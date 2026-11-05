/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 即时监督计划状态校验；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.StateValidatorDescriptor;
import com.innolux.iws.process.runtime.ValidationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 校验 APK 即时监督定义的最小起点到终点结构。
 *
 * <p>监督事件在 IMMEDIATE 准入中只提供冻结计划壳，不执行候选决策；
 * 结构校验不依赖业务发布事实。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ApkSupervisorStateValidator implements StateValidator {

    /**
     * 描述监督状态校验器。
     *
     * @return 校验器描述
     */
    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(ApkProcessContract.SUPERVISOR_STATE_VALIDATOR_KEY,
            ApkProcessContract.VERSION, ApkProcessContract.SUPERVISOR_PLAN_NAME);
    }

    /**
     * 校验监督起点到终点。
     *
     * @param context 状态上下文
     * @return 校验结果
     */
    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null) {
            return ValidationResult.fail("APK_SUPERVISOR_CONTEXT", "监督状态上下文不能为空");
        }
        if (!ApkProcessContract.SUPERVISOR_STATE_START.equals(context.sourceStateCode())
            || !ApkProcessContract.SUPERVISOR_STATE_END.equals(context.targetStateCode())) {
            return ValidationResult.fail("APK_SUPERVISOR_TRANSITION", "监督只允许从起点到终点");
        }
        return ValidationResult.pass();
    }

    /**
     * 校验监督动作已产生成功结果。
     *
     * @param context 状态上下文
     * @return 校验结果
     */
    @Override
    public ValidationResult validateBusiness(StateContext context) {
        ValidationResult structural = validateStructural(context);
        if (!structural.passed()) {
            return structural;
        }
        if (context.inputHash() == null || context.inputHash().isBlank()
            || !"SUCCEEDED".equals(context.evidenceSummary())) {
            return ValidationResult.fail("APK_SUPERVISOR_EVIDENCE", "监督动作缺少输入或成功证据");
        }
        return ValidationResult.pass();
    }
}
