/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 使用计划定义服务幂等准备规则发布业务和监督定义；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.DefinitionSummaryVo;
import com.innolux.iws.process.service.PlanDefinitionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则发布计划定义 provision。
 *
 * <p>目录与挂载关系由正式 SQL seed 维护；本组件只使用平台定义服务创建并发布
 * 代码内冻结的规则计划，不直接写计划运行表。相同命令键和相同定义内容可安全重放。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class WmsRulePlanProvisioner implements ApplicationRunner {

    private final ObjectProvider<PlanDefinitionService> definitionProvider;

    public WmsRulePlanProvisioner(ObjectProvider<PlanDefinitionService> definitionProvider) {
        this.definitionProvider = definitionProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        PlanDefinitionService definitions = definitionProvider.getIfAvailable();
        if (definitions == null) {
            throw new IllegalStateException("计划事件已启用但计划定义服务不可用");
        }
        provision(
            definitions,
            WmsRulePlanDefinitionFactory.publishDraft(
                WmsRulePlanContract.PLAN_CODE,
                WmsRulePlanContract.BUSINESS_CODE
            )
        );
        provision(
            definitions,
            WmsRulePlanDefinitionFactory.supervisorDraft(
                WmsRulePlanContract.SUPERVISOR_PLAN_CODE,
                WmsRulePlanContract.BUSINESS_CODE
            )
        );
    }

    private void provision(PlanDefinitionService definitions, DefinitionDraftBo draft) {
        List<DefinitionSummaryVo> active = definitions.page(
            draft.planCode(),
            "ACTIVE",
            draft.planRole(),
            new com.innolux.iws.common.mybatis.core.page.PageQuery(10, 1)
        ).getRows().stream()
            .filter(item -> draft.planCode().equals(item.planCode()))
            .filter(item -> draft.planRole().equals(item.planRole()))
            .toList();
        if (active.size() == 1) {
            return;
        }
        if (active.size() > 1) {
            throw new IllegalStateException("规则发布计划存在多个生效版本: " + draft.planCode());
        }
        var created = definitions.createDraft(
            draft,
            "wms-plan-provision:create:" + draft.planCode(),
            "规则发布计划首次 provision"
        );
        long versionId = Long.parseLong(created.summary().versionId());
        var validation = definitions.validate(versionId);
        if (!validation.valid()) {
            throw new IllegalStateException(
                "规则发布计划校验失败: " + String.join("; ", validation.errors())
            );
        }
        var published = definitions.publish(
            versionId,
            Long.parseLong(created.summary().revision()),
            "wms-plan-provision:publish:" + draft.planCode(),
            "规则发布计划首次 provision"
        );
        if (!published.valid()) {
            throw new IllegalStateException(
                "规则发布计划发布失败: " + String.join("; ", published.errors())
            );
        }
    }
}
