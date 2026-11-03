/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 只读查询集成测试
 * @Version      : V1.0
 * @Description  : 验证真实 MySQL 下的只读查询、分页、导入预览、定位与权限边界；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.inventory.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.effect.investigation.WmsAllocationEffectInvestigationController;
import com.innolux.iws.wms.allocation.effect.investigation.WmsAllocationEffectInvestigationService;
import com.innolux.iws.wms.allocation.effect.investigation.WmsAllocationEffectUnknownQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.BusinessTaskQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.ImportPreviewView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.LocateQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.LocateView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialDetailEnvelope;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.MaterialSummaryView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RuleDefinitionDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePackageDetailView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePreviewCommand;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.RulePreviewView;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceQuery;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadModels.TraceSummaryView;
import com.innolux.iws.wms.inventory.query.controller.WmsInventoryReadController;
import com.innolux.iws.wms.inventory.query.service.WmsInventoryReadService;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.system.api.domain.RoleDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P7 只读查询真实 MySQL 集成测试。
 */
@Tag("local")
@SpringJUnitConfig(WmsInventoryReadTestContext.class)
class WmsInventoryReadIntegrationTest {

    private static final long INVENTORY_ID_1 = 101L;

    private static final long INVENTORY_ID_2 = 102L;

    private static final long INVENTORY_ID_3 = 201L;

    private static final long INVENTORY_ID_4 = 301L;

    private static final long INVENTORY_ID_5 = 302L;

    private static final long PRODUCTION_INFO_ID = 301L;

    private static final long STORAGE_ID = 401L;

    private static final long STORAGE_ID_2 = 402L;

    private static final long STORAGE_ID_OTHER_COMPANY = 403L;

    private static final long ROOT_NODE_ID = 501L;

    private static final long MATERIAL_NODE_ID = 502L;

    private static final long CONTAINER_ID = 601L;

    private static final long TRACE_ID = 701L;

    private static final long DEFINITION_ID = 801L;

    private static final long VERSION_ID = 811L;

    private static final long PACKAGE_ID = 901L;

    private static final long INSTANCE_ID = 1001L;

    private static final long TASK_ID = 1101L;

    @Autowired
    private WmsInventoryReadService readService;

    @Autowired
    private WmsAllocationEffectInvestigationService effectInvestigationService;

    @Autowired
    private WmsInventoryReadTestContext.TestPermissionGuard permissionGuard;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WmsRuleBindingService ruleBindingService;

    @BeforeEach
    void resetDatabase() {
        WmsInventoryReadTestAuth.loginAsAdmin();
        for (String table : List.of(
            "wms_object_audit",
            "wms_access_channel_lock",
            "wms_allocation_lock",
            "wms_allocation_release",
            "wms_allocation_candidate",
            "wms_allocation_decision",
            "wms_inventory_reservation",
            "iws_process_event",
            "iws_process_instance",
            "wms_business_task",
            "wms_material_trace_edge",
            "wms_material_trace_item",
            "wms_material_trace_history",
            "wms_rule_package_member",
            "wms_rule_package_version",
            "wms_rule_version",
            "wms_rule_definition",
            "wms_attribute_catalog",
            "wms_logical_assignment_history",
            "wms_material_logical_assignment",
            "wms_logical_scope_storage",
            "wms_logical_scope_version",
            "wms_logical_scope",
            "wms_storage_info",
            "wms_load_relation",
            "wms_load_node",
            "wms_material_attribute_value",
            "wms_production_info",
            "wms_container",
            "wms_object_visibility_department",
            "wms_object_visibility",
            "wms_storage",
            "wms_warehouse_zone",
            "wms_warehouse",
            "wms_inventory"
        )) {
            jdbcTemplate.update("delete from " + table);
        }
        permissionGuard.setAllowed(true);
        seedInventory();
    }

    @AfterEach
    void clearAuthentication() {
        WmsInventoryReadTestAuth.clear();
    }

    @Test
    void mapperXmlLoadsAndMaterialSummaryKeepsDecimalPaging() {
        MaterialQuery first = new MaterialQuery();
        first.setPageNum(1);
        first.setPageSize(1);

        var firstPage = readService.queryMaterialSummaries(first);
        assertThat(firstPage.getTotal()).isEqualTo(2L);
        assertThat(firstPage.getRows()).singleElement().satisfies(row -> {
            assertThat(row.materialCode()).isEqualTo("MAT-01");
            assertThat(row.inventoryCount()).isEqualTo(2L);
            assertThat(row.inventoryQty()).isEqualByComparingTo(new BigDecimal("10.700000"));
            assertThat(row.reservedQty()).isEqualByComparingTo(new BigDecimal("1.200000"));
            assertThat(row.frozenQty()).isEqualByComparingTo(new BigDecimal("10.700000"));
            assertThat(row.availableQty()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.frozenCount()).isEqualTo(2L);
        });

        first.setPageNum(2);
        var secondPage = readService.queryMaterialSummaries(first);
        assertThat(secondPage.getRows()).singleElement()
            .extracting(MaterialSummaryView::materialCode)
            .isEqualTo("MAT-02");
        assertThat(secondPage.getRows().stream().findFirst().orElseThrow().inventoryQty())
            .isEqualByComparingTo(new BigDecimal("2.250000"));
    }

    @Test
    void materialDetailIncludesProductionAttributesLoadTreeAndLocation() {
        seedMaterialGraph();

        MaterialDetailEnvelope detail = readService.getMaterialDetail(INVENTORY_ID_1);

        assertThat(detail.material().inventoryId()).isEqualTo("101");
        assertThat(detail.material().availableQty())
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(detail.material().frozenQty())
            .isEqualByComparingTo(new BigDecimal("10.500000"));
        assertThat(detail.material().production()).isNotNull();
        assertThat(detail.material().production().productionInfoId()).isEqualTo("301");
        assertThat(detail.material().production().lengthValue())
            .isEqualByComparingTo(new BigDecimal("12.345678901234"));
        assertThat(detail.material().attributes()).singleElement().satisfies(attribute -> {
            assertThat(attribute.attributeKey()).isEqualTo("materialCode");
            assertThat(attribute.valueText()).isEqualTo("MAT-01");
        });
        assertThat(detail.tree().ancestry()).extracting(node -> node.loadNodeId())
            .containsExactly("501", "502");
        assertThat(detail.tree().nodes()).hasSize(2);
        assertThat(detail.material().currentLocation().locationKind()).isEqualTo("STORAGE");
        assertThat(detail.material().currentLocation().storageCode()).isEqualTo("S-01");
        assertThat(detail.material().currentLocation().warehouseCode()).isEqualTo("WH-01");
        assertThat(detail.material().currentLocation().zoneCode()).isEqualTo("Z-01");
    }

    @Test
    void materialDetailDoesNotExposeInvisibleSiblingInventoryOnSameCarrier() {
        seedMaterialGraph();
        jdbcTemplate.update("""
            update wms_inventory
               set create_dept = 2000, create_by = 9002
             where inventory_id = ?
            """, INVENTORY_ID_2);
        jdbcTemplate.update("""
            insert into wms_load_node (
                load_node_id, node_type, inventory_id, container_id, status, revision
            ) values (503, 'MATERIAL', ?, null, 'LOADED', 1)
            """, INVENTORY_ID_2);
        jdbcTemplate.update("""
            insert into wms_load_relation (
                relation_id, child_node_id, parent_node_id, revision
            ) values (20002, 503, ?, 1)
            """, ROOT_NODE_ID);
        loginOrdinary(1000L);

        MaterialDetailEnvelope detail = readService.getMaterialDetail(INVENTORY_ID_1);

        assertThat(detail.tree().nodes()).extracting(node -> node.inventoryId())
            .containsExactly(null, "101");
        assertThat(detail.tree().nodes()).extracting(node -> node.stockUnitCode())
            .doesNotContain("REEL-102");
    }

    @Test
    void materialDetailOmitsTracesWithoutIndependentTracePermission() {
        seedMaterialGraph();
        seedTrace();
        permissionGuard.setAllowedPermissions(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY);

        MaterialDetailEnvelope detail = readService.getMaterialDetail(INVENTORY_ID_1);

        assertThat(detail.recentTraces()).isEmpty();
        assertThatThrownBy(() -> readService.queryTraces(new TraceQuery()))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getCode()).isEqualTo(403));
    }

    @Test
    void availableQuantityUsesFrozenAndActiveReservationFactsWithoutDoubleCounting() {
        jdbcTemplate.update("""
            update wms_inventory
               set frozen_status = 0, reserved_qty = 0
             where inventory_id = ?
            """, INVENTORY_ID_2);
        jdbcTemplate.update("""
            insert into wms_inventory_reservation (
                reservation_id, inventory_id, business_type, business_name,
                business_id, allocation_unit_id, round_no, phase, task_key,
                plan_instance_id, reserved_qty, unit, status, effect_result, effect_key
            ) values (
                90001, ?, 'MATERIAL_CHANGE', '物料变更', 5001, 5101, 1, 'LOCK',
                'RESERVE-ACTIVE', 5201, 0.100000, 'EA', 'ACTIVE', 'NONE',
                ?
            )
            """, INVENTORY_ID_2, "WMS:" + "1".repeat(64));
        jdbcTemplate.update("""
            insert into wms_inventory_reservation (
                reservation_id, inventory_id, business_type, business_name,
                business_id, allocation_unit_id, round_no, phase, task_key,
                plan_instance_id, reserved_qty, unit, status, effect_result, effect_key,
                terminal_effect_key, terminal_release_id, terminal_at
            ) values (
                90002, ?, 'MATERIAL_CHANGE', '物料变更', 5002, 5102, 1, 'LOCK',
                'RESERVE-RELEASED', 5202, 8.000000, 'EA', 'RELEASED', 'NO_EFFECT',
                ?, ?, 90002, '2026-11-02 09:00:00.000'
            )
            """, INVENTORY_ID_2, "WMS:" + "2".repeat(64),
            "WMS:" + "3".repeat(64));

        MaterialQuery query = new MaterialQuery();
        query.setMaterialCode("MAT-01");
        MaterialSummaryView summary = readService.queryMaterialSummaries(query)
            .getRows().stream().findFirst().orElseThrow();

        assertThat(summary.reservedQty())
            .as("预留事实 %s", jdbcTemplate.queryForList(
                "select inventory_id, reserved_qty, status from wms_inventory_reservation"))
            .isEqualByComparingTo("1.100000");
        assertThat(summary.frozenQty()).isEqualByComparingTo("10.500000");
        assertThat(summary.availableQty()).isEqualByComparingTo("0.100000");
        assertThat(readService.getMaterialDetail(INVENTORY_ID_2).material().availableQty())
            .isEqualByComparingTo("0.100000");
    }

    @Test
    void summaryKeepsDifferentLogicalRuleVersionsInSeparateGroups() {
        seedRuleVersionAssignment(
            9101L, 9201L, 9301L, 1L, INVENTORY_ID_1,
            "SCOPE-A", "1".repeat(64)
        );
        seedRuleVersionAssignment(
            9102L, 9202L, 9302L, 2L, INVENTORY_ID_2,
            "SCOPE-B", "2".repeat(64)
        );

        MaterialQuery query = new MaterialQuery();
        query.setMaterialCode("MAT-01");
        var rows = readService.queryMaterialSummaries(query).getRows();

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(MaterialSummaryView::logicalScopeCode)
            .containsExactlyInAnyOrder("SCOPE-A", "SCOPE-B");
        assertThat(rows).extracting(MaterialSummaryView::rulePackageVersionId)
            .containsExactlyInAnyOrder("9301", "9302");
    }

    @Test
    void tracePageDetailAndCsvExportUseStableSnapshot() {
        seedTrace();

        TraceQuery query = new TraceQuery();
        query.setInventoryId(INVENTORY_ID_1);
        query.setBusinessType("MATERIAL_CHANGE");
        var page = readService.queryTraces(query);
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getRows()).singleElement().satisfies(trace -> {
            assertThat(trace.traceId()).isEqualTo("701");
            assertThat(trace.inputHash()).isEqualTo("a".repeat(64));
            assertThat(trace.actorName()).isEqualTo("操作员");
            assertThat(trace.taskKey()).isEqualTo("TASK-701");
        });

        TraceQuery taskQuery = new TraceQuery();
        taskQuery.setTaskKey("TASK-701");
        assertThat(readService.queryTraces(taskQuery).getTotal()).isEqualTo(1L);
        taskQuery.setTaskKey("");
        assertThat(readService.queryTraces(taskQuery).getTotal()).isZero();

        var detail = readService.getTrace(TRACE_ID);
        assertThat(detail.snapshot().history().traceId()).isEqualTo(TRACE_ID);
        assertThat(detail.snapshot().items()).singleElement()
            .extracting(item -> item.stockUnitCode())
            .isEqualTo("REEL-001");

        String csv = new String(readService.exportTraces(query), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFtraceId,effectKey");
        assertThat(csv).contains("\"701\"", "\"MATERIAL_CHANGE\"", "\"操作员\"");
    }

    @Test
    void businessTaskJoinsLatestProcessEventAndFiltersInstanceStatus() {
        seedBusinessTask();

        BusinessTaskQuery query = new BusinessTaskQuery();
        query.setBusinessType("MATERIAL_CHANGE");
        query.setPlanType("MATERIAL_CHANGE");
        query.setInstanceStatus("RUNNING");
        query.setLatestEffectResult("UNKNOWN");
        var page = readService.queryBusinessTasks(query);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getRows()).singleElement().satisfies(task -> {
            assertThat(task.taskId()).isEqualTo("1101");
            assertThat(task.instanceStatus()).isEqualTo("RUNNING");
            assertThat(task.latestEventCode()).isEqualTo("ALLOCATE");
            assertThat(task.latestEventStatus()).isEqualTo("RUNNING");
            assertThat(task.latestEffectResult()).isEqualTo("UNKNOWN");
        });

        query.setInstanceStatus("ENDED");
        assertThat(readService.queryBusinessTasks(query).getTotal()).isZero();

        query.setInstanceStatus("RUNNING");
        query.setLatestEffectResult("APPLIED");
        assertThat(readService.queryBusinessTasks(query).getTotal()).isZero();
    }

    @Test
    void ruleReadsExposeDraftActiveRetiredAndReadinessWithoutMutation() {
        seedRules();
        long before = countRows();

        var definitions = readService.queryRuleDefinitions(
            new WmsInventoryReadModels.RuleDefinitionQuery()
        );
        assertThat(definitions.getRows()).singleElement().satisfies(definition -> {
            assertThat(definition.ruleDefinitionId()).isEqualTo("801");
            assertThat(definition.versionCount()).isEqualTo(2L);
            assertThat(definition.activeVersionCount()).isEqualTo(1L);
        });

        RuleDefinitionDetailView detail = readService.getRuleDefinition(DEFINITION_ID);
        assertThat(detail.versions()).extracting(version -> version.status())
            .containsExactly("ACTIVE", "DRAFT");
        assertThat(detail.versions()).allSatisfy(version -> {
            assertThat(version.bindingSnapshotState()).isEqualTo("LEGACY_UNBOUND");
            assertThat(version.executionReady()).isFalse();
        });

        RulePackageDetailView rulePackage = readService.getRulePackage(PACKAGE_ID);
        assertThat(rulePackage.packageVersion().memberCount()).isEqualTo(1L);
        assertThat(rulePackage.members()).singleElement().satisfies(member -> {
            assertThat(member.purpose()).isEqualTo("ALLOCATION_CANDIDATE_FILTER");
            assertThat(member.ruleKey()).isEqualTo("RULE-01");
            assertThat(member.scopeType()).isEqualTo("STORAGE");
        });
        assertThat(countRows()).isEqualTo(before);
    }

    @Test
    void ruleReadsUseStoredBindingSnapshotToReportExecutionReadiness() {
        seedAttributeCatalog();
        long definitionId = 802L;
        long versionId = 812L;
        jdbcTemplate.update("""
            insert into wms_rule_definition (
                rule_definition_id, rule_key, purpose, rule_name, scope_type,
                status, current_version_id, revision, update_time
            ) values (?, 'RULE-READY', 'ALLOCATION_CANDIDATE_FILTER', '就绪规则',
                      'STORAGE', 'ACTIVE', ?, 1, '2026-11-01 07:00:00.000')
            """, definitionId, versionId);

        WmsRuleDefinition definition = new WmsRuleDefinition();
        definition.setPurpose("ALLOCATION_CANDIDATE_FILTER");
        definition.setScopeType("STORAGE");
        WmsRuleVersion version = new WmsRuleVersion();
        version.setConditionJson("""
            {"schema":"iws.rule.condition/1","root":{"type":"SCALAR_COMPARE",
            "attribute":"materialCode","operator":"EQ","value":"MAT-01"}}
            """);
        version.setResultJson("""
            {"schema":"iws.rule.result/1","type":"FILTER","value":true}
            """);
        WmsRuleBindingService.Compiled compiled = ruleBindingService.compile(definition, version);

        jdbcTemplate.update("""
            insert into wms_rule_version (
                rule_version_id, rule_definition_id, version_no, condition_json,
                result_json, attribute_bindings_json, canonical_digest, status,
                published_at, published_by
            ) values (?, ?, 1, cast(? as json), cast(? as json), cast(? as json), ?,
                      'ACTIVE', '2026-11-01 07:00:00.000', 1)
            """,
            versionId,
            definitionId,
            version.getConditionJson(),
            version.getResultJson(),
            compiled.snapshotJson(),
            compiled.digest()
        );

        RuleDefinitionDetailView detail = readService.getRuleDefinition(definitionId);
        assertThat(detail.versions()).singleElement().satisfies(readVersion -> {
            assertThat(readVersion.bindingSnapshotState()).isEqualTo("READY");
            assertThat(readVersion.executionReady()).isTrue();
        });
    }

    @Test
    void draftPreviewReturnsFrozenBindingContractWithoutWriting() {
        seedAttributeCatalog();
        long before = countRows();

        RulePreviewCommand command = new RulePreviewCommand();
        command.setPurpose("ALLOCATION_CANDIDATE_FILTER");
        command.setScopeType("STORAGE");
        command.setCondition(jsonMapper.readTree("""
            {
              "schema": "iws.rule.condition/1",
              "root": {
                "type": "SCALAR_COMPARE",
                "attribute": "materialCode",
                "operator": "EQ",
                "value": "MAT-01"
              }
            }
            """));
        command.setResult(jsonMapper.readTree("""
            {
              "schema": "iws.rule.result/1",
              "type": "FILTER",
              "value": true
            }
            """));

        RulePreviewView preview = readService.previewRule(command);
        assertThat(preview.valid()).isTrue();
        assertThat(preview.bindingSnapshotState()).isEqualTo("READY");
        assertThat(preview.referencedAttributes()).containsExactly("materialCode");
        assertThat(preview.canonicalDigest()).matches("[0-9a-f]{64}");
        assertThat(countRows()).isEqualTo(before);
    }

    @Test
    void csvImportPreviewReportsBadRowsAndStaysNonWritable() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "inventory.csv",
            "text/csv",
            """
                materialCode,stockUnitCode,quantity,unit,reason
                MAT-01,REEL-101,1.250000,EA,盘点修正
                MAT-02,REEL-102,-1,EA,错误数量
                """.getBytes(StandardCharsets.UTF_8)
        );

        ImportPreviewView preview = readService.previewMaterialImport(file);
        assertThat(preview.fileHash()).matches("[0-9a-f]{64}");
        assertThat(preview.totalRows()).isEqualTo(2);
        assertThat(preview.validRows()).isEqualTo(1);
        assertThat(preview.invalidRows()).isEqualTo(1);
        assertThat(preview.writable()).isFalse();
        assertThat(preview.writeBlockedReason()).contains("真实范围授权");
        assertThat(preview.rows()).extracting(row -> row.rowNo()).containsExactly(2, 3);
        assertThat(preview.rows().get(1).errors()).contains("数量不得为负数");
    }

    @Test
    void pdaLocateUsesScanPriorityAndReturnsStableOutcomes() {
        LocateQuery one = new LocateQuery();
        one.setCode("REEL-101");
        LocateView found = readService.locate(one);
        assertThat(found.outcome()).isEqualTo("FOUND");
        assertThat(found.items()).singleElement()
            .extracting(item -> item.matchType())
            .isEqualTo("STOCK_UNIT");

        LocateQuery material = new LocateQuery();
        material.setCode("MAT-01");
        LocateView multiple = readService.locate(material);
        assertThat(multiple.outcome()).isEqualTo("MATERIAL_MULTIPLE");
        assertThat(multiple.items()).extracting(item -> item.stockUnitCode())
            .containsExactly("REEL-101", "REEL-102");

        LocateQuery empty = new LocateQuery();
        empty.setCode("NOT-FOUND");
        assertThat(readService.locate(empty).outcome()).isEqualTo("EMPTY");
    }

    @Test
    void ordinaryUserCannotReadOtherCompanyInventoryOrTrace() {
        seedOtherCompanyInventory();
        seedOtherCompanyTrace();
        loginOrdinary(1000L);

        MaterialQuery query = new MaterialQuery();
        query.setPageSize(20);
        var materials = readService.queryMaterialSummaries(query);
        assertThat(materials.getTotal()).isEqualTo(2L);
        assertThat(materials.getRows()).extracting(MaterialSummaryView::materialCode)
            .containsExactly("MAT-01", "MAT-02")
            .doesNotContain("MAT-SECRET");

        assertThatThrownBy(() -> readService.getMaterialDetail(INVENTORY_ID_4))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getCode()).isEqualTo(404));

        LocateQuery locate = new LocateQuery();
        locate.setCode("REEL-SECRET");
        assertThat(readService.locate(locate).outcome()).isEqualTo("EMPTY");

        TraceQuery trace = new TraceQuery();
        trace.setBusinessId(3001L);
        assertThat(readService.queryTraces(trace).getTotal()).isZero();
        assertThatThrownBy(() -> readService.getTrace(TRACE_ID + 1))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getCode()).isEqualTo(404));
    }

    @Test
    void ordinaryUserStillReceivesExistingRowDataPermission() {
        jdbcTemplate.update("""
            update wms_inventory
               set create_dept = 1001, create_by = 9002
             where inventory_id = ?
            """, INVENTORY_ID_2);
        loginOrdinary(1000L);

        MaterialQuery query = new MaterialQuery();
        query.setPageSize(20);
        var materials = readService.queryMaterialSummaries(query);
        assertThat(materials.getTotal()).isEqualTo(2L);
        assertThat(materials.getRows()).filteredOn(
                row -> "MAT-01".equals(row.materialCode())
            )
            .singleElement()
            .satisfies(row -> {
                assertThat(row.inventoryCount()).isEqualTo(1L);
                assertThat(row.inventoryQty()).isEqualByComparingTo("10.500000");
            });
        assertThatThrownBy(() -> readService.getMaterialDetail(INVENTORY_ID_2))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getCode()).isEqualTo(404));
    }

    @Test
    void unknownInvestigationUsesInventoryVisibility() {
        seedOtherCompanyInventory();
        seedUnknownLocks();
        loginOrdinary(1000L);

        var rows = effectInvestigationService.findUnknown(
            new WmsAllocationEffectUnknownQuery("MATERIAL_CHANGE", null, null, 100)
        );
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.allocationLockId()).isEqualTo("5001");
            assertThat(row.storageId()).isEqualTo(String.valueOf(STORAGE_ID));
            assertThat(row.channelStatus()).isEqualTo("LOCKED");
            assertThat(row.channelEffectResult()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    void capabilitiesAreReadOnlyAndServiceRejectsMissingPermission() {
        var capabilities = readService.capabilities();
        assertThat(capabilities.allowedActions())
            .containsEntry("materialWrite", false)
            .containsEntry("moveWrite", false)
            .containsEntry("pdaLocate", true);
        assertThat(capabilities.allowedActions().keySet())
            .containsExactlyInAnyOrderElementsOf(WmsInventoryReadContract.ACTION_KEYS);

        permissionGuard.setAllowed(false);
        assertThatThrownBy(() -> readService.queryMaterialSummaries(new MaterialQuery()))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getCode()).isEqualTo(403);
                assertThat(error.getMessage()).contains("inventory:material:query");
            });
    }

    @Test
    void controllerRoutesDeclareExactPermissionBoundaries() throws Exception {
        assertPermission(
            "materialSummaries",
            PostMapping.class,
            "/materials/summary/page",
            WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY
        );
        assertPermission(
            "exportTraces",
            PostMapping.class,
            "/traces/export",
            WmsInventoryReadContract.PERMISSION_TRACE_EXPORT
        );
        assertPermission(
            "previewRule",
            PostMapping.class,
            "/rules/preview",
            WmsRuleContract.PERMISSION_DRAFT
        );
        assertPermission(
            "businessTasks",
            PostMapping.class,
            "/business-tasks/page",
            WmsInventoryReadContract.PERMISSION_TASK_QUERY
        );

        Method unknown = WmsAllocationEffectInvestigationController.class.getMethod(
            "unknown",
            String.class,
            Long.class,
            String.class,
            int.class
        );
        RequestMapping base = WmsAllocationEffectInvestigationController.class
            .getAnnotation(RequestMapping.class);
        assertThat(base.value()).containsExactly("/wms/inventory/allocation");
        assertThat(unknown.getAnnotation(GetMapping.class).value()).containsExactly("/unknown");
        assertThat(unknown.getAnnotation(SaCheckPermission.class).value())
            .containsExactly(WmsInventoryReadContract.PERMISSION_ALLOCATION_INVESTIGATE);
    }

    private void assertPermission(
        String methodName,
        Class<? extends java.lang.annotation.Annotation> mappingType,
        String expectedRoute,
        String expectedPermission
    ) throws Exception {
        Method method = java.util.Arrays.stream(WmsInventoryReadController.class.getMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
        RequestMapping base = WmsInventoryReadController.class.getAnnotation(RequestMapping.class);
        assertThat(base.value()).containsExactly("/wms/inventory/read");
        if (mappingType == PostMapping.class) {
            assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(expectedRoute);
        } else {
            assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(expectedRoute);
        }
        assertThat(method.getAnnotation(SaCheckPermission.class).value())
            .containsExactly(expectedPermission);
    }

    private void seedInventory() {
        seedWarehouseFixture();
        insertInventory(
            INVENTORY_ID_1,
            "REEL-101",
            "MAT-01",
            "WH-01",
            "S-01",
            STORAGE_ID,
            "C-01",
            "10.500000",
            "1.000000",
            1,
            "2026-11-01 10:00:00.000"
        );
        insertInventory(
            INVENTORY_ID_2,
            "REEL-102",
            "MAT-01",
            "WH-01",
            "S-01",
            STORAGE_ID,
            "C-02",
            "0.200000",
            "0.200000",
            1,
            "2026-11-01 10:01:00.000"
        );
        insertInventory(
            INVENTORY_ID_3,
            "REEL-201",
            "MAT-02",
            "WH-01",
            "S-02",
            STORAGE_ID_2,
            null,
            "2.250000",
            "0.000000",
            0,
            "2026-11-01 10:02:00.000"
        );
    }

    private void seedWarehouseFixture() {
        jdbcTemplate.update("""
            insert into wms_warehouse (
                warehouse_id, company_id, warehouse_code, warehouse_name,
                warehouse_type_code, lifecycle_status, revision
            ) values (21, 1, 'WH-01', '一号仓', 'GENERAL', 'ACTIVE', 1)
            """);
        jdbcTemplate.update("""
            insert into wms_warehouse_zone (
                zone_id, zone_code, zone_name, zone_type, zone_kind,
                warehouse_id, del_flag
            ) values (31, 'Z-01', '物料区', 1, 'DEVICE', 21, '0')
            """);
        jdbcTemplate.update("""
            insert into wms_storage (
                storage_id, storage_code, zone_id, warehouse_id, aisle_text
            ) values (?, 'S-01', 31, 21, 'A-01'), (?, 'S-02', 31, 21, 'A-02')
            """, STORAGE_ID, STORAGE_ID_2);
        seedVisibility();
    }

    private void seedVisibility() {
        jdbcTemplate.update("""
            insert into wms_object_visibility (
                visibility_key, parent_visibility_key, object_type, object_id,
                mode, revision
            ) values
                (100, null, 'COMPANY', 1, 'VALUE', 1),
                (200, 100, 'WAREHOUSE', 21, 'INHERIT', 1),
                (300, 200, 'STORAGE', ?, 'INHERIT', 1),
                (301, 200, 'STORAGE', ?, 'INHERIT', 1),
                (400, null, 'COMPANY', 2, 'VALUE', 1),
                (500, 400, 'WAREHOUSE', 22, 'INHERIT', 1),
                (600, 500, 'STORAGE', ?, 'INHERIT', 1)
            """, STORAGE_ID, STORAGE_ID_2, STORAGE_ID_OTHER_COMPANY);
        jdbcTemplate.update("""
            insert into wms_object_visibility_department (
                visibility_department_id, visibility_key, dept_id
            ) values
                (1001, 100, 1000),
                (1002, 400, 2000)
            """);
    }

    private void seedOtherCompanyInventory() {
        jdbcTemplate.update("""
            insert into wms_warehouse (
                warehouse_id, company_id, warehouse_code, warehouse_name,
                warehouse_type_code, lifecycle_status, revision
            ) values (22, 2, 'WH-02', '二号仓', 'GENERAL', 'ACTIVE', 1)
            """);
        jdbcTemplate.update("""
            insert into wms_warehouse_zone (
                zone_id, zone_code, zone_name, zone_type, zone_kind,
                warehouse_id, del_flag
            ) values (41, 'Z-02', '二公司物料区', 1, 'DEVICE', 22, '0')
            """);
        jdbcTemplate.update("""
            insert into wms_storage (
                storage_id, storage_code, zone_id, warehouse_id, aisle_text
            ) values (?, 'S-OTHER', 41, 22, 'A-OTHER')
            """, STORAGE_ID_OTHER_COMPANY);
        insertInventory(
            INVENTORY_ID_4,
            "REEL-SECRET",
            "MAT-SECRET",
            "WH-02",
            "S-OTHER",
            STORAGE_ID_OTHER_COMPANY,
            null,
            "9.000000",
            "0.000000",
            0,
            "2026-11-01 10:03:00.000"
        );
    }

    private void insertInventory(
        long id,
        String stockUnitCode,
        String materialCode,
        String warehouseCode,
        String storageCode,
        long storageId,
        String containerCode,
        String quantity,
        String reserved,
        int frozen,
        String updatedAt
    ) {
        jdbcTemplate.update("""
            insert into wms_inventory (
                inventory_id, stock_unit_code, material_code, material_name,
                warehouse_code, zone_id, zone_code, storage_id, storage_code,
                container_id, container_code, inventory_qty, reserved_qty, unit,
                status, frozen_status, inbound_status, quality_status,
                version, del_flag, create_dept, create_by, create_time, update_time
            ) values (?, ?, ?, '测试物料', ?, 31, 'Z-01', ?, ?, ?, ?,
                      ?, ?, 'EA', 1, ?, 1, 1, 1, '0', 1000, 9001, ?, ?)
            """,
            id,
            stockUnitCode,
            materialCode,
            warehouseCode,
            storageId,
            storageCode,
            containerCode == null ? null : CONTAINER_ID + id,
            containerCode,
            new BigDecimal(quantity),
            new BigDecimal(reserved),
            frozen,
            updatedAt,
            updatedAt
        );
    }

    private void seedMaterialGraph() {
        jdbcTemplate.update("""
            insert into wms_production_info (
                production_info_id, inventory_id, material_code, material_type,
                production_date, material_batch, supplier_code, supplier_name,
                length_value, width_value, height_value, dimension_unit,
                bom_level, bom_qty, bom_version, finished_material_code,
                finished_material_type, parse_version, revision
            ) values (?, ?, 'MAT-01', 'ELECTRONIC', '2026-10-31', 'B-01',
                      'SUP-01', '供应商', 12.345678901234, 3.400000000000,
                      1.200000000000, 'MM', 2, 4.000000, 'BOM-1',
                      'FG-01', 'FINISHED', 'LABEL-V1', 1)
            """, PRODUCTION_INFO_ID, INVENTORY_ID_1);
        jdbcTemplate.update("""
            insert into wms_material_attribute_value (
                attribute_value_id, production_info_id, attribute_key, seq_no,
                value_text, source_ref
            ) values (10001, ?, 'materialCode', 1, 'MAT-01', 'label')
            """, PRODUCTION_INFO_ID);

        jdbcTemplate.update("""
            insert into wms_container (
                container_id, container_code, container_type_id, storage_id,
                container_status, location_status, del_flag, create_time
            ) values (?, 'CART-01', 1, ?, 1, 1, '0', now(3))
            """, CONTAINER_ID, STORAGE_ID);
        jdbcTemplate.update("""
            insert into wms_load_node (
                load_node_id, node_type, inventory_id, container_id, status, revision
            ) values (?, 'CARRIER', null, ?, 'LOADED', 1),
                     (?, 'MATERIAL', ?, null, 'LOADED', 1)
            """, ROOT_NODE_ID, CONTAINER_ID, MATERIAL_NODE_ID, INVENTORY_ID_1);
        jdbcTemplate.update("""
            insert into wms_load_relation (
                relation_id, child_node_id, parent_node_id, revision
            ) values (20001, ?, ?, 1)
            """, MATERIAL_NODE_ID, ROOT_NODE_ID);
        jdbcTemplate.update("""
            insert into wms_storage_info (
                storage_info_id, root_node_id, location_kind, storage_id, revision
            ) values (30001, ?, 'STORAGE', ?, 1)
            """, ROOT_NODE_ID, STORAGE_ID);
    }

    private void seedTrace() {
        jdbcTemplate.update("""
            insert into wms_material_trace_history (
                trace_id, effect_key, business_type, business_name, business_id,
                root_business_type, root_business_id, task_key, input_hash, trace_kind,
                effect_result, actor_id, actor_name, occurred_at,
                create_dept, create_by
            ) values (?, ?, 'MATERIAL_CHANGE', '物料变更', 2001,
                      'MATERIAL_CHANGE', 2001, 'TASK-701', ?, 'FACT', 'APPLIED', 9001,
                      '操作员', '2026-11-01 09:00:00.000', 1000, 9001)
            """, TRACE_ID, "WMS:" + "b".repeat(64), "a".repeat(64));
        jdbcTemplate.update("""
            insert into wms_material_trace_item (
                item_id, trace_id, inventory_id, stock_unit_code, material_code,
                production_info_id, production_revision, quantity, unit,
                before_qty, delta_qty, after_qty,
                physical_from_storage_id, physical_to_storage_id
            ) values (40001, ?, ?, 'REEL-001', 'MAT-01', null, null,
                      10.000000, 'EA', 10.000000, 1.000000, 11.000000,
                      ?, ?)
            """, TRACE_ID, INVENTORY_ID_1, STORAGE_ID, STORAGE_ID);
    }

    private void seedRuleVersionAssignment(
        long scopeId,
        long scopeVersionId,
        long packageVersionId,
        long versionNo,
        long inventoryId,
        String scopeCode,
        String digest
    ) {
        jdbcTemplate.update("""
            insert into wms_rule_package_version (
                package_version_id, package_code, version_no, canonical_digest,
                status, published_at
            ) values (?, ?, ?, ?, 'ACTIVE', '2026-11-02 08:00:00.000')
            """, packageVersionId, "PKG-" + packageVersionId, versionNo, digest);
        jdbcTemplate.update("""
            insert into wms_logical_scope (
                logical_scope_id, scope_code, scope_name, lifecycle_status, revision
            ) values (?, ?, ?, 'ACTIVE', 1)
            """, scopeId, scopeCode, "逻辑归属" + scopeCode);
        jdbcTemplate.update("""
            insert into wms_logical_scope_version (
                scope_version_id, logical_scope_id, version_no,
                rule_package_version_id, space_snapshot_digest, status, published_at
            ) values (?, ?, ?, ?, ?, 'ACTIVE', '2026-11-02 08:01:00.000')
            """, scopeVersionId, scopeId, versionNo, packageVersionId, digest);
        jdbcTemplate.update("""
            insert into wms_material_logical_assignment (
                inventory_id, scope_version_id, assignment_revision,
                source_business_type, source_business_id, revision
            ) values (?, ?, 1, 'MATERIAL_CHANGE', ?, 1)
            """, inventoryId, scopeVersionId, inventoryId + 10_000L);
    }

    private void seedOtherCompanyTrace() {
        jdbcTemplate.update("""
            insert into wms_material_trace_history (
                trace_id, effect_key, business_type, business_name, business_id,
                root_business_type, root_business_id, input_hash, trace_kind,
                effect_result, actor_id, actor_name, occurred_at,
                create_dept, create_by
            ) values (?, ?, 'MATERIAL_CHANGE', '二公司物料变更', 3001,
                      'MATERIAL_CHANGE', 3001, ?, 'FACT', 'APPLIED', 9001,
                      '操作员', '2026-11-01 09:01:00.000', 1000, 9001)
            """, TRACE_ID + 1, "WMS:" + "c".repeat(64), "d".repeat(64));
        jdbcTemplate.update("""
            insert into wms_material_trace_item (
                item_id, trace_id, inventory_id, stock_unit_code, material_code,
                production_info_id, production_revision, quantity, unit,
                before_qty, delta_qty, after_qty,
                physical_from_storage_id, physical_to_storage_id
            ) values (40002, ?, ?, 'REEL-SECRET', 'MAT-SECRET', null, null,
                      9.000000, 'EA', 8.000000, 1.000000, 9.000000,
                      ?, ?)
            """, TRACE_ID + 1, INVENTORY_ID_4,
            STORAGE_ID_OTHER_COMPANY, STORAGE_ID_OTHER_COMPANY);
    }

    private void seedUnknownLocks() {
        jdbcTemplate.update("""
            insert into wms_access_channel_lock (
                channel_lock_id, channel_id, business_type, business_name,
                business_id, allocation_unit_id, round_no, phase, task_key,
                plan_instance_id, lock_effect_key, status, effect_result,
                path_member_ids_json, path_revision, evidence_revision, revision
            ) values
                (6001, 7001, 'MATERIAL_CHANGE', '物料变更', 4001, 4201, 1, 'LOCK',
                 'MC:4001:1', 4301, ?, 'LOCKED', 'UNKNOWN', json_array(7001), 1, 0, 1),
                (6002, 7002, 'MATERIAL_CHANGE', '二公司物料变更', 4002, 4202, 1, 'LOCK',
                 'MC:4002:1', 4302, ?, 'LOCKED', 'UNKNOWN', json_array(7002), 1, 0, 1)
            """, "WMS:" + "4".repeat(64), "WMS:" + "5".repeat(64));
        jdbcTemplate.update("""
            insert into wms_allocation_lock (
                allocation_lock_id, decision_id, candidate_id, channel_lock_id,
                storage_id, business_type, business_name, business_id, business_no,
                business_line_id, allocation_unit_id, round_no, phase, task_key,
                plan_instance_id, resource_domain, lock_kind, dimension_key, unit,
                locked_qty, status, effect_result, effect_key, evidence_revision,
                revision, create_dept, create_by, update_time
            ) values
                (5001, 50001, 500011, 6001, ?, 'MATERIAL_CHANGE', '物料变更',
                 4001, 'MC-4001', 4101, 4201, 1, 'LOCK', 'MC:4001:1', 4301,
                 'CHANNEL', 'CAPACITY_SHARE', 'WEIGHT', 'KG', 1.000000,
                 'LOCKED', 'UNKNOWN', ?, 0, 1, 1000, 9001,
                 '2026-11-01 11:00:00.000'),
                (5002, 50002, 500012, 6002, ?, 'MATERIAL_CHANGE', '二公司物料变更',
                 4002, 'MC-4002', 4102, 4202, 1, 'LOCK', 'MC:4002:1', 4302,
                 'CHANNEL', 'CAPACITY_SHARE', 'WEIGHT', 'KG', 1.000000,
                 'LOCKED', 'UNKNOWN', ?, 0, 1, 1000, 9001,
                 '2026-11-01 11:01:00.000')
            """, STORAGE_ID, "WMS:" + "6".repeat(64),
            STORAGE_ID_OTHER_COMPANY, "WMS:" + "7".repeat(64));
        jdbcTemplate.update("""
            insert into wms_object_audit (
                audit_id, object_type, object_id, action, command_key,
                result, evidence_json, occurred_at, recorded_at
            ) values
                (8001, 'WMS_ALLOCATION_LOCK', 5001, 'ALLOCATION_EFFECT_UNKNOWN',
                 'unknown-5001', 'UNKNOWN', '{"provider":"timeout"}',
                 '2026-11-01 11:00:01.000',
                 '2026-11-01 11:00:02.000'),
                (8002, 'WMS_ALLOCATION_LOCK', 5002, 'ALLOCATION_EFFECT_UNKNOWN',
                 'unknown-5002', 'UNKNOWN', '{"provider":"timeout"}',
                 '2026-11-01 11:01:01.000',
                 '2026-11-01 11:01:02.000')
            """);
    }

    private void loginOrdinary(long deptId) {
        RoleDTO role = new RoleDTO();
        role.setRoleId(10L);
        role.setRoleName("库存查询");
        role.setRoleKey("inventory-query");
        role.setDataScope("3");
        WmsInventoryReadTestAuth.login(
            9001L,
            deptId,
            java.util.Set.of(WmsInventoryReadContract.PERMISSION_MATERIAL_QUERY),
            List.of(role)
        );
    }

    private void seedBusinessTask() {
        jdbcTemplate.update("""
            insert into iws_process_instance (
                id, plan_status, instance_version, started_at
            ) values (?, 'RUNNING', 7, '2026-11-01 08:00:00.000')
            """, INSTANCE_ID);
        jdbcTemplate.update("""
            insert into iws_process_event (
                id, instance_id, event_code, event_status, effect_result, update_time
            ) values (10001, ?, 'ALLOCATE', 'WAITING', 'UNKNOWN',
                      '2026-11-01 08:01:00.000'),
                     (10002, ?, 'ALLOCATE', 'RUNNING', 'UNKNOWN',
                      '2026-11-01 08:02:00.000')
            """, INSTANCE_ID, INSTANCE_ID);
        jdbcTemplate.update("""
            insert into wms_business_task (
                task_id, business_type, business_id, line_id, allocation_unit_id,
                round_no, phase, task_key, plan_type, plan_instance_id,
                input_hash, input_json, status, revision, create_time, update_time
            ) values (?, 'MATERIAL_CHANGE', 2001, 2101, 2201, 1, 'ALLOCATE',
                      'MC:2001:1', 'MATERIAL_CHANGE', ?, ?, '{}', 'RUNNING', 1,
                      '2026-11-01 08:00:00.000', '2026-11-01 08:02:00.000')
            """, TASK_ID, INSTANCE_ID, "c".repeat(64));
    }

    private void seedRules() {
        jdbcTemplate.update("""
            insert into wms_rule_definition (
                rule_definition_id, rule_key, purpose, rule_name, scope_type,
                status, current_version_id, revision, update_time
            ) values (?, 'RULE-01', 'ALLOCATION_CANDIDATE_FILTER', '候选规则',
                      'STORAGE', 'ACTIVE', ?, 1, '2026-11-01 07:00:00.000')
            """, DEFINITION_ID, VERSION_ID);
        jdbcTemplate.update("""
            insert into wms_rule_version (
                rule_version_id, rule_definition_id, version_no, condition_json,
                result_json, canonical_digest, status, published_at, published_by
            ) values (?, ?, 2, '{}', '{}', ?, 'ACTIVE', '2026-11-01 07:00:00.000', 1),
                     (?, ?, 1, '{}', '{}', ?, 'DRAFT', null, null)
            """,
            VERSION_ID,
            DEFINITION_ID,
            "d".repeat(64),
            VERSION_ID + 1,
            DEFINITION_ID,
            "e".repeat(64)
        );
        jdbcTemplate.update("""
            insert into wms_rule_package_version (
                package_version_id, package_code, version_no, canonical_digest,
                status, published_at, published_by
            ) values (?, 'PKG-01', 1, ?, 'ACTIVE', '2026-11-01 07:05:00.000', 1)
            """, PACKAGE_ID, "f".repeat(64));
        jdbcTemplate.update("""
            insert into wms_rule_package_member (
                package_version_id, purpose, rule_version_id, revision
            ) values (?, 'ALLOCATION_CANDIDATE_FILTER', ?, 1)
            """, PACKAGE_ID, VERSION_ID);
    }

    private void seedAttributeCatalog() {
        jdbcTemplate.update("""
            insert into wms_attribute_catalog (
                attribute_key, source_type, value_type, cardinality, operators_json,
                unit, scale, groupable, status, contract_version, revision
            ) values ('materialCode', 'MATERIAL', 'STRING', 'SCALAR',
                      json_array('EQ', 'NE'), null, null, 1, 'ACTIVE', 'TEST-1', 1)
            """);
    }

    private long countRows() {
        long count = 0;
        for (String table : List.of(
            "wms_rule_definition",
            "wms_rule_version",
            "wms_rule_package_version",
            "wms_rule_package_member",
            "wms_attribute_catalog"
        )) {
            Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
            count += value == null ? 0 : value;
        }
        return count;
    }
}
