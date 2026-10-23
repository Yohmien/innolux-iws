/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划实例动作准入描述；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划实例可用动作的唯一准入描述。
 *
 * <p>命令入口与只读快照共用本类判断业务状态；功能权限与对象范围仍由控制器及
 * {@link ProcessObjectAuthorizer} 分别校验，本类不替代鉴权。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process",
    name = "enabled", havingValue = "true")
public class PlanInstanceActionPolicy {

    public static final String CONFIRM_POSTING = "CONFIRM_POSTING";

    public static final String PAUSE = "PAUSE";

    public static final String RESUME = "RESUME";

    public static final String CANCEL = "CANCEL";

    public static final String HOLD_START = "HOLD_START";

    public static final String RELEASE_START = "RELEASE_START";

    public static final String PRIORITY = "PRIORITY";

    private static final String DISPATCH_DUTY = "DISPATCH";

    private final RuntimeDao runtimeDao;

    private final ProcessSystemContextMapper contextMapper;

    private final ProcessScheduleControlMapper controlMapper;

    /**
     * 构造实例动作准入描述。
     *
     * @param runtimeDao 运行时 DAO
     * @param contextMapper 系统上下文 Mapper
     * @param controlMapper 控制 Mapper
     */
    public PlanInstanceActionPolicy(RuntimeDao runtimeDao, ProcessSystemContextMapper contextMapper,
                                    ProcessScheduleControlMapper controlMapper) {
        this.runtimeDao = runtimeDao;
        this.contextMapper = contextMapper;
        this.controlMapper = controlMapper;
    }

    /**
     * 按实例 ID 装载并描述动作。
     *
     * @param instanceId 实例 ID
     * @return 动作描述
     */
    public Admission describe(long instanceId) {
        ProcessInstance instance = runtimeDao.instance(instanceId);
        List<ProcessSystemContext> contexts = contextMapper.selectList(
            Wrappers.<ProcessSystemContext>lambdaQuery()
                .eq(ProcessSystemContext::getInstanceId, instanceId)
                .orderByAsc(ProcessSystemContext::getSystemCode));
        return describe(instance, runtimeDao.events(instanceId), contexts);
    }

    /**
     * 使用调用方已装载的实例快照描述动作，供查询层复用。
     *
     * @param instance 实例
     * @param events 实例事件
     * @param contexts 系统上下文
     * @return 动作描述
     */
    public Admission describe(ProcessInstance instance, List<ProcessEvent> events,
                              List<ProcessSystemContext> contexts) {
        Map<String, Boolean> allowedActions = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        decide(CONFIRM_POSTING, confirmPostingRejection(contexts), allowedActions, reasons);
        decide(PAUSE, pauseRejection(instance), allowedActions, reasons);
        decide(RESUME, resumeRejection(instance), allowedActions, reasons);
        decide(CANCEL, null, allowedActions, reasons);
        decide(HOLD_START, holdStartRejection(instance), allowedActions, reasons);
        decide(RELEASE_START, releaseStartRejection(instance), allowedActions, reasons);
        decide(PRIORITY, priorityRejection(events), allowedActions, reasons);
        return new Admission(allowedActions, reasons);
    }

    /**
     * 实例级首次确认准入。
     *
     * @param contexts 系统上下文
     * @return 不可用原因；可用时为 {@code null}
     */
    public String confirmPostingRejection(List<ProcessSystemContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "实例未冻结系统模式";
        }
        if (contexts.stream().noneMatch(item -> item.getPostingMode() == PostingMode.MANUAL_CONFIRM)) {
            return "没有人工确认模式的系统";
        }
        if (contexts.stream().noneMatch(item -> item.getPostingMode() == PostingMode.MANUAL_CONFIRM
            && !Boolean.TRUE.equals(item.getFirstConfirmed()))) {
            return "人工确认系统均已确认";
        }
        return null;
    }

    /**
     * 逐系统首次确认准入。
     *
     * @param context 系统上下文
     * @param systemCode 系统编码
     * @return 不可用原因；可用时为 {@code null}
     */
    public String confirmSystemRejection(ProcessSystemContext context, String systemCode) {
        if (context == null) {
            return "实例未冻结系统模式: " + systemCode;
        }
        if (context.getPostingMode() != PostingMode.MANUAL_CONFIRM) {
            return "系统不是人工确认模式，整批拒绝: " + systemCode;
        }
        return null;
    }

    /**
     * 暂停准入。
     *
     * @param instance 实例
     * @return 不可用原因；可用时为 {@code null}
     */
    public String pauseRejection(ProcessInstance instance) {
        if (instance == null) {
            return "计划实例不存在";
        }
        if (instance.getPlanStatus() == PlanStatus.COMPLETED
            || instance.getPlanStatus() == PlanStatus.CANCELLED) {
            return "终态计划不能暂停";
        }
        return null;
    }

    /**
     * 恢复准入。
     *
     * @param instance 实例
     * @return 不可用原因；可用时为 {@code null}
     */
    public String resumeRejection(ProcessInstance instance) {
        if (instance == null) {
            return "计划实例不存在";
        }
        if (instance.getPlanStatus() != PlanStatus.PAUSED) {
            return "只有 PAUSED 计划可 resume";
        }
        if (!hasPlanSlot(instance.getId())) {
            return "计划未取得 PLAN_SLOT，必须由监督准入后再恢复";
        }
        if (instance.getSupervisorInstanceId() != null) {
            ProcessScheduleControl supervisor = controlMapper.selectOne(
                Wrappers.<ProcessScheduleControl>lambdaQuery()
                    .eq(ProcessScheduleControl::getSupervisorInstanceId, instance.getSupervisorInstanceId())
                    .eq(ProcessScheduleControl::getDuty, DISPATCH_DUTY));
            if (supervisor != null && supervisor.getControlState() == ControlState.MANUAL_PAUSED) {
                return "监督已暂停，不能恢复子计划";
            }
        }
        return null;
    }

    /**
     * 准入前挂起。
     *
     * @param instance 实例
     * @return 不可用原因；可用时为 {@code null}
     */
    public String holdStartRejection(ProcessInstance instance) {
        if (instance == null || instance.getPlanStatus() != PlanStatus.CREATED
            || hasPlanSlot(instance.getId())) {
            return "ALREADY_ADMITTED: 只有未准入计划可 hold-start";
        }
        return null;
    }

    /**
     * 解除准入前挂起。
     *
     * @param instance 实例
     * @return 不可用原因；可用时为 {@code null}
     */
    public String releaseStartRejection(ProcessInstance instance) {
        if (instance == null || instance.getPlanStatus() != PlanStatus.CREATED
            || !Boolean.TRUE.equals(instance.getHoldStart())) {
            return "只有未准入挂起计划可 release-start";
        }
        return null;
    }

    /**
     * 实例优先级准入。
     *
     * @param events 实例事件
     * @return 不可用原因；可用时为 {@code null}
     */
    public String priorityRejection(List<ProcessEvent> events) {
        if (events != null && events.stream().anyMatch(item -> item.getEventStatus() == EventStatus.PROCESSING)) {
            return "正在执行的实例不能调整优先级";
        }
        return null;
    }

    /**
     * 判断实例是否持有 PLAN_SLOT。
     *
     * @param instanceId 实例 ID
     * @return 是否持有
     */
    public boolean hasPlanSlot(long instanceId) {
        return controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(instanceId))
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD)) > 0;
    }

    private static void decide(String action, String reason, Map<String, Boolean> allowedActions,
                               Map<String, String> reasons) {
        allowedActions.put(action, reason == null);
        if (reason != null) {
            reasons.put(action, reason);
        }
    }

    /**
     * 实例动作描述。
     *
     * @param allowedActions 动作可用性
     * @param reasons 不可用原因
     */
    public record Admission(Map<String, Boolean> allowedActions, Map<String, String> reasons) {

        /**
         * 规整为只读快照。
         */
        public Admission {
            allowedActions = Collections.unmodifiableMap(new LinkedHashMap<>(allowedActions));
            reasons = Collections.unmodifiableMap(new LinkedHashMap<>(reasons));
        }

        /**
         * 判断动作是否可用。
         *
         * @param action 动作
         * @return 是否可用
         */
        public boolean allowed(String action) {
            return Boolean.TRUE.equals(allowedActions.get(action));
        }

        /**
         * 返回当前可用动作编码，供实例执行链列表契约复用。
         *
         * @return 可用动作
         */
        public List<String> allowedActionCodes() {
            return allowedActions.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .toList();
        }
    }
}
