/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆计划动作
 * @Version      : V1.0
 * @Description  : 生成 CHECK_LOAD_CHANGE → APPLY_LOAD_CHANGE 官方计划草稿；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.process;

import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.DefinitionEdgeBo;
import com.innolux.iws.process.api.DefinitionEventBo;
import com.innolux.iws.process.api.DefinitionStateBo;
import com.innolux.iws.process.api.SystemPolicyBo;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.runtime.SupervisorRules;
import com.innolux.iws.wms.allocation.process.WmsAllocationSupervisorStateValidator;
import com.innolux.iws.wms.allocation.process.WmsInventoryProcessConfiguration;

import java.util.List;

/** 装拆计划官方草稿工厂。 */
public final class WmsLoadChangePlanDefinitionFactory {

    public static final String PLAN_TYPE_LOAD_CHANGE = "LOAD_CHANGE";

    private static final String SYSTEM = "IWS";

    private WmsLoadChangePlanDefinitionFactory() {
    }

    /**
     * 构造装拆业务计划草稿。
     *
     * @param planCode 计划编码
     * @param planName 计划名称
     * @param businessScheduleCode 业务调度编码
     * @return 草稿
     */
    public static DefinitionDraftBo loadChangeDraft(
        String planCode,
        String planName,
        String businessScheduleCode
    ) {
        return new DefinitionDraftBo(
            planCode,
            planName,
            1,
            PLAN_TYPE_LOAD_CHANGE,
            null,
            businessScheduleCode,
            SYSTEM,
            PlanRole.BUSINESS.name(),
            null,
            null,
            null,
            30_000L,
            CompletionPolicy.ALL_SELECTED.name(),
            null,
            32,
            5_000L,
            32,
            5_000L,
            30_000L,
            2,
            1_000L,
            null,
            null,
            null,
            0,
            planName,
            0L,
            null,
            loadStates(),
            loadEvents(),
            List.of(edge(
                WmsLoadChangeCheckAction.ACTION_KEY,
                WmsLoadChangeAction.ACTION_KEY
            )),
            List.of(new SystemPolicyBo(SYSTEM, PostingMode.AUTOMATIC.name(), null, null, null))
        );
    }

    /**
     * 构造装拆监督计划草稿。
     *
     * @param planCode 计划编码
     * @param planName 计划名称
     * @param businessScheduleCode 业务调度编码
     * @return 草稿
     */
    public static DefinitionDraftBo supervisorLoadChangeDraft(
        String planCode,
        String planName,
        String businessScheduleCode
    ) {
        return new DefinitionDraftBo(
            planCode,
            planName,
            1,
            PLAN_TYPE_LOAD_CHANGE,
            null,
            businessScheduleCode,
            SYSTEM,
            PlanRole.SUPERVISOR.name(),
            PlanMode.QUEUE.name(),
            8,
            32,
            null,
            CompletionPolicy.ALL_SELECTED.name(),
            null,
            32,
            5_000L,
            32,
            5_000L,
            30_000L,
            2,
            1_000L,
            null,
            null,
            null,
            0,
            planName,
            0L,
            null,
            supervisorStates(),
            supervisorEvents(),
            List.of(edge(SupervisorRules.ACTION_DECISION, SupervisorRules.ACTION_ADMIT)),
            List.of(new SystemPolicyBo(SYSTEM, PostingMode.AUTOMATIC.name(), null, null, null))
        );
    }

    private static List<DefinitionStateBo> loadStates() {
        return List.of(
            state(
                WmsLoadChangeStateValidator.STATE_START,
                "待预检",
                StateKind.START,
                false,
                10,
                WmsLoadChangeStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsLoadChangeStateValidator.STATE_CHECKED,
                "已预检",
                StateKind.NORMAL,
                false,
                20,
                WmsLoadChangeStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsLoadChangeStateValidator.STATE_END,
                "已提交",
                StateKind.END,
                true,
                30,
                WmsLoadChangeStateValidator.VALIDATOR_KEY
            )
        );
    }

    private static List<DefinitionStateBo> supervisorStates() {
        return List.of(
            state(
                WmsAllocationSupervisorStateValidator.STATE_START,
                "待决策",
                StateKind.START,
                false,
                10,
                WmsAllocationSupervisorStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationSupervisorStateValidator.STATE_DECIDED,
                "已决策",
                StateKind.NORMAL,
                false,
                20,
                WmsAllocationSupervisorStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationSupervisorStateValidator.STATE_END,
                "本轮已准入",
                StateKind.END,
                true,
                30,
                WmsAllocationSupervisorStateValidator.VALIDATOR_KEY
            )
        );
    }

    private static List<DefinitionEventBo> loadEvents() {
        return List.of(
            event(
                WmsLoadChangeCheckAction.ACTION_KEY,
                "装拆结构预检",
                WmsLoadChangeCheckAction.LOCAL_EFFECT_KEY,
                WmsLoadChangeStateValidator.STATE_START,
                WmsLoadChangeStateValidator.STATE_CHECKED,
                10
            ),
            event(
                WmsLoadChangeAction.ACTION_KEY,
                "装拆容量事实",
                WmsLoadChangeAction.LOCAL_EFFECT_KEY,
                WmsLoadChangeStateValidator.STATE_CHECKED,
                WmsLoadChangeStateValidator.STATE_END,
                20
            )
        );
    }

    private static List<DefinitionEventBo> supervisorEvents() {
        return List.of(
            event(
                SupervisorRules.ACTION_DECISION,
                "监督候选决策",
                EventRole.DECISION,
                "WMS:" + SupervisorRules.ACTION_DECISION,
                WmsAllocationSupervisorStateValidator.STATE_START,
                WmsAllocationSupervisorStateValidator.STATE_DECIDED,
                10
            ),
            event(
                SupervisorRules.ACTION_ADMIT,
                "监督逐候选准入",
                EventRole.LOCAL,
                "WMS:" + SupervisorRules.ACTION_ADMIT,
                WmsAllocationSupervisorStateValidator.STATE_DECIDED,
                WmsAllocationSupervisorStateValidator.STATE_END,
                20
            )
        );
    }

    private static DefinitionStateBo state(
        String code,
        String displayName,
        StateKind kind,
        boolean terminal,
        int sortOrder,
        String validatorKey
    ) {
        return new DefinitionStateBo(
            code,
            displayName,
            kind.name(),
            validatorKey,
            "1",
            null,
            terminal,
            sortOrder
        );
    }

    private static DefinitionEventBo event(
        String code,
        String displayName,
        String localEffectKey,
        String sourceState,
        String targetState,
        int priority
    ) {
        return event(
            code,
            displayName,
            EventRole.BUSINESS,
            localEffectKey,
            sourceState,
            targetState,
            priority
        );
    }

    private static DefinitionEventBo event(
        String code,
        String displayName,
        EventRole eventRole,
        String localEffectKey,
        String sourceState,
        String targetState,
        int priority
    ) {
        return new DefinitionEventBo(
            code,
            displayName,
            eventRole.name(),
            EventDirection.INTERNAL.name(),
            WmsInventoryProcessConfiguration.EVENT_TYPE_KEY,
            WmsInventoryProcessConfiguration.EVENT_TYPE_VERSION,
            null,
            null,
            null,
            code,
            "1",
            null,
            localEffectKey,
            SYSTEM,
            SYSTEM,
            null,
            null,
            null,
            sourceState,
            targetState,
            true,
            null,
            null,
            null,
            priority,
            null,
            null,
            null,
            null
        );
    }

    private static DefinitionEdgeBo edge(String predecessor, String successor) {
        return new DefinitionEdgeBo(
            predecessor,
            successor,
            "DEFAULT",
            BranchKind.SEQUENTIAL.name(),
            null,
            null,
            false,
            null,
            0
        );
    }
}
