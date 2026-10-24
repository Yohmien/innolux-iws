/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process HTTP fixture 仅本地能力注册；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture.http;

import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.EventTypeDescriptor;
import com.innolux.iws.process.runtime.EventTypeProvider;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * 只注册 INTERNAL 事件类型、状态校验器和本地动作。
 *
 * <p>fixture 不注册调用合同或 HTTP 动作，配置页演练不会产生外部调用。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ProcessHttpFixtureCapabilities {

    static final String EVENT_TYPE_KEY = "fixture-internal";
    static final String CAPABILITY_VERSION = "1";
    static final String VALIDATOR_KEY = "fixture-state";
    static final String LOCAL_ACTION_KEY = "fixture-local";

    /**
     * 仅声明 INTERNAL 方向事件类型。
     *
     * @return 事件类型提供者
     */
    @Bean
    EventTypeProvider processHttpFixtureEventTypeProvider() {
        return () -> List.of(new EventTypeDescriptor(EVENT_TYPE_KEY, CAPABILITY_VERSION,
            "隔离本地事件", Set.of(EventDirection.INTERNAL)));
    }

    /**
     * 结构校验直接通过，只用于配置页演练。
     *
     * @return 状态校验器
     */
    @Bean
    StateValidator processHttpFixtureStateValidator() {
        return new StateValidator() {
            @Override
            public StateValidatorDescriptor describe() {
                return new StateValidatorDescriptor(VALIDATOR_KEY, CAPABILITY_VERSION, "隔离状态校验");
            }

            @Override
            public ValidationResult validateStructural(StateContext context) {
                return ValidationResult.pass();
            }

            @Override
            public ValidationResult validateBusiness(StateContext context) {
                return ValidationResult.pass();
            }
        };
    }

    /**
     * 本地动作只返回固定证据引用，不访问网络。
     *
     * @return 本地动作
     */
    @Bean
    LocalAction processHttpFixtureLocalAction() {
        return new LocalAction() {
            @Override
            public LocalActionDescriptor describe() {
                return new LocalActionDescriptor(LOCAL_ACTION_KEY, CAPABILITY_VERSION,
                    "隔离本地动作", "SAME_TX", true);
            }

            @Override
            public ActionResult execute(ActionContext context) {
                return ActionResult.succeeded("fixture://event/" + context.eventId());
            }
        };
    }
}
