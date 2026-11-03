/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : 规则、物料、业务任务、追踪、定位和导入预览只读服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.BusinessTaskQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.BusinessTaskView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.CapabilitiesView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.ImportPreviewView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.LocateQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.LocateView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialDetailEnvelope;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialSummaryView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleDefinitionDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleDefinitionQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleDefinitionView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePreviewCommand;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePreviewView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceSummaryView;
import org.springframework.web.multipart.MultipartFile;

/**
 * P7 只读查询服务。
 */
public interface WmsInventoryReadService {

    CapabilitiesView capabilities();

    PageResult<MaterialSummaryView> queryMaterialSummaries(MaterialQuery query);

    PageResult<MaterialDetailView> queryMaterialDetails(MaterialQuery query);

    MaterialDetailEnvelope getMaterialDetail(long inventoryId);

    PageResult<TraceSummaryView> queryTraces(TraceQuery query);

    TraceDetailView getTrace(long traceId);

    byte[] exportTraces(TraceQuery query);

    PageResult<BusinessTaskView> queryBusinessTasks(BusinessTaskQuery query);

    PageResult<RuleDefinitionView> queryRuleDefinitions(RuleDefinitionQuery query);

    RuleDefinitionDetailView getRuleDefinition(long definitionId);

    PageResult<RulePackageView> queryRulePackages(RulePackageQuery query);

    RulePackageDetailView getRulePackage(long packageVersionId);

    RulePreviewView previewRule(RulePreviewCommand command);

    LocateView locate(LocateQuery query);

    ImportPreviewView previewMaterialImport(MultipartFile file);
}
