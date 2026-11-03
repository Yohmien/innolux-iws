/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 使用计划定义服务幂等准备空间配置、退役及监督计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.process;

import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** 空间计划定义 provision。 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class WmsSpacePlanProvisioner implements ApplicationRunner {

    private final ObjectProvider<PlanDefinitionService> definitionProvider;

    public WmsSpacePlanProvisioner(ObjectProvider<PlanDefinitionService> definitionProvider) {
        this.definitionProvider = definitionProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        PlanDefinitionService definitions = definitionProvider.getIfAvailable();
        if (definitions == null) {
            throw new IllegalStateException("计划事件已启用但计划定义服务不可用");
        }
        provision(definitions, WmsSpaceChangePlanDefinitionFactory.changeDraft(
            WmsSpacePlanContract.CHANGE_PLAN_CODE));
        provision(definitions, WmsSpaceChangePlanDefinitionFactory.supervisorDraft(
            WmsSpacePlanContract.CHANGE_SUPERVISOR_PLAN_CODE,
            WmsSpaceChangeService.PLAN_TYPE_CHANGE,
            "空间配置变更监督"
        ));
        provision(definitions, WmsSpaceChangePlanDefinitionFactory.retireDraft(
            WmsSpacePlanContract.RETIRE_PLAN_CODE));
        provision(definitions, WmsSpaceChangePlanDefinitionFactory.supervisorDraft(
            WmsSpacePlanContract.RETIRE_SUPERVISOR_PLAN_CODE,
            WmsSpaceChangeService.PLAN_TYPE_RETIRE,
            "空间退役监督"
        ));
    }

    private void provision(PlanDefinitionService definitions, DefinitionDraftBo draft) {
        List<? extends com.innolux.iws.process.api.DefinitionSummaryVo> active =
            definitions.page(
                draft.planCode(), "ACTIVE", draft.planRole(), new PageQuery(10, 1)
            ).getRows().stream()
                .filter(item -> draft.planCode().equals(item.planCode()))
                .filter(item -> draft.planRole().equals(item.planRole()))
                .toList();
        if (active.size() == 1) {
            return;
        }
        if (active.size() > 1) {
            throw new IllegalStateException("空间计划存在多个生效版本: " + draft.planCode());
        }
        var created = definitions.createDraft(
            draft,
            "wms-space-plan-provision:create:" + draft.planCode(),
            "空间计划首次 provision"
        );
        long versionId = Long.parseLong(created.summary().versionId());
        var validation = definitions.validate(versionId);
        if (!validation.valid()) {
            throw new IllegalStateException(
                "空间计划校验失败: " + String.join("; ", validation.errors())
            );
        }
        var published = definitions.publish(
            versionId,
            Long.parseLong(created.summary().revision()),
            "wms-space-plan-provision:publish:" + draft.planCode(),
            "空间计划首次 provision"
        );
        if (!published.valid()) {
            throw new IllegalStateException(
                "空间计划发布失败: " + String.join("; ", published.errors())
            );
        }
    }
}
