/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划
 * @Version      : V1.0
 * @Description  : 生成按业务调度编码绑定的分配锁位和释放监督计划草稿；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

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

import java.util.List;

/** 分配计划官方草稿工厂。 */
public final class WmsAllocationPlanDefinitionFactory {

    public static final String PLAN_TYPE_LOCK = "ALLOCATION_LOCK";

    public static final String PLAN_TYPE_RELEASE = "ALLOCATION_RELEASE";

    private static final String SYSTEM = "IWS";

    private WmsAllocationPlanDefinitionFactory() {
    }

    /**
     * 构造分配锁位监督计划草稿。
     *
     * @param planCode 计划编码
     * @param planName 计划名称
     * @param businessScheduleCode 业务调度编码
     * @return 草稿
     */
    public static DefinitionDraftBo lockDraft(
        String planCode,
        String planName,
        String businessScheduleCode
    ) {
        return businessDraft(
            planCode,
            planName,
            PLAN_TYPE_LOCK,
            businessScheduleCode,
            lockStates(),
            lockEvents(),
            List.of(
                edge(WmsAllocationEvaluateAction.ACTION_KEY, WmsAllocationReserveAction.ACTION_KEY),
                edge(WmsAllocationReserveAction.ACTION_KEY, WmsAllocationVerifyAction.ACTION_KEY)
            )
        );
    }

    /**
     * 构造分配释放监督计划草稿。
     *
     * @param planCode 计划编码
     * @param planName 计划名称
     * @param businessScheduleCode 业务调度编码
     * @return 草稿
     */
    public static DefinitionDraftBo releaseDraft(
        String planCode,
        String planName,
        String businessScheduleCode
    ) {
        return businessDraft(
            planCode,
            planName,
            PLAN_TYPE_RELEASE,
            businessScheduleCode,
            releaseStates(),
            releaseEvents(),
            List.of(
                edge(
                    WmsAllocationCheckReleaseAction.ACTION_KEY,
                    WmsAllocationReleaseAction.ACTION_KEY
                ),
                edge(
                    WmsAllocationReleaseAction.ACTION_KEY,
                    WmsAllocationVerifyReleaseAction.ACTION_KEY
                )
            )
        );
    }

    /**
     * 构造分配锁位监督计划草稿。
     *
     * @param planCode 计划编码
     * @param planName 计划名称
     * @param businessScheduleCode 业务调度编码
     * @return 草稿
     */
    public static DefinitionDraftBo supervisorLockDraft(
        String planCode,
        String planName,
        String businessScheduleCode
    ) {
        return supervisorDraft(
            planCode,
            planName,
            PLAN_TYPE_LOCK,
            businessScheduleCode
        );
    }

    /**
     * 构造分配释放监督计划草稿。
     *
     * @param planCode 计划编码
     * @param planName 计划名称
     * @param businessScheduleCode 业务调度编码
     * @return 草稿
     */
    public static DefinitionDraftBo supervisorReleaseDraft(
        String planCode,
        String planName,
        String businessScheduleCode
    ) {
        return supervisorDraft(
            planCode,
            planName,
            PLAN_TYPE_RELEASE,
            businessScheduleCode
        );
    }

    private static DefinitionDraftBo businessDraft(
        String planCode,
        String planName,
        String planType,
        String businessScheduleCode,
        List<DefinitionStateBo> states,
        List<DefinitionEventBo> events,
        List<DefinitionEdgeBo> edges
    ) {
        return new DefinitionDraftBo(
            planCode,
            planName,
            1,
            planType,
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
            states,
            events,
            edges,
            List.of(new SystemPolicyBo(SYSTEM, PostingMode.AUTOMATIC.name(), null, null, null))
        );
    }

    private static DefinitionDraftBo supervisorDraft(
        String planCode,
        String planName,
        String planType,
        String businessScheduleCode
    ) {
        return new DefinitionDraftBo(
            planCode,
            planName,
            1,
            planType,
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
            supervisorEdges(),
            List.of(new SystemPolicyBo(SYSTEM, PostingMode.AUTOMATIC.name(), null, null, null))
        );
    }

    private static List<DefinitionStateBo> lockStates() {
        return List.of(
            state(
                WmsAllocationLockStateValidator.STATE_START,
                "待评估",
                StateKind.START,
                false,
                10,
                WmsAllocationLockStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationLockStateValidator.STATE_EVALUATED,
                "已评估",
                StateKind.NORMAL,
                false,
                20,
                WmsAllocationLockStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationLockStateValidator.STATE_RESERVED,
                "已锁位",
                StateKind.NORMAL,
                false,
                30,
                WmsAllocationLockStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationLockStateValidator.STATE_END,
                "已完成",
                StateKind.END,
                true,
                40,
                WmsAllocationLockStateValidator.VALIDATOR_KEY
            )
        );
    }

    private static List<DefinitionStateBo> releaseStates() {
        return List.of(
            state(
                WmsAllocationReleaseStateValidator.STATE_START,
                "待检查",
                StateKind.START,
                false,
                10,
                WmsAllocationReleaseStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationReleaseStateValidator.STATE_EVIDENCE_CHECKED,
                "证据已检查",
                StateKind.NORMAL,
                false,
                20,
                WmsAllocationReleaseStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationReleaseStateValidator.STATE_RELEASED,
                "已释放",
                StateKind.NORMAL,
                false,
                30,
                WmsAllocationReleaseStateValidator.VALIDATOR_KEY
            ),
            state(
                WmsAllocationReleaseStateValidator.STATE_END,
                "已完成",
                StateKind.END,
                true,
                40,
                WmsAllocationReleaseStateValidator.VALIDATOR_KEY
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

    private static List<DefinitionEventBo> lockEvents() {
        return List.of(
            event(
                WmsAllocationEvaluateAction.ACTION_KEY,
                "分配评估",
                WmsAllocationEvaluateAction.LOCAL_EFFECT_KEY,
                WmsAllocationLockStateValidator.STATE_START,
                WmsAllocationLockStateValidator.STATE_EVALUATED,
                10
            ),
            event(
                WmsAllocationReserveAction.ACTION_KEY,
                "分配储位锁位",
                WmsAllocationReserveAction.LOCAL_EFFECT_KEY,
                WmsAllocationLockStateValidator.STATE_EVALUATED,
                WmsAllocationLockStateValidator.STATE_RESERVED,
                20
            ),
            event(
                WmsAllocationVerifyAction.ACTION_KEY,
                "分配锁位核验",
                WmsAllocationVerifyAction.LOCAL_EFFECT_KEY,
                WmsAllocationLockStateValidator.STATE_RESERVED,
                WmsAllocationLockStateValidator.STATE_END,
                30
            )
        );
    }

    private static List<DefinitionEventBo> releaseEvents() {
        return List.of(
            event(
                WmsAllocationCheckReleaseAction.ACTION_KEY,
                "释放证据检查",
                WmsAllocationCheckReleaseAction.LOCAL_EFFECT_KEY,
                WmsAllocationReleaseStateValidator.STATE_START,
                WmsAllocationReleaseStateValidator.STATE_EVIDENCE_CHECKED,
                10
            ),
            event(
                WmsAllocationReleaseAction.ACTION_KEY,
                "释放分配锁",
                WmsAllocationReleaseAction.LOCAL_EFFECT_KEY,
                WmsAllocationReleaseStateValidator.STATE_EVIDENCE_CHECKED,
                WmsAllocationReleaseStateValidator.STATE_RELEASED,
                20
            ),
            event(
                WmsAllocationVerifyReleaseAction.ACTION_KEY,
                "释放终态核验",
                WmsAllocationVerifyReleaseAction.LOCAL_EFFECT_KEY,
                WmsAllocationReleaseStateValidator.STATE_RELEASED,
                WmsAllocationReleaseStateValidator.STATE_END,
                30
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

    private static List<DefinitionEdgeBo> supervisorEdges() {
        return List.of(edge(SupervisorRules.ACTION_DECISION, SupervisorRules.ACTION_ADMIT));
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
