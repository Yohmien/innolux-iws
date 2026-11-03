/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询
 * @Version      : V1.0
 * @Description  : 聚合规则、物料、装载、业务任务与 trace 的服务端只读事实；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.excel.core.ExcelResult;
import com.innolux.iws.common.excel.utils.ExcelBuilder;
import com.innolux.iws.wms.inventory.query.WmsInventoryImportExcelRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryPermissionGuard;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadContract;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadScopeResolver;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.AttributeView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.BusinessTaskQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.BusinessTaskView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.CapabilitiesView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.ImportPreviewRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.ImportPreviewView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.LoadNodeView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.LocateItemView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.LocateQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.LocateView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialDetailEnvelope;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialSummaryView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialTreeView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.ProductionView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleDefinitionDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleDefinitionQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleDefinitionView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageMemberView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePreviewCommand;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePreviewView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleVersionView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.StorageLocationView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceSummaryView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.AttributeRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.BusinessTaskRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.LoadNodeRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.LocateRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.MaterialDetailRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.MaterialSummaryRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.ProductionRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.RuleDefinitionRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.RulePackageMemberRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.RulePackageRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.RuleVersionRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.StorageLocationRow;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadRows.TraceSummaryRow;
import com.innolux.iws.wms.inventory.query.mapper.WmsInventoryReadMapper;
import com.innolux.iws.wms.inventory.query.service.WmsInventoryReadService;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;
import com.innolux.iws.wms.material.trace.service.WmsMaterialTraceService;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * P7 服务端只读事实聚合实现。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryReadServiceImpl implements WmsInventoryReadService {

    private static final long MAX_IMPORT_BYTES = 2L * 1024L * 1024L;

    private static final int MAX_IMPORT_ROWS = 1000;

    private static final int MAX_LOCATE_ROWS = 50;

    private static final DateTimeFormatter CSV_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WmsInventoryReadMapper readMapper;

    private final WmsMaterialTraceService traceService;

    private final WmsRuleBindingService ruleBindingService;

    private final WmsInventoryPermissionGuard permissionGuard;

    private final WmsInventoryReadScopeResolver readScopeResolver;

    private final JsonMapper jsonMapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CapabilitiesView capabilities() {
        Map<String, Boolean> actions = new LinkedHashMap<>();
        actions.put("materialQuery", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY));
        actions.put("traceQuery", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_TRACE_QUERY));
        actions.put("traceExport", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_TRACE_EXPORT));
        actions.put("businessQuery", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_BUSINESS_QUERY));
        actions.put("taskQuery", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_TASK_QUERY));
        actions.put("allocationInvestigate", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_ALLOCATION_INVESTIGATE));
        actions.put("ruleQuery", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_RULE_QUERY));
        actions.put("ruleDraft", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_RULE_DRAFT));
        actions.put("rulePublish", permissionGuard.hasPermission(
            WmsRuleContract.PERMISSION_PUBLISH));
        actions.put("materialWrite", false);
        actions.put("moveWrite", false);
        actions.put("pdaLocate", permissionGuard.hasPermission(
            WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY));
        return new CapabilitiesView(actions);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<MaterialSummaryView> queryMaterialSummaries(MaterialQuery query) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY);
        MaterialQuery safe = query == null ? new MaterialQuery() : query;
        Page<MaterialSummaryRow> page = new Page<>(
            safe.getPageNum(),
            safe.normalizedPageSize()
        );
        List<MaterialSummaryView> rows = readMapper.selectMaterialSummaries(
            page,
            safe,
            readScopeResolver.current()
        ).stream().map(this::toMaterialSummary).toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<MaterialDetailView> queryMaterialDetails(MaterialQuery query) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY);
        MaterialQuery safe = query == null ? new MaterialQuery() : query;
        Page<MaterialDetailRow> page = new Page<>(
            safe.getPageNum(),
            safe.normalizedPageSize()
        );
        List<MaterialDetailView> rows = readMapper.selectMaterialDetails(
            page,
            safe,
            readScopeResolver.current()
        ).stream().map(this::toMaterialDetail).toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MaterialDetailEnvelope getMaterialDetail(long inventoryId) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY);
        MaterialDetailRow row = readMapper.selectMaterialDetail(
            inventoryId,
            readScopeResolver.current()
        );
        if (row == null) {
            throw notFound("库存单元不存在: " + inventoryId);
        }
        ProductionView production = productionView(readMapper.selectProduction(inventoryId));
        List<AttributeView> attributes = loadAttributes(production);
        MaterialTreeView tree = loadTree(inventoryId);
        StorageLocationView location = null;
        if (!tree.nodes().isEmpty()) {
            long rootNodeId = Long.parseLong(tree.nodes().getFirst().rootNodeId());
            location = toStorageLocation(readMapper.selectStorageLocation(rootNodeId));
        }
        MaterialDetailView material = toMaterialDetail(row, production, attributes, location);

        List<TraceSummaryView> traces = List.of();
        if (permissionGuard.hasPermission(WmsInventoryReadContract.PERMISSION_TRACE_QUERY)) {
            TraceQuery traceQuery = new TraceQuery();
            traceQuery.setInventoryId(inventoryId);
            traceQuery.setPageSize(10);
            Page<TraceSummaryRow> tracePage = new Page<>(1, 10);
            traces = readMapper.selectTraceSummaries(
                tracePage,
                traceQuery,
                readScopeResolver.current()
            ).stream().map(this::toTraceSummary).toList();
        }
        return new MaterialDetailEnvelope(material, tree, traces);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<TraceSummaryView> queryTraces(TraceQuery query) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_TRACE_QUERY);
        TraceQuery safe = query == null ? new TraceQuery() : query;
        Page<TraceSummaryRow> page = new Page<>(
            safe.getPageNum(),
            safe.normalizedPageSize()
        );
        List<TraceSummaryView> rows = readMapper.selectTraceSummaries(
            page,
            safe,
            readScopeResolver.current()
        ).stream().map(this::toTraceSummary).toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TraceDetailView getTrace(long traceId) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_TRACE_QUERY);
        TraceSummaryRow summary = readMapper.selectTraceSummaryById(
            traceId,
            readScopeResolver.current()
        );
        if (summary == null) {
            throw notFound("物料 trace 不存在: " + traceId);
        }
        WmsMaterialTraceSnapshot snapshot = traceService.findByTraceId(traceId)
            .orElseThrow(() -> notFound("物料 trace 快照不存在: " + traceId));
        return new TraceDetailView(toTraceSummary(summary), snapshot);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] exportTraces(TraceQuery query) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_TRACE_EXPORT);
        TraceQuery safe = query == null ? new TraceQuery() : query;
        safe.setPageNum(1);
        safe.setPageSize(WmsInventoryReadContract.MAX_PAGE_SIZE);
        List<TraceSummaryView> rows = new ArrayList<>();
        while (rows.size() < WmsInventoryReadContract.TRACE_EXPORT_MAX_ROWS) {
            int remaining = WmsInventoryReadContract.TRACE_EXPORT_MAX_ROWS - rows.size();
            int limit = Math.min(WmsInventoryReadContract.MAX_PAGE_SIZE, remaining);
            Page<TraceSummaryRow> page = new Page<>(
                rows.size() / limit + 1L,
                limit
            );
            List<TraceSummaryView> pageRows = readMapper.selectTraceSummaries(
                page,
                safe,
                readScopeResolver.current()
            ).stream().map(this::toTraceSummary).toList();
            rows.addAll(pageRows);
            if (pageRows.size() < limit || rows.size() >= page.getTotal()) {
                break;
            }
        }
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("traceId,effectKey,businessType,businessName,businessId,taskKey,"
            + "planInstanceId,planType,traceKind,effectResult,actorName,occurredAt,recordedAt\n");
        for (TraceSummaryView row : rows) {
            csv.append(csv(row.traceId())).append(',')
                .append(csv(row.effectKey())).append(',')
                .append(csv(row.businessType())).append(',')
                .append(csv(row.businessName())).append(',')
                .append(csv(row.businessId())).append(',')
                .append(csv(row.taskKey())).append(',')
                .append(csv(row.planInstanceId())).append(',')
                .append(csv(row.planType())).append(',')
                .append(csv(row.traceKind())).append(',')
                .append(csv(row.effectResult())).append(',')
                .append(csv(row.actorName())).append(',')
                .append(csv(time(row.occurredAt()))).append(',')
                .append(csv(time(row.recordedAt()))).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<BusinessTaskView> queryBusinessTasks(BusinessTaskQuery query) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_TASK_QUERY);
        BusinessTaskQuery safe = query == null ? new BusinessTaskQuery() : query;
        Page<BusinessTaskRow> page = new Page<>(
            safe.getPageNum(),
            safe.normalizedPageSize()
        );
        List<BusinessTaskView> rows = readMapper.selectBusinessTasks(
            page,
            safe,
            readScopeResolver.current()
        ).stream().map(this::toBusinessTask).toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<RuleDefinitionView> queryRuleDefinitions(RuleDefinitionQuery query) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_RULE_QUERY);
        RuleDefinitionQuery safe = query == null ? new RuleDefinitionQuery() : query;
        Page<RuleDefinitionRow> page = new Page<>(
            safe.getPageNum(),
            safe.normalizedPageSize()
        );
        List<RuleDefinitionView> rows = readMapper.selectRuleDefinitions(
            page,
            safe,
            readScopeResolver.current()
        ).stream().map(this::toRuleDefinition).toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RuleDefinitionDetailView getRuleDefinition(long definitionId) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_RULE_QUERY);
        RuleDefinitionRow definition = readMapper.selectRuleDefinition(
            definitionId,
            readScopeResolver.current()
        );
        if (definition == null) {
            throw notFound("规则定义不存在: " + definitionId);
        }
        List<RuleVersionView> versions = readMapper.selectRuleVersions(
                definitionId,
                readScopeResolver.current()
            )
            .stream().map(this::toRuleVersion).toList();
        return new RuleDefinitionDetailView(toRuleDefinition(definition), versions);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<RulePackageView> queryRulePackages(RulePackageQuery query) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_RULE_QUERY);
        RulePackageQuery safe = query == null ? new RulePackageQuery() : query;
        Page<RulePackageRow> page = new Page<>(
            safe.getPageNum(),
            safe.normalizedPageSize()
        );
        List<RulePackageView> rows = readMapper.selectRulePackages(
            page,
            safe,
            readScopeResolver.current()
        ).stream().map(this::toRulePackage).toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RulePackageDetailView getRulePackage(long packageVersionId) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_RULE_QUERY);
        RulePackageRow packageRow = readMapper.selectRulePackage(
            packageVersionId,
            readScopeResolver.current()
        );
        if (packageRow == null) {
            throw notFound("规则包版本不存在: " + packageVersionId);
        }
        List<RulePackageMemberView> members = readMapper.selectRulePackageMembers(packageVersionId)
            .stream().map(this::toRulePackageMember).toList();
        return new RulePackageDetailView(toRulePackage(packageRow), members);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RulePreviewView previewRule(RulePreviewCommand command) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_RULE_DRAFT);
        if (command == null || command.getPurpose() == null || command.getPurpose().isBlank()
            || command.getScopeType() == null || command.getScopeType().isBlank()
            || command.getCondition() == null || command.getResult() == null) {
            throw invalidQuery("规则预览必须提供用途、作用域、条件和结果");
        }
        WmsRuleDefinition definition = new WmsRuleDefinition();
        definition.setPurpose(command.getPurpose().trim());
        definition.setScopeType(command.getScopeType().trim());
        WmsRuleVersion version = new WmsRuleVersion();
        version.setConditionJson(jsonMapper.writeValueAsString(command.getCondition()));
        version.setResultJson(jsonMapper.writeValueAsString(command.getResult()));
        WmsRuleBindingService.Compiled compiled = ruleBindingService.compile(definition, version);
        JsonNode snapshot = jsonMapper.readTree(compiled.snapshotJson());
        List<String> attributes = new ArrayList<>();
        for (JsonNode binding : snapshot.path("bindings")) {
            attributes.add(binding.path("attributeKey").stringValue());
        }
        JsonNode purpose = snapshot.path("purposeContract");
        JsonNode limits = snapshot.path("limits");
        return new RulePreviewView(
            true,
            compiled.digest(),
            "READY",
            attributes,
            purpose.path("purpose").stringValue(),
            purpose.path("version").stringValue(),
            purpose.path("scopeType").stringValue(),
            limits.path("version").stringValue()
        );
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LocateView locate(LocateQuery query) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY);
        String code = query == null || query.getCode() == null ? "" : query.getCode().trim();
        if (code.isEmpty() || code.length() > 128) {
            throw invalidQuery("扫描编码不能为空且不得超过128字符");
        }
        List<LocateItemView> items = readMapper.locateMaterials(
                code,
                MAX_LOCATE_ROWS,
                readScopeResolver.current()
            )
            .stream().map(this::toLocateItem).toList();
        String outcome;
        if (items.isEmpty()) {
            outcome = "EMPTY";
        } else if (items.size() == 1) {
            outcome = "FOUND";
        } else if (items.stream().allMatch(item -> "MATERIAL".equals(item.matchType()))) {
            outcome = "MATERIAL_MULTIPLE";
        } else if (items.stream().allMatch(item -> "STORAGE".equals(item.matchType()))) {
            outcome = "STORAGE_MULTIPLE";
        } else {
            outcome = "MULTIPLE";
        }
        return new LocateView(code, outcome, items);
    }

    @Override
    public ImportPreviewView previewMaterialImport(MultipartFile file) {
        permissionGuard.requirePermission(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY);
        if (file == null || file.isEmpty()) {
            throw invalidImport("导入文件不能为空");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw invalidImport("导入文件不得超过2MB");
        }
        try {
            byte[] bytes = file.getBytes();
            String fileHash = sha256(bytes);
            String filename = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
            ParsedImport parsed;
            if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                ExcelResult<WmsInventoryImportExcelRow> result =
                    ExcelBuilder.read(file.getInputStream(), WmsInventoryImportExcelRow.class)
                        .validate(false)
                        .failFast(false)
                        .doRead();
                List<RawImportRow> rawRows = result.getList().stream()
                    .map(row -> new RawImportRow(
                        row.getMaterialCode(),
                        row.getStockUnitCode(),
                        row.getQuantity(),
                        row.getUnit(),
                        row.getReason()
                    ))
                    .toList();
                parsed = new ParsedImport(rawRows, result.getErrorList());
            } else if (filename.endsWith(".csv") || filename.endsWith(".txt")) {
                parsed = parseCsv(bytes);
            } else {
                throw invalidImport("仅支持 .xlsx、.xls、.csv 或 .txt 导入文件");
            }
            return buildImportPreview(fileHash, parsed);
        } catch (ServiceException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw invalidImport("导入文件解析失败: " + safeMessage(error));
        }
    }

    private ProductionView productionView(ProductionRow row) {
        if (row == null) {
            return null;
        }
        return new ProductionView(
            text(row.getProductionInfoId()),
            row.getMaterialCode(),
            row.getMaterialType(),
            row.getProductionDate() == null ? null : row.getProductionDate().atStartOfDay(),
            row.getMaterialBatch(),
            row.getSupplierCode(),
            row.getSupplierName(),
            row.getCustomerCode(),
            row.getCustomerName(),
            row.getLengthValue(),
            row.getWidthValue(),
            row.getHeightValue(),
            row.getDimensionUnit(),
            row.getBomLevel(),
            row.getBomQty(),
            row.getBomVersion(),
            row.getFinishedMaterialCode(),
            row.getFinishedMaterialType(),
            row.getParseVersion(),
            row.getRevision()
        );
    }

    private List<AttributeView> loadAttributes(ProductionView production) {
        if (production == null) {
            return List.of();
        }
        return readMapper.selectProductionAttributes(Long.parseLong(production.productionInfoId()))
            .stream()
            .map(this::toAttribute)
            .toList();
    }

    private MaterialTreeView loadTree(long inventoryId) {
        List<LoadNodeRow> ancestry = readMapper.selectLoadAncestry(inventoryId);
        if (ancestry.isEmpty()) {
            return new MaterialTreeView(text(inventoryId), List.of(), List.of());
        }
        long rootNodeId = ancestry.getFirst().getLoadNodeId();
        int rootDepth = ancestry.getFirst().getDepth();
        List<LoadNodeView> ancestryViews = ancestry.stream()
            .map(row -> toLoadNode(row, rootNodeId, rootDepth))
            .toList();
        List<LoadNodeRow> subtree = readMapper.selectLoadSubtree(rootNodeId);
        List<Long> inventoryIds = subtree.stream()
            .map(LoadNodeRow::getInventoryId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Set<Long> visibleInventoryIds = inventoryIds.isEmpty()
            ? Set.of()
            : new HashSet<>(readMapper.selectVisibleInventoryIds(
                inventoryIds,
                readScopeResolver.current()
            ));
        Set<Long> excludedNodeIds = new HashSet<>();
        List<LoadNodeView> nodes = subtree.stream()
            .filter(row -> {
                boolean excludedParent = row.getParentNodeId() != null
                    && excludedNodeIds.contains(row.getParentNodeId());
                boolean visibleLeaf = row.getInventoryId() != null
                    && visibleInventoryIds.contains(row.getInventoryId());
                boolean structuralCarrier = row.getInventoryId() == null
                    && "CARRIER".equals(row.getNodeType());
                if (excludedParent || !(visibleLeaf || structuralCarrier)) {
                    excludedNodeIds.add(row.getLoadNodeId());
                    return false;
                }
                return true;
            })
            .map(row -> toLoadNode(row, rootNodeId, 0))
            .toList();
        return new MaterialTreeView(text(inventoryId), ancestryViews, nodes);
    }

    private MaterialSummaryView toMaterialSummary(MaterialSummaryRow row) {
        return new MaterialSummaryView(
            row.getMaterialCode(),
            row.getMaterialName(),
            row.getWarehouseCode(),
            row.getStorageCode(),
            row.getUnit(),
            zero(row.getInventoryCount()),
            zero(row.getInventoryQty()),
            zero(row.getReservedQty()),
            zero(row.getFrozenQty()),
            zero(row.getAvailableQty()),
            zero(row.getFrozenCount()),
            text(row.getLogicalScopeVersionId()),
            row.getLogicalScopeCode(),
            row.getLogicalScopeName(),
            text(row.getRulePackageVersionId()),
            row.getLastUpdatedAt()
        );
    }

    private MaterialDetailView toMaterialDetail(MaterialDetailRow row) {
        return toMaterialDetail(row, null, List.of(), null);
    }

    private MaterialDetailView toMaterialDetail(
        MaterialDetailRow row,
        ProductionView production,
        List<AttributeView> attributes,
        StorageLocationView location
    ) {
        return new MaterialDetailView(
            text(row.getInventoryId()),
            row.getStockUnitCode(),
            row.getMaterialCode(),
            row.getMaterialName(),
            row.getMaterialBatch(),
            row.getDateCode(),
            row.getSupplierCode(),
            row.getSupplierName(),
            row.getPlantCode(),
            row.getWarehouseCode(),
            text(row.getZoneId()),
            row.getZoneCode(),
            text(row.getStorageId()),
            row.getStorageCode(),
            text(row.getContainerId()),
            row.getContainerCode(),
            row.getCompartmentCode(),
            zero(row.getInventoryQty()),
            zero(row.getReservedQty()),
            zero(row.getFrozenQty()),
            zero(row.getAvailableQty()),
            row.getUnit(),
            row.getStatus(),
            row.getFrozenStatus(),
            row.getInboundStatus(),
            row.getQualityStatus(),
            row.getProductionDate() == null ? null : row.getProductionDate().atStartOfDay(),
            row.getReceivingTime(),
            row.getInboundTime(),
            row.getReservedOrderNo(),
            row.getSourceOrderNo(),
            row.getTargetPlace(),
            row.getVersion(),
            text(row.getLogicalScopeVersionId()),
            row.getLogicalScopeCode(),
            row.getLogicalScopeName(),
            text(row.getRulePackageVersionId()),
            row.getCreateTime(),
            row.getUpdateTime(),
            production,
            List.copyOf(attributes),
            location
        );
    }

    private AttributeView toAttribute(AttributeRow row) {
        return new AttributeView(
            row.getAttributeKey(),
            row.getSeqNo() == null ? 0 : row.getSeqNo(),
            row.getValueText(),
            row.getValueNumber(),
            row.getValueDate() == null ? null : row.getValueDate().atStartOfDay(),
            row.getValueDateTime(),
            row.getUnit(),
            row.getSourceRef()
        );
    }

    private StorageLocationView toStorageLocation(StorageLocationRow row) {
        if (row == null) {
            return null;
        }
        return new StorageLocationView(
            text(row.getRootNodeId()),
            row.getLocationKind(),
            text(row.getStorageId()),
            row.getStorageCode(),
            row.getWarehouseCode(),
            row.getZoneCode(),
            row.getRevision()
        );
    }

    private LoadNodeView toLoadNode(LoadNodeRow row, long rootNodeId, int rootDepth) {
        return new LoadNodeView(
            text(row.getLoadNodeId()),
            row.getNodeType(),
            text(row.getInventoryId()),
            row.getStockUnitCode(),
            row.getMaterialCode(),
            text(row.getContainerId()),
            row.getContainerCode(),
            text(row.getParentNodeId()),
            text(rootNodeId),
            Math.max(0, row.getDepth() == null ? 0 : row.getDepth() - rootDepth),
            row.getStatus(),
            row.getRevision(),
            row.getLocationKind(),
            text(row.getStorageId()),
            row.getStorageCode()
        );
    }

    private TraceSummaryView toTraceSummary(TraceSummaryRow row) {
        return new TraceSummaryView(
            text(row.getTraceId()),
            row.getEffectKey(),
            row.getBusinessType(),
            row.getBusinessName(),
            text(row.getBusinessId()),
            row.getBusinessNo(),
            text(row.getBusinessLineId()),
            row.getRootBusinessType(),
            text(row.getRootBusinessId()),
            row.getTaskKey(),
            text(row.getPlanInstanceId()),
            row.getPlanCode(),
            row.getPlanType(),
            row.getDefinitionVersion(),
            text(row.getEventId()),
            text(row.getAttemptId()),
            row.getInputHash(),
            text(row.getReverseTraceId()),
            row.getTraceKind(),
            row.getEffectResult(),
            row.getActorName(),
            row.getDepartmentName(),
            row.getExecutionSubject(),
            row.getExecutionAccount(),
            row.getEvidenceRef(),
            row.getOccurredAt(),
            row.getRecordedAt()
        );
    }

    private BusinessTaskView toBusinessTask(BusinessTaskRow row) {
        return new BusinessTaskView(
            text(row.getTaskId()),
            row.getBusinessType(),
            text(row.getBusinessId()),
            text(row.getLineId()),
            text(row.getAllocationUnitId()),
            row.getRoundNo(),
            row.getPhase(),
            row.getTaskKey(),
            row.getPlanType(),
            text(row.getPlanInstanceId()),
            row.getInputHash(),
            row.getStatus(),
            row.getRevision(),
            row.getInstanceStatus(),
            text(row.getInstanceVersion()),
            row.getLatestEventCode(),
            row.getLatestEventStatus(),
            row.getLatestEffectResult(),
            row.getStartedAt(),
            row.getEndedAt(),
            row.getUpdatedAt()
        );
    }

    private RuleDefinitionView toRuleDefinition(RuleDefinitionRow row) {
        return new RuleDefinitionView(
            text(row.getRuleDefinitionId()),
            row.getRuleKey(),
            row.getPurpose(),
            row.getRuleName(),
            row.getScopeType(),
            row.getStatus(),
            text(row.getCurrentVersionId()),
            row.getRevision(),
            zero(row.getVersionCount()),
            zero(row.getActiveVersionCount()),
            row.getUpdatedAt()
        );
    }

    private RuleVersionView toRuleVersion(RuleVersionRow row) {
        WmsRuleVersion version = new WmsRuleVersion();
        version.setRuleVersionId(row.getRuleVersionId());
        version.setRuleDefinitionId(row.getRuleDefinitionId());
        version.setVersionNo(row.getVersionNo());
        version.setConditionJson(row.getConditionJson());
        version.setResultJson(row.getResultJson());
        version.setAttributeBindingsJson(row.getAttributeBindingsJson());
        version.setCanonicalDigest(row.getCanonicalDigest());
        version.setStatus(row.getStatus());
        version.setPublishedAt(row.getPublishedAt());
        version.setPublishedBy(row.getPublishedBy());
        WmsRuleVersionVo vo = ruleBindingService.toVersionVo(version);
        return new RuleVersionView(
            text(vo.ruleVersionId()),
            text(vo.ruleDefinitionId()),
            vo.versionNo(),
            vo.status(),
            vo.canonicalDigest(),
            vo.condition(),
            vo.result(),
            vo.bindingSnapshotState(),
            vo.executionReady(),
            vo.publishedAt(),
            text(vo.publishedBy())
        );
    }

    private RulePackageView toRulePackage(RulePackageRow row) {
        return new RulePackageView(
            text(row.getPackageVersionId()),
            row.getPackageCode(),
            row.getVersionNo(),
            row.getCanonicalDigest(),
            row.getStatus(),
            row.getPublishedAt(),
            text(row.getPublishedBy()),
            zero(row.getMemberCount())
        );
    }

    private RulePackageMemberView toRulePackageMember(RulePackageMemberRow row) {
        return new RulePackageMemberView(
            row.getPurpose(),
            text(row.getRuleVersionId()),
            row.getRevision(),
            row.getRuleKey(),
            row.getRuleName(),
            row.getScopeType(),
            text(row.getVersionNo()),
            row.getStatus()
        );
    }

    private LocateItemView toLocateItem(LocateRow row) {
        return new LocateItemView(
            text(row.getInventoryId()),
            row.getStockUnitCode(),
            row.getMaterialCode(),
            row.getMaterialName(),
            row.getStorageCode(),
            row.getContainerCode(),
            zero(row.getInventoryQty()),
            row.getUnit(),
            row.getStatus(),
            row.getFrozenStatus(),
            row.getInboundStatus(),
            row.getMatchType()
        );
    }

    private ImportPreviewView buildImportPreview(String fileHash, ParsedImport parsed) {
        List<ImportPreviewRow> rows = new ArrayList<>();
        int valid = 0;
        for (int index = 0; index < parsed.rows().size(); index++) {
            RawImportRow row = parsed.rows().get(index);
            List<String> errors = validateImportRow(row);
            if (errors.isEmpty()) {
                valid++;
            }
            BigDecimal quantity = null;
            if (row.quantity() != null && !row.quantity().isBlank()) {
                try {
                    quantity = new BigDecimal(row.quantity().trim());
                } catch (NumberFormatException ignored) {
                    quantity = null;
                }
            }
            rows.add(new ImportPreviewRow(
                index + 2,
                row.materialCode(),
                row.stockUnitCode(),
                quantity,
                row.unit(),
                row.reason(),
                List.copyOf(errors)
            ));
        }
        return new ImportPreviewView(
            fileHash,
            rows.size(),
            valid,
            rows.size() - valid,
            fileHash.substring(0, 16),
            false,
            "物料正式写入仍需要持久父业务、允许挂载关系及真实范围授权",
            List.copyOf(parsed.fileErrors()),
            List.copyOf(rows)
        );
    }

    private List<String> validateImportRow(RawImportRow row) {
        List<String> errors = new ArrayList<>();
        requireImportText(errors, "物料编码", row.materialCode(), 50);
        requireImportText(errors, "库存单元编码", row.stockUnitCode(), 100);
        requireImportText(errors, "单位", row.unit(), 32);
        requireImportText(errors, "原因", row.reason(), 500);
        if (row.quantity() == null || row.quantity().isBlank()) {
            errors.add("数量必填");
        } else {
            try {
                BigDecimal quantity = new BigDecimal(row.quantity().trim());
                if (quantity.signum() < 0) {
                    errors.add("数量不得为负数");
                }
                if (quantity.scale() > 6) {
                    errors.add("数量小数位不得超过6位");
                }
                if (quantity.precision() - quantity.scale() > 14) {
                    errors.add("数量整数位不得超过14位");
                }
            } catch (NumberFormatException error) {
                errors.add("数量不是有效十进制");
            }
        }
        return errors;
    }

    private void requireImportText(
        List<String> errors,
        String label,
        String value,
        int maxLength
    ) {
        if (value == null || value.isBlank()) {
            errors.add(label + "必填");
        } else if (value.length() > maxLength) {
            errors.add(label + "不得超过" + maxLength + "字符");
        }
    }

    private ParsedImport parseCsv(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        List<List<String>> records = parseCsvRecords(content);
        if (records.isEmpty()) {
            return new ParsedImport(List.of(), List.of("文件没有可读取的数据行"));
        }
        List<String> header = records.getFirst();
        boolean hasHeader = header.stream().anyMatch(cell ->
            "materialCode".equalsIgnoreCase(cell)
                || "物料编码".equals(cell)
                || "stockUnitCode".equalsIgnoreCase(cell)
        );
        Map<String, Integer> columns = hasHeader
            ? importColumns(header)
            : Map.of(
                "materialCode", 0,
                "stockUnitCode", 1,
                "quantity", 2,
                "unit", 3,
                "reason", 4
            );
        List<String> errors = new ArrayList<>();
        List<RawImportRow> rows = new ArrayList<>();
        int start = hasHeader ? 1 : 0;
        for (int index = start; index < records.size(); index++) {
            List<String> record = records.get(index);
            rows.add(new RawImportRow(
                cell(record, columns.get("materialCode")),
                cell(record, columns.get("stockUnitCode")),
                cell(record, columns.get("quantity")),
                cell(record, columns.get("unit")),
                cell(record, columns.get("reason"))
            ));
            if (rows.size() > MAX_IMPORT_ROWS) {
                errors.add("导入行数超过上限" + MAX_IMPORT_ROWS);
                break;
            }
        }
        return new ParsedImport(rows, errors);
    }

    private Map<String, Integer> importColumns(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String value = header.get(index).trim();
            switch (value) {
                case "materialCode", "物料编码", "料号" -> columns.put("materialCode", index);
                case "stockUnitCode", "库存单元编码", "盘号" -> columns.put("stockUnitCode", index);
                case "quantity", "数量" -> columns.put("quantity", index);
                case "unit", "单位" -> columns.put("unit", index);
                case "reason", "原因" -> columns.put("reason", index);
                default -> {
                }
            }
        }
        return columns;
    }

    private List<List<String>> parseCsvRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < content.length()
                    && content.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else if (character == '"') {
                    quoted = false;
                } else {
                    value.append(character);
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                current.add(value.toString());
                value.setLength(0);
            } else if (character == '\n') {
                current.add(value.toString());
                records.add(List.copyOf(current));
                current.clear();
                value.setLength(0);
            } else if (character != '\r') {
                value.append(character);
            }
        }
        if (!current.isEmpty() || !value.isEmpty()) {
            current.add(value.toString());
            records.add(List.copyOf(current));
        }
        return records;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private String time(LocalDateTime value) {
        return value == null ? "" : value.format(CSV_TIME);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName()
            : message;
    }

    private String cell(List<String> record, Integer index) {
        if (index == null || index < 0 || index >= record.size()) {
            return null;
        }
        return record.get(index);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long zero(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private ServiceException invalidQuery(String message) {
        return new ServiceException(
            WmsInventoryReadContract.ERROR_INVALID_QUERY + ": " + message,
            HttpStatus.HTTP_BAD_REQUEST
        );
    }

    private ServiceException invalidImport(String message) {
        return new ServiceException(
            WmsInventoryReadContract.ERROR_IMPORT_INVALID + ": " + message,
            HttpStatus.HTTP_BAD_REQUEST
        );
    }

    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.HTTP_NOT_FOUND);
    }

    private record RawImportRow(
        String materialCode,
        String stockUnitCode,
        String quantity,
        String unit,
        String reason
    ) {
    }

    private record ParsedImport(List<RawImportRow> rows, List<String> fileErrors) {
    }
}
