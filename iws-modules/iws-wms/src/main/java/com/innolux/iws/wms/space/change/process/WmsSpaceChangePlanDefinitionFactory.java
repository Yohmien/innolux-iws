/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 生成空间配置、退役业务计划及其监督计划草稿；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.process;

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
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;

import java.util.List;

/** 空间生命周期计划草稿工厂。 */
public final class WmsSpaceChangePlanDefinitionFactory {

    private static final String SYSTEM = "IWS";

    private WmsSpaceChangePlanDefinitionFactory() {
    }

    public static DefinitionDraftBo changeDraft(String planCode) {
        return businessDraft(
            planCode,
            WmsSpacePlanContract.CHANGE_PLAN_NAME,
            WmsSpaceChangeService.PLAN_TYPE_CHANGE,
            List.of(
                state("START", "待校验", StateKind.START, false, 10),
                state("VALIDATED", "校验通过", StateKind.NORMAL, false, 20),
                state("APPLIED", "配置已应用", StateKind.NORMAL, false, 30),
                state("END", "已完成", StateKind.END, true, 40)
            ),
            List.of(
                event(
                    WmsSpacePlanContract.VALIDATE_EVENT,
                    "空间变更校验",
                    WmsSpacePlanContract.VALIDATE_EFFECT,
                    "START",
                    "VALIDATED",
                    10
                ),
                event(
                    WmsSpacePlanContract.APPLY_EVENT,
                    "应用空间配置",
                    WmsSpacePlanContract.APPLY_EFFECT,
                    "VALIDATED",
                    "APPLIED",
                    20
                ),
                event(
                    WmsSpacePlanContract.VERIFY_EVENT,
                    "核验空间配置",
                    WmsSpacePlanContract.VERIFY_EFFECT,
                    "APPLIED",
                    "END",
                    30
                )
            ),
            List.of(
                edge(WmsSpacePlanContract.VALIDATE_EVENT, WmsSpacePlanContract.APPLY_EVENT),
                edge(WmsSpacePlanContract.APPLY_EVENT, WmsSpacePlanContract.VERIFY_EVENT)
            )
        );
    }

    public static DefinitionDraftBo retireDraft(String planCode) {
        return businessDraft(
            planCode,
            WmsSpacePlanContract.RETIRE_PLAN_NAME,
            WmsSpaceChangeService.PLAN_TYPE_RETIRE,
            List.of(
                state("START", "待检查", StateKind.START, false, 10),
                state("CHECKED", "退役检查通过", StateKind.NORMAL, false, 20),
                state("END", "已退役", StateKind.END, true, 30)
            ),
            List.of(
                event(
                    WmsSpacePlanContract.CHECK_RETIRE_EVENT,
                    "检查空间退役阻碍",
                    WmsSpacePlanContract.CHECK_RETIRE_EFFECT,
                    "START",
                    "CHECKED",
                    10
                ),
                event(
                    WmsSpacePlanContract.RETIRE_EVENT,
                    "退役空间对象",
                    WmsSpacePlanContract.RETIRE_EFFECT,
                    "CHECKED",
                    "END",
                    20
                )
            ),
            List.of(edge(
                WmsSpacePlanContract.CHECK_RETIRE_EVENT,
                WmsSpacePlanContract.RETIRE_EVENT
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
            WmsSpaceChangeService.SCHEDULE_CODE,
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
                    SupervisorRules.ACTION_DECISION,
                    "监督候选决策",
                    EventRole.DECISION,
                    "START",
                    "DECIDED",
                    10
                ),
                supervisorEvent(
                    SupervisorRules.ACTION_ADMIT,
                    "监督逐候选准入",
                    EventRole.LOCAL,
                    "DECIDED",
                    "END",
                    20
                )
            ),
            List.of(edge(
                SupervisorRules.ACTION_DECISION,
                SupervisorRules.ACTION_ADMIT
            )),
            List.of(new SystemPolicyBo(
                SYSTEM,
                PostingMode.AUTOMATIC.name(),
                null,
                null,
                "空间计划监督只调度本库任务"
            ))
        );
    }

    private static DefinitionDraftBo businessDraft(
        String planCode,
        String planName,
        String planType,
        List<DefinitionStateBo> states,
        List<DefinitionEventBo> events,
        List<DefinitionEdgeBo> edges
    ) {
        return new DefinitionDraftBo(
            planCode,
            planName,
            1,
            planType,
            WmsSpaceChangeService.BUSINESS_TYPE,
            WmsSpaceChangeService.SCHEDULE_CODE,
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
                "空间配置只写本库"
            ))
        );
    }

    private static DefinitionStateBo state(
        String code,
        String name,
        StateKind kind,
        boolean terminal,
        int sortOrder
    ) {
        return new DefinitionStateBo(
            code,
            name,
            kind.name(),
            WmsSpacePlanContract.STATE_VALIDATOR_KEY,
            WmsSpacePlanContract.STATE_VALIDATOR_VERSION,
            null,
            terminal,
            sortOrder
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
            code,
            name,
            kind.name(),
            WmsAllocationSupervisorStateValidator.VALIDATOR_KEY,
            WmsAllocationSupervisorStateValidator.VALIDATOR_VERSION,
            null,
            terminal,
            sortOrder
        );
    }

    private static DefinitionEventBo event(
        String code,
        String name,
        String localEffectKey,
        String source,
        String target,
        int priority
    ) {
        return event(code, name, EventRole.BUSINESS, localEffectKey, source, target, priority);
    }

    private static DefinitionEventBo supervisorEvent(
        String code,
        String name,
        EventRole role,
        String source,
        String target,
        int priority
    ) {
        return event(
            code,
            name,
            role,
            "WMS:" + code,
            source,
            target,
            priority
        );
    }

    private static DefinitionEventBo event(
        String code,
        String name,
        EventRole role,
        String localEffectKey,
        String source,
        String target,
        int priority
    ) {
        return new DefinitionEventBo(
            code,
            name,
            role.name(),
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
            source,
            target,
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
