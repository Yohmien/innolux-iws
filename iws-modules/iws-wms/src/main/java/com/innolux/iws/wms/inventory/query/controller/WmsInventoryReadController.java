/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : 规则、物料、业务任务、追踪、定位及导入预览入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadContract;
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
import com.innolux.iws.wms.inventory.query.service.WmsInventoryReadService;
import com.innolux.iws.wms.rule.WmsRuleContract;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * P7 在库管理只读入口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/wms/inventory/read")
public class WmsInventoryReadController {

    private final WmsInventoryReadService readService;

    @GetMapping("/capabilities")
    public R<CapabilitiesView> capabilities() {
        return R.ok(readService.capabilities());
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY)
    @PostMapping("/materials/summary/page")
    public R<PageResult<MaterialSummaryView>> materialSummaries(
        @RequestBody(required = false) MaterialQuery query
    ) {
        return R.ok(readService.queryMaterialSummaries(query));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY)
    @PostMapping("/materials/details/page")
    public R<PageResult<MaterialDetailView>> materialDetails(
        @RequestBody(required = false) MaterialQuery query
    ) {
        return R.ok(readService.queryMaterialDetails(query));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY)
    @GetMapping("/materials/{inventoryId}")
    public R<MaterialDetailEnvelope> material(@PathVariable long inventoryId) {
        return R.ok(readService.getMaterialDetail(inventoryId));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY)
    @PostMapping("/materials/locate")
    public R<LocateView> locate(@RequestBody LocateQuery query) {
        return R.ok(readService.locate(query));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY)
    @PostMapping(value = "/materials/import-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ImportPreviewView> importPreview(@RequestPart("file") MultipartFile file) {
        return R.ok(readService.previewMaterialImport(file));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_TRACE_QUERY)
    @PostMapping("/traces/page")
    public R<PageResult<TraceSummaryView>> traces(@RequestBody(required = false) TraceQuery query) {
        return R.ok(readService.queryTraces(query));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_TRACE_QUERY)
    @GetMapping("/traces/{traceId}")
    public R<TraceDetailView> trace(@PathVariable long traceId) {
        return R.ok(readService.getTrace(traceId));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_TRACE_EXPORT)
    @PostMapping("/traces/export")
    public ResponseEntity<byte[]> exportTraces(@RequestBody(required = false) TraceQuery query) {
        byte[] body = readService.exportTraces(query);
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename("wms-material-trace.csv", StandardCharsets.UTF_8)
                    .build()
                    .toString()
            )
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(body);
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_TASK_QUERY)
    @PostMapping("/business-tasks/page")
    public R<PageResult<BusinessTaskView>> businessTasks(
        @RequestBody(required = false) BusinessTaskQuery query
    ) {
        return R.ok(readService.queryBusinessTasks(query));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_RULE_QUERY)
    @PostMapping("/rules/definitions/page")
    public R<PageResult<RuleDefinitionView>> ruleDefinitions(
        @RequestBody(required = false) RuleDefinitionQuery query
    ) {
        return R.ok(readService.queryRuleDefinitions(query));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_RULE_QUERY)
    @GetMapping("/rules/definitions/{definitionId}")
    public R<RuleDefinitionDetailView> ruleDefinition(@PathVariable long definitionId) {
        return R.ok(readService.getRuleDefinition(definitionId));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_RULE_QUERY)
    @PostMapping("/rules/packages/page")
    public R<PageResult<RulePackageView>> rulePackages(
        @RequestBody(required = false) RulePackageQuery query
    ) {
        return R.ok(readService.queryRulePackages(query));
    }

    @SaCheckPermission(WmsInventoryReadContract.PERMISSION_RULE_QUERY)
    @GetMapping("/rules/packages/{packageVersionId}")
    public R<RulePackageDetailView> rulePackage(@PathVariable long packageVersionId) {
        return R.ok(readService.getRulePackage(packageVersionId));
    }

    @SaCheckPermission(WmsRuleContract.PERMISSION_DRAFT)
    @PostMapping("/rules/preview")
    public R<RulePreviewView> previewRule(@RequestBody RulePreviewCommand command) {
        return R.ok(readService.previewRule(command));
    }
}
