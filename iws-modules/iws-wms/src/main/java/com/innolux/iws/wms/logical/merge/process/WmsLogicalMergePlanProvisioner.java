/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 使用计划定义服务幂等准备合并批次、父收敛及监督计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.service.PlanDefinitionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** 逻辑合并计划定义 provision。 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class WmsLogicalMergePlanProvisioner implements ApplicationRunner {

    private final ObjectProvider<PlanDefinitionService> definitionProvider;

    public WmsLogicalMergePlanProvisioner(
        ObjectProvider<PlanDefinitionService> definitionProvider
    ) {
        this.definitionProvider = definitionProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        PlanDefinitionService definitions = definitionProvider.getIfAvailable();
        if (definitions == null) {
            throw new IllegalStateException("计划事件已启用但计划定义服务不可用");
        }
        provision(definitions, WmsLogicalMergePlanDefinitionFactory.batchDraft(
            WmsLogicalMergePlanContract.BATCH_PLAN_CODE));
        provision(definitions, WmsLogicalMergePlanDefinitionFactory.supervisorDraft(
            WmsLogicalMergePlanContract.BATCH_SUPERVISOR_PLAN_CODE,
            WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
            "逻辑合并批次监督"
        ));
        provision(definitions, WmsLogicalMergePlanDefinitionFactory.closeDraft(
            WmsLogicalMergePlanContract.CLOSE_PLAN_CODE));
        provision(definitions, WmsLogicalMergePlanDefinitionFactory.supervisorDraft(
            WmsLogicalMergePlanContract.CLOSE_SUPERVISOR_PLAN_CODE,
            WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE,
            "逻辑合并收敛监督"
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
            throw new IllegalStateException("逻辑合并计划存在多个生效版本: " + draft.planCode());
        }
        var created = definitions.createDraft(
            draft,
            "wms-logical-merge-plan-provision:create:" + draft.planCode(),
            "逻辑合并计划首次 provision"
        );
        long versionId = Long.parseLong(created.summary().versionId());
        var validation = definitions.validate(versionId);
        if (!validation.valid()) {
            throw new IllegalStateException(
                "逻辑合并计划校验失败: " + String.join("; ", validation.errors())
            );
        }
        var published = definitions.publish(
            versionId,
            Long.parseLong(created.summary().revision()),
            "wms-logical-merge-plan-provision:publish:" + draft.planCode(),
            "逻辑合并计划首次 provision"
        );
        if (!published.valid()) {
            throw new IllegalStateException(
                "逻辑合并计划发布失败: " + String.join("; ", published.errors())
            );
        }
    }
}
