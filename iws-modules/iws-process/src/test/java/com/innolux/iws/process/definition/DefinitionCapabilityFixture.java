/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 仅测试使用的注册能力 fixture；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.definition;

import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.BranchCondition;
import com.innolux.iws.process.runtime.BranchConditionDescriptor;
import com.innolux.iws.process.runtime.CallContractDescriptor;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EventTypeDescriptor;
import com.innolux.iws.process.runtime.EventTypeProvider;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.SingleActionOperation;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划定义隔离测试的注册能力。
 */
final class DefinitionCapabilityFixture {

    static final String VALIDATOR = "fixture-validator";

    static final String CONTRACT_IN = "in-trigger";

    static final String CONTRACT_RESULT = "in-result";

    static final String CONTRACT_POST = "out-post";

    static final String CONTRACT_CONTROL = "mes-control";

    static final String CONTRACT_QUERY = "out-query";

    static final String LOCAL_ACTION = "fixture-local";

    private DefinitionCapabilityFixture() {
    }

    static CapabilityRegistry registry() {
        EventTypeProvider types = () -> List.of(
            new EventTypeDescriptor("inbound-type", "1", "入站", Set.of(EventDirection.INBOUND)),
            new EventTypeDescriptor("outbound-type", "1", "出站", Set.of(EventDirection.OUTBOUND)),
            new EventTypeDescriptor("internal-type", "1", "本地", Set.of(EventDirection.INTERNAL)));
        SingleActionOperation operation = new SingleActionOperation() {
            @Override
            public List<CallContractDescriptor> describe() {
                return List.of(
                    new CallContractDescriptor(CONTRACT_IN, "1", "入站触发", OperationKind.INBOUND_TRIGGER,
                        EventDirection.INBOUND, CallPurpose.TRIGGER_EVENT, CompletionContract.BUSINESS_COMPLETION,
                        "POST", "/trigger", null, List.of("SAP")),
                    new CallContractDescriptor(CONTRACT_RESULT, "1", "结果回调", OperationKind.RESULT_CALLBACK,
                        EventDirection.INBOUND, CallPurpose.RESULT_CALLBACK, CompletionContract.BUSINESS_COMPLETION,
                        "POST", "/result", null),
                    new CallContractDescriptor(CONTRACT_POST, "1", "外部抛账", OperationKind.EXTERNAL_POSTING,
                        EventDirection.OUTBOUND, CallPurpose.EXECUTE_ACTION, CompletionContract.ACCEPTANCE,
                        "POST", "/post", "R-MID"),
                    new CallContractDescriptor(CONTRACT_CONTROL, "1", "控制调用", OperationKind.CONTROL,
                        EventDirection.OUTBOUND, CallPurpose.CONTROL, CompletionContract.BUSINESS_COMPLETION,
                        "POST", "/control", null),
                    new CallContractDescriptor(CONTRACT_QUERY, "1", "恢复查询", OperationKind.QUERY,
                        EventDirection.OUTBOUND, CallPurpose.QUERY, CompletionContract.BUSINESS_COMPLETION,
                        "GET", "/query", null));
            }

            @Override
            public ActionResult execute(com.innolux.iws.process.runtime.ActionContext context) {
                return ActionResult.succeeded("fixture");
            }
        };
        LocalAction localAction = new LocalAction() {
            @Override
            public LocalActionDescriptor describe() {
                return new LocalActionDescriptor(LOCAL_ACTION, "1", "本地动作", "SAME_TX", true);
            }

            @Override
            public ActionResult execute(com.innolux.iws.process.runtime.ActionContext context) {
                return ActionResult.succeeded("fixture");
            }
        };
        StateValidator validator = new StateValidator() {
            @Override
            public StateValidatorDescriptor describe() {
                return new StateValidatorDescriptor(VALIDATOR, "1", "结构校验");
            }

            @Override
            public ValidationResult validateStructural(com.innolux.iws.process.runtime.StateContext context) {
                return ValidationResult.pass();
            }

            @Override
            public ValidationResult validateBusiness(com.innolux.iws.process.runtime.StateContext context) {
                return ValidationResult.pass();
            }
        };
        BranchCondition condition = new BranchCondition() {
            @Override
            public BranchConditionDescriptor describe() {
                return new BranchConditionDescriptor("fixture-cond", "1", "条件");
            }

            @Override
            public boolean matches(Map<String, String> facts) {
                return true;
            }
        };
        return new CapabilityRegistry(List.of(types), List.of(operation), List.of(localAction),
            List.of(validator), List.of(condition));
    }
}
