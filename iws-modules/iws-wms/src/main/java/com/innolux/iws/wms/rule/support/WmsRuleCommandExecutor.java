/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则命令事务边界、失败留证与提交结果未知复核；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.WmsCommandAttempt;
import com.innolux.iws.wms.rule.WmsRuleContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 规则命令执行器。
 *
 * <p>与公司最小闭环保持一致：命令入口自行拥有完整事务边界，事务使用 REQUIRES_NEW，方法体异常经
 * 事务模板真实回滚后在独立事务写失败尝试；提交阶段异常不直接判定失败，必须按命令键、动作和输入摘要
 * 复核 attempt 后才认定生效或未知。</p>
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WmsRuleCommandExecutor {

    private final WmsRuleAuditService auditService;

    private final PlatformTransactionManager transactionManager;

    /**
     * 执行一条规则命令。
     *
     * @param action     命令动作
     * @param objectType 审计对象类型
     * @param objectId   对象 ID，创建前可为 null
     * @param commandKey 命令键
     * @param input      命令输入
     * @param isolation  事务隔离级别
     * @param body       事务方法体
     * @param readBack   提交响应异常但已确认生效时的读回，可为 null
     * @param <T>        返回类型
     * @return 方法体结果或确认生效后的读回结果
     */
    public <T> T execute(String action, String objectType, Long objectId, String commandKey, Object input,
                         Isolation isolation, Supplier<T> body, Supplier<T> readBack) {
        assertOutsideCommandTransaction();
        LocalDateTime occurredAt = LocalDateTime.now();
        String digest = auditService.digest(input);
        boolean[] completed = {false};
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.setIsolationLevel(isolation.value());
            T result = template.execute(status -> {
                T value = body.get();
                completed[0] = true;
                return value;
            });
            return result;
        } catch (RuntimeException error) {
            if (handleCommandFailure(action, objectType, objectId, commandKey, input, digest, error,
                occurredAt, completed[0])) {
                return readBack == null ? null : readBack.get();
            }
            throw error;
        }
    }

    /**
     * 命令入口必须自行拥有完整事务边界，禁止加入调用方已有事务。
     */
    private void assertOutsideCommandTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("WMS规则命令禁止加入已有事务，必须在事务边界之外调用");
        }
    }

    /**
     * 处理命令失败；返回 true 表示提交阶段异常经安全复核确认命令已生效。
     */
    private boolean handleCommandFailure(String action, String objectType, Long objectId, String commandKey,
                                         Object input, String inputDigest, RuntimeException error,
                                         LocalDateTime occurredAt, boolean bodyCompleted) {
        if (!bodyCompleted || error instanceof UnexpectedRollbackException) {
            auditService.recordFailure(action, objectType, objectId, commandKey, input, error, occurredAt);
            return false;
        }
        WmsCommandAttempt attempt;
        try {
            attempt = auditService.findAttempt(commandKey);
        } catch (RuntimeException lookupError) {
            log.error("规则命令提交结果无法复核: commandKey={}", commandKey, lookupError);
            throw new ServiceException(
                "COMMIT_OUTCOME_UNKNOWN: 提交结果未知，请使用相同命令键重试",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (attempt == null) {
            log.error("规则命令提交结果未知，复核未找到对应 attempt: commandKey={}", commandKey, error);
            throw new ServiceException(
                "COMMIT_OUTCOME_UNKNOWN: 提交结果未知，请使用相同命令键重试",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (!StrUtil.equals(attempt.getAction(), auditService.attemptAction(action))
            || !StrUtil.equals(attempt.getInputDigest(), inputDigest)) {
            throw new ServiceException(
                "COMMIT_KEY_CONFLICT: 命令键已被不同命令占用",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (WmsRuleContract.ATTEMPT_APPLIED.equals(attempt.getStatus())) {
            log.warn("规则命令提交响应异常但已确认生效: commandKey={}", commandKey, error);
            return true;
        }
        log.warn("规则命令提交响应异常且复核为已记录失败: commandKey={}", commandKey, error);
        return false;
    }
}
