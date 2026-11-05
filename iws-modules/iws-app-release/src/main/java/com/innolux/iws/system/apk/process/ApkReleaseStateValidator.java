/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布状态结构与成功证据校验；
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
 * 只允许 APK_START 到 APK_PUBLISHED 的成功收口。
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ApkReleaseStateValidator implements StateValidator {

    private final ApkReleasePublicationPort publicationPort;

    /**
     * 构造状态校验器。
     *
     * @param publicationPort 业务发布事实读取端口
     */
    public ApkReleaseStateValidator(ApkReleasePublicationPort publicationPort) {
        this.publicationPort = publicationPort;
    }

    /**
     * 描述状态校验器。
     *
     * @return 校验器描述
     */
    @Override
    public StateValidatorDescriptor describe() {
        return new StateValidatorDescriptor(ApkProcessContract.STATE_VALIDATOR_KEY,
            ApkProcessContract.VERSION, "APK 发布状态");
    }

    /**
     * 校验发布前后状态和输入摘要。
     *
     * @param context 状态上下文
     * @return 校验结果
     */
    @Override
    public ValidationResult validateStructural(StateContext context) {
        if (context == null) {
            return ValidationResult.fail("APK_STATE_CONTEXT", "状态上下文不能为空");
        }
        if (!ApkProcessContract.STATE_START.equals(context.sourceStateCode())
            || !ApkProcessContract.STATE_PUBLISHED.equals(context.targetStateCode())) {
            return ValidationResult.fail("APK_STATE_TRANSITION", "APK 发布只允许从起点到已发布状态");
        }
        return ValidationResult.pass();
    }

    /**
     * 校验本地动作已经产生成功结果。
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
        if (context.inputHash() == null || context.inputHash().isBlank()) {
            return ValidationResult.fail("APK_INPUT_HASH", "APK 发布缺少输入摘要");
        }
        if (!"SUCCEEDED".equals(context.evidenceSummary())) {
            return ValidationResult.fail("APK_PUBLICATION_EVIDENCE", "APK 发布缺少成功证据");
        }
        var found = publicationPort.findByPlanInstanceId(context.instanceId());
        if (found == null || found.isEmpty()) {
            return ValidationResult.fail("APK_PUBLICATION_FACT_MISSING", "APK 发布缺少业务事实");
        }
        ApkPublicationFact fact = found.get();
        if (!fact.published() || fact.releaseId() <= 0 || fact.planInstanceId() != context.instanceId()) {
            return ValidationResult.fail("APK_PUBLICATION_FACT_INVALID", "APK 业务发布事实未发布或实例不一致");
        }
        return ValidationResult.pass();
    }
}
