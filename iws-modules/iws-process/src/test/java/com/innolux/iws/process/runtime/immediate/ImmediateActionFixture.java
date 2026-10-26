/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划测试本地动作与状态校验器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.immediate;

import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.CallContractDescriptor;
import com.innolux.iws.process.runtime.SingleActionOperation;
import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可切换成败与延迟的即时测试动作。
 */
public final class ImmediateActionFixture implements LocalAction {

    static final String LOCAL_KEY = "immediate-local";

    static final String VALIDATOR_KEY = "immediate-validator";

    static final String INBOUND_CONTRACT = "immediate-inbound";

    private final AtomicReference<Behavior> behavior = new AtomicReference<>(Behavior.SUCCEEDED);

    private final AtomicInteger invocations = new AtomicInteger();

    private volatile long sleepMillis;

    /**
     * 重置测试动作。
     *
     * @param next 行为
     * @param sleep 延迟毫秒
     */
    public void reset(Behavior next, long sleep) {
        behavior.set(next);
        sleepMillis = sleep;
        invocations.set(0);
    }

    /**
     * 调用次数。
     *
     * @return 次数
     */
    public int invocations() {
        return invocations.get();
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(LOCAL_KEY, "1", "即时测试动作", "SAME_TX", true);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        invocations.incrementAndGet();
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return ActionResult.unknown("测试动作被中断");
            }
        }
        return switch (behavior.get()) {
            case SUCCEEDED, SLOW_SUCCEEDED -> ActionResult.succeeded("immediate-fixture");
            case FAILED -> new ActionResult(EffectResult.FAILED, null, null, null, null, null, null,
                "即时测试确定失败");
            case UNKNOWN -> ActionResult.unknown("即时测试结果未知");
        };
    }

    /**
     * 测试状态校验器。
     *
     * @return 校验器
     */
    public StateValidator validator() {
        return new FixtureValidator();
    }

    /**
     * 入站调用测试操作。
     *
     * @return 单动作操作
     */
    public SingleActionOperation operation() {
        return new FixtureOperation();
    }

    private static final class FixtureOperation implements SingleActionOperation {

        @Override
        public java.util.List<CallContractDescriptor> describe() {
            return java.util.List.of(new CallContractDescriptor(INBOUND_CONTRACT, "1", "即时入站",
                OperationKind.INBOUND_TRIGGER, EventDirection.INBOUND, CallPurpose.TRIGGER_EVENT,
                CompletionContract.BUSINESS_COMPLETION, "POST", "/immediate/inbound", null));
        }

        @Override
        public ActionResult execute(ActionContext context) {
            return ActionResult.succeeded("immediate-inbound-fixture");
        }
    }

    private static final class FixtureValidator implements StateValidator {

        @Override
        public StateValidatorDescriptor describe() {
            return new StateValidatorDescriptor(VALIDATOR_KEY, "1", "即时测试校验器");
        }

        @Override
        public ValidationResult validateStructural(StateContext context) {
            return ValidationResult.pass();
        }

        @Override
        public ValidationResult validateBusiness(StateContext context) {
            return ValidationResult.pass();
        }
    }

    /**
     * 测试动作行为。
     */
    public enum Behavior {
        SUCCEEDED,
        FAILED,
        UNKNOWN,
        SLOW_SUCCEEDED
    }
}
