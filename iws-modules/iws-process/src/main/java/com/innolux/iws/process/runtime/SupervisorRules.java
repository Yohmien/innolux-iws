/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督模式、名额和公平窗口的共享校验
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.support.ProcessText;

import java.util.Objects;

/**
 * 监督运行共享规则。只表达名额、公平窗口和稳定键，不访问数据库。
 */
public final class SupervisorRules {

    /** 监督决策本地动作键。 */
    public static final String ACTION_DECISION = "SUPERVISOR_CANDIDATE_DECISION";

    /** 监督准入本地动作键。 */
    public static final String ACTION_ADMIT = "SUPERVISOR_ADMIT_QUEUE";

    /** 监督调度控制职责。 */
    public static final String DUTY_DISPATCH = "DISPATCH";

    /** 计划名额控制职责。 */
    public static final String DUTY_SLOT = "PLAN_SLOT";

    private SupervisorRules() {
    }

    /**
     * 监督冻结名额上限，缺失或非正数直接失败。
     *
     * @param definition 监督定义
     * @return 上限
     */
    public static int activePlanLimit(PlanDefinition definition) {
        if (definition == null || definition.getActivePlanLimit() == null
            || definition.getActivePlanLimit() <= 0) {
            throw new ServiceException("监督计划未配置正数 activePlanLimit");
        }
        if (definition.getMode() == PlanMode.IMMEDIATE && definition.getActivePlanLimit() != 1) {
            throw new ServiceException("IMMEDIATE activePlanLimit 必须为 1");
        }
        return definition.getActivePlanLimit();
    }

    /**
     * 解析 QUEUE 公平窗口。业务调度共享配置优先，定义中的不同值视为冲突。
     *
     * @param definition 监督定义
     * @param schedule 业务调度
     * @return 正整数 K
     */
    public static int fairnessWindow(PlanDefinition definition, BusinessSchedule schedule) {
        Integer shared = schedule == null ? null : schedule.getFairnessWindowSize();
        Integer frozen = definition == null ? null : definition.getFairnessWindowSize();
        if (shared != null && frozen != null && !Objects.equals(shared, frozen)) {
            throw new ServiceException("公平窗口配置冲突: definition=" + frozen + ", schedule=" + shared);
        }
        Integer value = shared != null ? shared : frozen;
        if (value == null || value <= 0) {
            throw new ServiceException("QUEUE 监督未配置正数 fairnessWindowSize");
        }
        return value;
    }

    /**
     * 当前窗口内的下一次准入序号。
     *
     * @param fairnessCount 已完成成功准入数
     * @param window 窗口大小 K
     * @return 1..K
     */
    public static int nextWindowIndex(int fairnessCount, int window) {
        return Math.floorMod(fairnessCount, window) + 1;
    }

    /**
     * 本次准入后保存的窗口计数；窗口末位后归零。
     *
     * @param fairnessCount 完成前计数
     * @param window 窗口大小 K
     * @return 完成后计数
     */
    public static int nextWindowCount(int fairnessCount, int window) {
        int next = nextWindowIndex(fairnessCount, window);
        return next == window ? 0 : next;
    }

    /**
     * 本次准入是否为窗口末位保底。
     *
     * @param fairnessCount 完成前计数
     * @param window 窗口大小 K
     * @return 是否保底
     */
    public static boolean fairnessTurn(int fairnessCount, int window) {
        return nextWindowIndex(fairnessCount, window) == window;
    }

    /**
     * 是否为未人工提升的普通候选。
     *
     * @param child 子计划
     * @return 是否普通
     */
    public static boolean normal(ProcessInstance child) {
        return child != null && (child.getInstancePriority() == null || child.getInstancePriority() <= 0);
    }

    /**
     * 监督定义必须为已发布可运行角色。
     *
     * @param definition 监督定义
     */
    public static void requireSupervisorDefinition(PlanDefinition definition) {
        if (definition == null || definition.getPlanRole() != PlanRole.SUPERVISOR) {
            throw new ServiceException("监督冻结定义不存在或角色不符");
        }
        if (definition.getMode() == null) {
            throw new ServiceException("监督定义未配置 QUEUE/IMMEDIATE 模式");
        }
        activePlanLimit(definition);
        if (definition.getMode() == PlanMode.QUEUE) {
            if (definition.getFairnessWindowSize() != null && definition.getFairnessWindowSize() <= 0) {
                throw new ServiceException("fairnessWindowSize 必须为正数");
            }
        }
    }

    /**
     * 构造不超过数据库长度的稳定幂等键。
     *
     * @param prefix 前缀
     * @param parts 稳定片段
     * @return 幂等键
     */
    public static String stableKey(String prefix, String... parts) {
        String value = prefix + ":" + String.join(":", parts);
        if (value.length() <= 240) {
            return value;
        }
        return value.substring(0, 190) + ":" + ProcessText.sha256(value).substring(0, 32);
    }
}
