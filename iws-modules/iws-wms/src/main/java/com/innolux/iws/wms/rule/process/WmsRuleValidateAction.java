/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 在同一计划事务内校验规则包、绑定、作用域与摘要；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.LocalActionInput;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import com.innolux.iws.wms.rule.domain.WmsRulePublicationValidation;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** VALIDATE_RULESET 计划动作。 */
public class WmsRuleValidateAction implements LocalAction, LocalActionInputProvider {

    private final WmsRulePublishService publishService;

    private final ProcessInstanceMapper instanceMapper;

    private final JsonMapper jsonMapper;

    private final WmsFrozenActionInputReader inputReader;

    public WmsRuleValidateAction(
        WmsRulePublishService publishService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        this.publishService = publishService;
        this.instanceMapper = instanceMapper;
        this.jsonMapper = jsonMapper;
        this.inputReader = inputReader;
    }

    @Override
    public LocalActionDescriptor describe() {
        return new LocalActionDescriptor(
            WmsRulePlanContract.VALIDATE_ACTION_KEY,
            "1",
            "规则包发布校验",
            "SAME_TX",
            true
        );
    }

    @Override
    public LocalActionInput provide(ActionContext context) {
        return inputReader.read(context, WmsRulePlanContract.VALIDATE_ACTION_KEY);
    }

    @Override
    public ActionResult execute(ActionContext context) {
        WmsRulePackagePublishCommand command = WmsRulePublishActionSupport.readCommand(
            context,
            "规则包发布校验",
            WmsRulePlanContract.VALIDATE_LOCAL_EFFECT_KEY,
            jsonMapper,
            instanceMapper
        );
        WmsRulePublicationValidation validation =
            publishService.validateWithinTransaction(command, inputReader.actor(context));
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", WmsRulePlanContract.STATE_VALIDATED);
        checkpoint.put("packageVersionId", validation.packageVersionId());
        checkpoint.put("scopeType", validation.scopeType());
        checkpoint.put("scopeId", validation.scopeId());
        checkpoint.put("canonicalDigest", validation.canonicalDigest());
        checkpoint.put("storageIds", validation.storageIds());
        checkpoint.put("previousPackageVersionId", validation.previousPackageVersionId());
        checkpoint.put("previousPointerRevision", validation.previousPointerRevision());
        return new ActionResult(
            EffectResult.SUCCEEDED,
            context.evidenceRef(),
            null,
            null,
            null,
            null,
            jsonMapper.writeValueAsString(checkpoint),
            "规则包发布校验完成"
        );
    }
}
