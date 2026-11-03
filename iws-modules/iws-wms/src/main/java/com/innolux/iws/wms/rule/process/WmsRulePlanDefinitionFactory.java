/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 生成规则包校验、激活两个持久事件的计划草稿；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

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
import com.innolux.iws.wms.allocation.process.WmsInventoryProcessConfiguration;
import com.innolux.iws.process.runtime.SupervisorRules;

import java.util.List;

/** 规则发布计划官方草稿工厂。 */
public final class WmsRulePlanDefinitionFactory {

    private static final String SYSTEM = "IWS";

    private WmsRulePlanDefinitionFactory() {
    }

    /**
     * 构造规则包发布计划草稿。
     *
     * @param planCode 计划编码
     * @param businessScheduleCode 业务调度编码
     * @return 草稿
     */
    public static DefinitionDraftBo publishDraft(
        String planCode,
        String businessScheduleCode
    ) {
        return new DefinitionDraftBo(
            planCode,
            WmsRulePlanContract.PLAN_NAME,
            1,
            WmsRulePlanContract.PLAN_TYPE,
            WmsRulePlanContract.BUSINESS_TYPE,
            businessScheduleCode,
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
            WmsRulePlanContract.PLAN_NAME,
            0L,
            null,
            states(),
            events(),
            List.of(edge(
                WmsRulePlanContract.VALIDATE_ACTION_KEY,
                WmsRulePlanContract.ACTIVATE_ACTION_KEY
            )),
            List.of(new SystemPolicyBo(
                SYSTEM,
                PostingMode.AUTOMATIC.name(),
                null,
                null,
                "规则包发布只写本库"
            ))
        );
    }

    /**
     * 构造规则发布监督计划草稿。
     *
     * @param planCode 计划编码
     * @param businessScheduleCode 业务调度编码
     * @return 草稿
     */
    public static DefinitionDraftBo supervisorDraft(
        String planCode,
        String businessScheduleCode
    ) {
        return new DefinitionDraftBo(
            planCode,
            WmsRulePlanContract.SUPERVISOR_PLAN_NAME,
            1,
            WmsRulePlanContract.PLAN_TYPE,
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
            WmsRulePlanContract.SUPERVISOR_PLAN_NAME,
            0L,
            null,
            supervisorStates(),
            supervisorEvents(),
            List.of(edge(
                SupervisorRules.ACTION_DECISION,
                SupervisorRules.ACTION_ADMIT
            )),
            List.of(new SystemPolicyBo(
                SYSTEM,
                PostingMode.AUTOMATIC.name(),
                null,
                null,
                "规则发布监督只调度本库计划"
            ))
        );
    }

    private static List<DefinitionStateBo> states() {
        return List.of(
            state(WmsRulePlanContract.STATE_START, "待校验", StateKind.START, false, 10),
            state(
                WmsRulePlanContract.STATE_VALIDATED,
                "校验通过",
                StateKind.NORMAL,
                false,
                20
            ),
            state(
                WmsRulePlanContract.STATE_PUBLISHED,
                "已发布",
                StateKind.END,
                true,
                30
            )
        );
    }

    private static List<DefinitionEventBo> events() {
        return List.of(
            event(
                WmsRulePlanContract.VALIDATE_ACTION_KEY,
                "规则包发布校验",
                WmsRulePlanContract.VALIDATE_LOCAL_EFFECT_KEY,
                WmsRulePlanContract.STATE_START,
                WmsRulePlanContract.STATE_VALIDATED,
                10
            ),
            event(
                WmsRulePlanContract.ACTIVATE_ACTION_KEY,
                "规则包原子激活",
                WmsRulePlanContract.ACTIVATE_LOCAL_EFFECT_KEY,
                WmsRulePlanContract.STATE_VALIDATED,
                WmsRulePlanContract.STATE_PUBLISHED,
                20
            )
        );
    }

    private static List<DefinitionStateBo> supervisorStates() {
        return List.of(
            stateWithValidator(
                "START",
                "待决策",
                StateKind.START,
                false,
                10,
                WmsRulePlanContract.SUPERVISOR_STATE_VALIDATOR_KEY
            ),
            stateWithValidator(
                "DECIDED",
                "已决策",
                StateKind.NORMAL,
                false,
                20,
                WmsRulePlanContract.SUPERVISOR_STATE_VALIDATOR_KEY
            ),
            stateWithValidator(
                "END",
                "本轮已准入",
                StateKind.END,
                true,
                30,
                WmsRulePlanContract.SUPERVISOR_STATE_VALIDATOR_KEY
            )
        );
    }

    private static List<DefinitionEventBo> supervisorEvents() {
        return List.of(
            supervisorEvent(
                SupervisorRules.ACTION_DECISION,
                "监督候选决策",
                EventRole.DECISION,
                "WMS:" + SupervisorRules.ACTION_DECISION,
                "START",
                "DECIDED",
                10
            ),
            supervisorEvent(
                SupervisorRules.ACTION_ADMIT,
                "监督逐候选准入",
                EventRole.LOCAL,
                "WMS:" + SupervisorRules.ACTION_ADMIT,
                "DECIDED",
                "END",
                20
            )
        );
    }

    private static DefinitionStateBo state(
        String code,
        String displayName,
        StateKind kind,
        boolean terminal,
        int sortOrder
    ) {
        return new DefinitionStateBo(
            code,
            displayName,
            kind.name(),
            WmsRulePlanContract.STATE_VALIDATOR_KEY,
            WmsRulePlanContract.STATE_VALIDATOR_VERSION,
            null,
            terminal,
            sortOrder
        );
    }

    private static DefinitionStateBo stateWithValidator(
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
            WmsRulePlanContract.SUPERVISOR_STATE_VALIDATOR_VERSION,
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
        return new DefinitionEventBo(
            code,
            displayName,
            EventRole.BUSINESS.name(),
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

    private static DefinitionEventBo supervisorEvent(
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
