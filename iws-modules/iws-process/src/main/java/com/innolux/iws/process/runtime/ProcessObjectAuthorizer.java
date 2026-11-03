/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 命令与查询共享的对象范围授权入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.PlanRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 复用 Sa-Token 功能权限之上的对象范围门禁。
 *
 * <p>已登录用户必须具有 {@code process:object:all}，或由任一已注册 ProcessAccessPolicy 明确放行；
 * 未注册业务范围时默认拒绝，策略异常按拒绝处理。系统调度/无安全会话路径不伪造登录身份。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessObjectAuthorizer {

    private static final String OBJECT_ALL_PERMISSION = "process:object:all";

    private static final Logger log = LoggerFactory.getLogger(ProcessObjectAuthorizer.class);

    private final RuntimeDao runtimeDao;

    private final ObjectProvider<ProcessAccessPolicy> policyProvider;

    /**
     * 构造授权入口。
     *
     * @param runtimeDao 运行时 DAO
     * @param policyProvider 业务范围策略
     */
    public ProcessObjectAuthorizer(RuntimeDao runtimeDao, ObjectProvider<ProcessAccessPolicy> policyProvider) {
        this.runtimeDao = runtimeDao;
        this.policyProvider = policyProvider;
    }

    /**
     * 校验实例访问。
     *
     * @param instanceId 实例 ID
     * @param action 动作
     */
    public void authorizeUserInstance(long instanceId, String action) {
        requireLogin();
        ProcessInstance instance = runtimeDao.instance(instanceId);
        authorize(accessTarget(instance, null, action));
    }

    /**
     * 校验事件访问，并复用其实例范围。
     *
     * @param eventId 事件 ID
     * @param action 动作
     */
    public void authorizeUserEvent(long eventId, String action) {
        requireLogin();
        ProcessEvent event = runtimeDao.event(eventId);
        ProcessInstance instance = runtimeDao.instance(event.getInstanceId());
        authorize(accessTarget(instance, eventId, action));
    }

    /**
     * 按实例角色解析统一的业务授权目标。
     *
     * <p>监督实例创建时仅写入计划类型和监督唯一键，因此其业务范围使用这两个字段；
     * 普通业务实例继续使用自身业务类型和业务标识。</p>
     *
     * @param instance 计划实例
     * @param eventId 事件 ID，无事件时为 null
     * @param action 动作
     * @return 对象授权目标
     */
    public ProcessAccessTarget accessTarget(ProcessInstance instance, Long eventId, String action) {
        boolean supervisor = instance.getPlanRole() == PlanRole.SUPERVISOR;
        return new ProcessAccessTarget(instance.getId(), eventId, instance.getScheduleId(),
            supervisor ? instance.getPlanType() : instance.getBusinessType(),
            supervisor ? instance.getSupervisorKey() : instance.getBusinessId(),
            instance.getTaskKey(), instance.getScheduleScope(), action);
    }

    /**
     * 校验业务调度范围，用于挂载/查询入口。
     *
     * @param scheduleId 业务调度 ID
     * @param action 动作
     */
    public void authorizeUserSchedule(long scheduleId, String action) {
        requireLogin();
        authorize(new ProcessAccessTarget(0L, null, scheduleId, null, null, null, null, action));
    }

    /**
     * 受信 worker 入口；必须由 job/worker 显式标记可信调用上下文。
     *
     * @param eventId 事件 ID
     * @param action 动作
     */
    public void authorizeTrustedEvent(long eventId, String action) {
        if (!ProcessTrustedInvocation.isActive()) {
            throw new ServiceException("受信 worker 调用缺少可信上下文");
        }
        runtimeDao.event(eventId);
    }

    /**
     * HTTP/查询兼容入口，语义等同用户授权；未登录拒绝。
     *
     * @param instanceId 实例 ID
     * @param action 动作
     */
    public void authorizeInstance(long instanceId, String action) {
        authorizeUserInstance(instanceId, action);
    }

    /**
     * HTTP/查询兼容入口，语义等同用户授权；未登录拒绝。
     *
     * @param eventId 事件 ID
     * @param action 动作
     */
    public void authorizeEvent(long eventId, String action) {
        authorizeUserEvent(eventId, action);
    }

    /**
     * HTTP/查询兼容入口，语义等同用户授权；未登录拒绝。
     *
     * @param scheduleId 业务调度 ID
     * @param action 动作
     */
    public void authorizeSchedule(long scheduleId, String action) {
        authorizeUserSchedule(scheduleId, action);
    }

    /**
     * 判定对象范围是否可见；多个策略中任一明确允许即可见，未登录或全部拒绝时返回 false。
     *
     * @param target 对象授权目标
     * @return true 表示允许访问
     */
    public boolean canAccess(ProcessAccessTarget target) {
        requireLogin();
        if (StpUtil.hasPermission(OBJECT_ALL_PERMISSION)) {
            return true;
        }
        return policyProvider.orderedStream().anyMatch(policy -> policyAllows(policy, target));
    }

    private static boolean policyAllows(ProcessAccessPolicy policy, ProcessAccessTarget target) {
        try {
            return policy.canAccess(target);
        } catch (RuntimeException ex) {
            log.warn("计划对象授权策略执行异常，按拒绝处理: {}", policy.getClass().getName(), ex);
            return false;
        }
    }

    private void authorize(ProcessAccessTarget target) {
        if (!canAccess(target)) {
            throw new ServiceException("无权限访问该业务范围");
        }
    }

    private void requireLogin() {
        if (!StpUtil.isLogin()) {
            throw new ServiceException("未登录，不能访问计划事件对象");
        }
    }
}
