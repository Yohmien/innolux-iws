/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划同步执行应用服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.api.ImmediateExecutionVO;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.support.ProcessText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 即时计划同步命令入口，负责幂等、期限竞争、有界驱动与结果收口。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediateExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ImmediateExecutionService.class);

    private static final int COMMAND_KEY_LIMIT = 128;

    private static final int SHORT_CODE_LIMIT = 64;

    private static final int BUSINESS_ID_LIMIT = 128;

    private static final int TASK_KEY_LIMIT = 128;

    private static final int HASH_LIMIT = 128;

    private final ImmediateAdmissionService admissionService;

    private final ImmediateExecutionDriver driver;

    private final ImmediateOutcomeService outcomeService;

    private final ImmediateExecutionGuard guard;

    private final ImmediateCommandReceiptStore receiptStore;

    private final ProcessObjectAuthorizer objectAuthorizer;

    /**
     * 构造应用服务。
     *
     * @param admissionService 准入服务
     * @param driver 同步驱动
     * @param outcomeService 收口服务
     * @param guard 执行资源
     * @param receiptStore 回执存储
     * @param objectAuthorizer 公共对象范围门禁
     */
    public ImmediateExecutionService(ImmediateAdmissionService admissionService, ImmediateExecutionDriver driver,
                                     ImmediateOutcomeService outcomeService, ImmediateExecutionGuard guard,
                                     ImmediateCommandReceiptStore receiptStore,
                                     ProcessObjectAuthorizer objectAuthorizer) {
        this.admissionService = admissionService;
        this.driver = driver;
        this.outcomeService = outcomeService;
        this.guard = guard;
        this.receiptStore = receiptStore;
        this.objectAuthorizer = objectAuthorizer;
    }

    /**
     * 同步执行一次即时计划。
     *
     * @param command 冻结合同命令
     * @return 当前命令事实
     */
    public ImmediateExecutionVO execute(ExecuteImmediateCommand command) {
        validate(command);
        objectAuthorizer.authorizeUserSchedule(command.scheduleId(), "EXECUTE_IMMEDIATE");
        ImmediateExecutionVO existing = receiptStore.findView(command.scheduleId(), command.commandKey());
        if (existing != null) {
            requireSameRequest(command);
            return existing;
        }
        if (!guard.ready()) {
            return rejectBeforeAdmission(command, "NOT_READY", "即时执行资源或响应收口预算未配置");
        }
        if (!guard.tryAcquire()) {
            return rejectBeforeAdmission(command, "NOT_READY", "即时执行并发资源已满");
        }
        boolean releaseManagedByFuture = false;
        try {
            ImmediateAdmissionService.Admission admission = admissionService.admit(command);
            if (!admission.admitted()) {
                return admission.view();
            }
            long remaining = Duration.between(LocalDateTime.now(), admission.deadline()).toMillis()
                - guard.responseReserveMs();
            if (remaining <= 0) {
                return outcomeService.timeout(admission);
            }
            CompletableFuture<ImmediateExecutionDriver.DriveResult> future = CompletableFuture.supplyAsync(
                () -> ProcessTrustedInvocation.call(() -> driver.drive(admission)), guard.executor());
            releaseManagedByFuture = true;
            AtomicBoolean deadlineReturned = new AtomicBoolean(false);
            future.whenComplete((result, error) -> {
                guard.release();
                if (deadlineReturned.get()) {
                    try {
                        ProcessTrustedInvocation.run(() -> outcomeService.reconcileLate(admission));
                    } catch (RuntimeException ex) {
                        log.warn("即时期限后证据收口失败: commandKey={}", command.commandKey(), ex);
                    }
                }
            });
            try {
                ImmediateExecutionDriver.DriveResult result = future.get(remaining, TimeUnit.MILLISECONDS);
                return outcomeService.finalize(admission, result);
            } catch (TimeoutException ex) {
                deadlineReturned.set(true);
                ImmediateExecutionVO view = outcomeService.timeout(admission);
                if (future.isDone()) {
                    outcomeService.reconcileLate(admission);
                }
                return view;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return outcomeService.infrastructureFailure(admission, "即时请求线程被中断");
            } catch (ExecutionException ex) {
                return outcomeService.infrastructureFailure(admission,
                    "即时驱动异常: " + rootMessage(ex));
            }
        } catch (DuplicateKeyException ex) {
            ImmediateExecutionVO raced = receiptStore.findView(command.scheduleId(), command.commandKey());
            if (raced == null) {
                throw ex;
            }
            requireSameRequest(command);
            return raced;
        } finally {
            if (!releaseManagedByFuture) {
                guard.release();
            }
        }
    }

    /**
     * 只读查询当前命令事实，不执行命令也不追加审计。
     *
     * @param scheduleId 业务调度 ID
     * @param commandKey 命令键
     * @return 当前事实或 NOT_FOUND
     */
    public ImmediateExecutionVO find(long scheduleId, String commandKey) {
        if (scheduleId <= 0) {
            throw new ServiceException("scheduleId 必须为正整数");
        }
        String key = ProcessText.required(commandKey, "commandKey");
        if (key.length() > COMMAND_KEY_LIMIT) {
            throw new ServiceException("commandKey 超过长度限制");
        }
        objectAuthorizer.authorizeUserSchedule(scheduleId, "QUERY");
        ImmediateExecutionVO value = receiptStore.findView(scheduleId, key);
        if (value != null) {
            return value;
        }
        return new ImmediateExecutionVO(key, "NOT_FOUND", "当前时点无可见回执", null, null,
            null, null, null, false, "/process/schedule/" + scheduleId + "/commands/" + key,
            null, LocalDateTime.now());
    }

    private ImmediateExecutionVO rejectBeforeAdmission(ExecuteImmediateCommand command, String resultCode,
                                                       String message) {
        LocalDateTime now = LocalDateTime.now();
        try {
            receiptStore.writeAnchor(command, null, null, null, resultCode, message, null, false,
                now, now, AuditResult.REJECTED);
        } catch (DuplicateKeyException ex) {
            ImmediateExecutionVO raced = receiptStore.findView(command.scheduleId(), command.commandKey());
            if (raced == null) {
                throw ex;
            }
            requireSameRequest(command);
            return raced;
        }
        return receiptStore.findView(command.scheduleId(), command.commandKey());
    }

    private void requireSameRequest(ExecuteImmediateCommand command) {
        ProcessActionAudit anchor = receiptStore.findAnchor(command.scheduleId(), command.commandKey());
        if (anchor == null || !receiptStore.sameRequest(anchor, command)) {
            throw new ServiceException("COMMAND_KEY_CONFLICT: 同一 commandKey 的请求参数不同");
        }
    }

    private void validate(ExecuteImmediateCommand command) {
        if (command == null || command.scheduleId() <= 0) {
            throw new ServiceException("scheduleId 必须为正整数");
        }
        require(command.planType(), "planType", SHORT_CODE_LIMIT);
        require(command.businessType(), "businessType", SHORT_CODE_LIMIT);
        require(command.businessId(), "businessId", BUSINESS_ID_LIMIT);
        require(command.taskKey(), "taskKey", TASK_KEY_LIMIT);
        require(command.inputHash(), "inputHash", HASH_LIMIT);
        require(command.commandKey(), "commandKey", COMMAND_KEY_LIMIT);
    }

    private void require(String value, String name, int limit) {
        String text = ProcessText.required(value, name);
        if (text.length() > limit) {
            throw new ServiceException(name + " 超过长度限制: " + limit);
        }
    }

    private String rootMessage(ExecutionException ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
