/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划有界同步事件驱动；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PlanStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 只通过唯一 EventUpdateService 推进即时业务事件。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediateExecutionDriver {

    private final RuntimeDao runtimeDao;

    private final EventUpdateService eventUpdateService;

    private final ImmediateExecutionGuard guard;

    /**
     * 构造驱动。
     *
     * @param runtimeDao 运行时 DAO
     * @param eventUpdateService 唯一事件更新入口
     * @param guard 执行资源
     */
    public ImmediateExecutionDriver(RuntimeDao runtimeDao, EventUpdateService eventUpdateService,
                                    ImmediateExecutionGuard guard) {
        this.runtimeDao = runtimeDao;
        this.eventUpdateService = eventUpdateService;
        this.guard = guard;
    }

    /**
     * 驱动到终态、明确失败或期限边界。
     *
     * @param admission 已提交准入
     * @return 驱动结果
     */
    public DriveResult drive(ImmediateAdmissionService.Admission admission) {
        long maxSteps = runtimeDao.graph(admission.businessDefinition().getId()).eventCodes().size() + 2L;
        long steps = 0L;
        while (steps++ < maxSteps) {
            ProcessInstance instance = runtimeDao.instance(admission.instance().getId());
            PlanStatus planStatus = instance.getPlanStatus();
            if (planStatus == PlanStatus.COMPLETED) {
                return new DriveResult(Outcome.SUCCEEDED, "计划已完成");
            }
            if (planStatus == PlanStatus.FAILED_FINAL) {
                return new DriveResult(Outcome.FAILED, "计划已确定失败");
            }
            boolean stopped = Boolean.TRUE.equals(instance.getStopRequested())
                || Boolean.TRUE.equals(instance.getCancelRequested());
            if (stopped) {
                return new DriveResult(Outcome.TIMEOUT, "计划已请求停止，不再启动后继事件");
            }
            LocalDateTime deadline = instance.getExecutionDeadline() == null
                ? admission.deadline() : instance.getExecutionDeadline();
            if (remainingMs(deadline) <= guard.responseReserveMs()) {
                return new DriveResult(Outcome.TIMEOUT, "执行期限剩余预算不足");
            }
            if (planStatus == PlanStatus.EXCEPTION_HOLD) {
                return new DriveResult(Outcome.UNKNOWN, "计划处于异常停留");
            }
            if (planStatus == PlanStatus.MANUAL_PENDING) {
                return new DriveResult(Outcome.FAILED, "计划已确定失败待安全终结");
            }
            List<ProcessEvent> pending = runtimeDao.events(instance.getId()).stream()
                .filter(event -> event.getEventStatus() == EventStatus.PENDING)
                .sorted(Comparator.comparing((ProcessEvent event) -> event.getPriority() == null ? 0
                    : event.getPriority()).reversed().thenComparing(ProcessEvent::getId))
                .toList();
            if (pending.isEmpty()) {
                return new DriveResult(Outcome.BLOCKED, "即时图没有可执行事件或存在未收敛等待");
            }
            for (ProcessEvent event : pending) {
                if (remainingMs(deadline) <= guard.responseReserveMs()) {
                    return new DriveResult(Outcome.TIMEOUT, "执行期限剩余预算不足");
                }
                EventUpdateService.UpdateResult result = eventUpdateService.update(event.getId(), admission.owner());
                if (result == EventUpdateService.UpdateResult.UNKNOWN) {
                    return new DriveResult(Outcome.UNKNOWN, "事件结果未知，需对账");
                }
                if (result == EventUpdateService.UpdateResult.FAILED) {
                    return new DriveResult(Outcome.FAILED, "事件确定失败");
                }
                if (result == EventUpdateService.UpdateResult.RACED) {
                    ProcessEvent latest = runtimeDao.event(event.getId());
                    if (latest.getEventStatus() != EventStatus.PENDING) {
                        continue;
                    }
                    return new DriveResult(Outcome.BLOCKED, "事件认领竞争或资源容量不足");
                }
            }
        }
        return new DriveResult(Outcome.BLOCKED, "即时驱动超过冻结图事件上限");
    }

    private static long remainingMs(LocalDateTime deadline) {
        return Math.max(0L, Duration.between(LocalDateTime.now(), deadline).toMillis());
    }

    /**
     * 驱动分类。
     */
    public enum Outcome {
        SUCCEEDED,
        FAILED,
        UNKNOWN,
        TIMEOUT,
        BLOCKED
    }

    /**
     * 驱动结果。
     *
     * @param outcome 分类
     * @param message 中文说明
     */
    public record DriveResult(Outcome outcome, String message) {
    }
}
