/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 生成固定批次、父收敛及其监督计划草稿；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

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

/** 逻辑合并计划草稿工厂。 */
public final class WmsLogicalMergePlanDefinitionFactory {

    private static final String SYSTEM = "IWS";

    private WmsLogicalMergePlanDefinitionFactory() {
    }

    public static DefinitionDraftBo batchDraft(String planCode) {
        return businessDraft(
            planCode,
            "逻辑合并固定批次",
            WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
            WmsLogicalMergePlanContract.BATCH_STATE_VALIDATOR_KEY,
            List.of(
                state("START", "待冻结", StateKind.START, false, 10,
                    WmsLogicalMergePlanContract.BATCH_STATE_VALIDATOR_KEY),
                state("CAPTURED", "批次已冻结", StateKind.NORMAL, false, 20,
                    WmsLogicalMergePlanContract.BATCH_STATE_VALIDATOR_KEY),
                state("CHECKED", "兼容性已检查", StateKind.NORMAL, false, 30,
                    WmsLogicalMergePlanContract.BATCH_STATE_VALIDATOR_KEY),
                state("APPLIED", "归属已应用", StateKind.NORMAL, false, 40,
                    WmsLogicalMergePlanContract.BATCH_STATE_VALIDATOR_KEY),
                state("END", "批次已关闭", StateKind.END, true, 50,
                    WmsLogicalMergePlanContract.BATCH_STATE_VALIDATOR_KEY)
            ),
            List.of(
                event(
                    WmsLogicalMergePlanContract.CAPTURE_BATCH,
                    "冻结合并批次",
                    WmsLogicalMergePlanContract.CAPTURE_BATCH_EFFECT,
                    "START", "CAPTURED", 10
                ),
                event(
                    WmsLogicalMergePlanContract.CHECK_BATCH,
                    "检查合并兼容性",
                    WmsLogicalMergePlanContract.CHECK_BATCH_EFFECT,
                    "CAPTURED", "CHECKED", 20
                ),
                event(
                    WmsLogicalMergePlanContract.APPLY_BATCH,
                    "应用逻辑归属批次",
                    WmsLogicalMergePlanContract.APPLY_BATCH_EFFECT,
                    "CHECKED", "APPLIED", 30
                ),
                event(
                    WmsLogicalMergePlanContract.CLOSE_BATCH,
                    "关闭固定批次",
                    WmsLogicalMergePlanContract.CLOSE_BATCH_EFFECT,
                    "APPLIED", "END", 40
                )
            ),
            List.of(
                edge(WmsLogicalMergePlanContract.CAPTURE_BATCH,
                    WmsLogicalMergePlanContract.CHECK_BATCH),
                edge(WmsLogicalMergePlanContract.CHECK_BATCH,
                    WmsLogicalMergePlanContract.APPLY_BATCH),
                edge(WmsLogicalMergePlanContract.APPLY_BATCH,
                    WmsLogicalMergePlanContract.CLOSE_BATCH)
            )
        );
    }

    public static DefinitionDraftBo closeDraft(String planCode) {
        return businessDraft(
            planCode,
            "逻辑合并收敛",
            WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE,
            WmsLogicalMergePlanContract.CLOSE_STATE_VALIDATOR_KEY,
            List.of(
                state("START", "待确认父范围", StateKind.START, false, 10,
                    WmsLogicalMergePlanContract.CLOSE_STATE_VALIDATOR_KEY),
                state("CAPTURED", "父范围已确认", StateKind.NORMAL, false, 20,
                    WmsLogicalMergePlanContract.CLOSE_STATE_VALIDATOR_KEY),
                state("END", "合并已收敛", StateKind.END, true, 30,
                    WmsLogicalMergePlanContract.CLOSE_STATE_VALIDATOR_KEY)
            ),
            List.of(
                event(
                    WmsLogicalMergePlanContract.CAPTURE_PARENT,
                    "确认合并父范围",
                    WmsLogicalMergePlanContract.CAPTURE_PARENT_EFFECT,
                    "START", "CAPTURED", 10
                ),
                event(
                    WmsLogicalMergePlanContract.CLOSE_PARENT,
                    "收敛并退役来源",
                    WmsLogicalMergePlanContract.CLOSE_PARENT_EFFECT,
                    "CAPTURED", "END", 20
                )
            ),
            List.of(edge(
                WmsLogicalMergePlanContract.CAPTURE_PARENT,
                WmsLogicalMergePlanContract.CLOSE_PARENT
            ))
        );
    }

    public static DefinitionDraftBo supervisorDraft(
        String planCode,
        String planType,
        String planName
    ) {
        return new DefinitionDraftBo(
            planCode,
            planName,
            1,
            planType,
            null,
            WmsLogicalMergePlanContract.SCHEDULE_CODE,
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
            List.of(
                supervisorState("START", "待决策", StateKind.START, false, 10),
                supervisorState("DECIDED", "已决策", StateKind.NORMAL, false, 20),
                supervisorState("END", "本轮已准入", StateKind.END, true, 30)
            ),
            List.of(
                supervisorEvent(
                    SupervisorRules.ACTION_DECISION, "监督候选决策",
                    EventRole.DECISION, "START", "DECIDED", 10),
                supervisorEvent(
                    SupervisorRules.ACTION_ADMIT, "监督逐候选准入",
                    EventRole.LOCAL, "DECIDED", "END", 20)
            ),
            List.of(edge(
                SupervisorRules.ACTION_DECISION,
                SupervisorRules.ACTION_ADMIT
            )),
            List.of(new SystemPolicyBo(
                SYSTEM, PostingMode.AUTOMATIC.name(), null, null,
                "逻辑合并监督只调度本库任务"))
        );
    }

    private static DefinitionDraftBo businessDraft(
        String planCode,
        String planName,
        String planType,
        String validatorKey,
        List<DefinitionStateBo> states,
        List<DefinitionEventBo> events,
        List<DefinitionEdgeBo> edges
    ) {
        return new DefinitionDraftBo(
            planCode,
            planName,
            1,
            planType,
            WmsLogicalMergePlanContract.BUSINESS_TYPE,
            WmsLogicalMergePlanContract.SCHEDULE_CODE,
            SYSTEM,
            PlanRole.BUSINESS.name(),
            null,
            null,
            null,
            30_000L,
            CompletionPolicy.ALL_SELECTED.name(),
            null,
            16,
            5_000L,
            16,
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
            List.of(new SystemPolicyBo(
                SYSTEM,
                PostingMode.AUTOMATIC.name(),
                null,
                null,
                "逻辑合并只写本库"
            ))
        );
    }

    private static DefinitionStateBo state(
        String code,
        String name,
        StateKind kind,
        boolean terminal,
        int sortOrder,
        String validatorKey
    ) {
        return new DefinitionStateBo(
            code, name, kind.name(), validatorKey,
            WmsLogicalMergePlanContract.VALIDATOR_VERSION,
            null, terminal, sortOrder
        );
    }

    private static DefinitionStateBo supervisorState(
        String code,
        String name,
        StateKind kind,
        boolean terminal,
        int sortOrder
    ) {
        return new DefinitionStateBo(
            code, name, kind.name(),
            WmsAllocationSupervisorStateValidator.VALIDATOR_KEY,
            WmsAllocationSupervisorStateValidator.VALIDATOR_VERSION,
            null, terminal, sortOrder
        );
    }

    private static DefinitionEventBo event(
        String code,
        String name,
        String effect,
        String source,
        String target,
        int priority
    ) {
        return event(code, name, EventRole.BUSINESS, effect, source, target, priority);
    }

    private static DefinitionEventBo supervisorEvent(
        String code,
        String name,
        EventRole role,
        String source,
        String target,
        int priority
    ) {
        return event(code, name, role, "WMS:" + code, source, target, priority);
    }

    private static DefinitionEventBo event(
        String code,
        String name,
        EventRole role,
        String effect,
        String source,
        String target,
        int priority
    ) {
        return new DefinitionEventBo(
            code, name, role.name(), EventDirection.INTERNAL.name(),
            WmsInventoryProcessConfiguration.EVENT_TYPE_KEY,
            WmsInventoryProcessConfiguration.EVENT_TYPE_VERSION,
            null, null, null, code, "1", null,
            effect, SYSTEM, SYSTEM, null, null, null,
            source, target, true, null, null, null,
            priority, null, null, null, null
        );
    }

    private static DefinitionEdgeBo edge(String predecessor, String successor) {
        return new DefinitionEdgeBo(
            predecessor, successor, "DEFAULT", BranchKind.SEQUENTIAL.name(),
            null, null, false, null, 0
        );
    }
}
