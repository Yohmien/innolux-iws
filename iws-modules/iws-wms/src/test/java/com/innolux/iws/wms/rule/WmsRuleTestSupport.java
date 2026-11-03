/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务集成测试
 * @Version      : V1.0
 * @Description  : 规则服务集成测试的共享上下文、数据库清理与命令构造；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDraftEditCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackageCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionActivateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionCopyCommand;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.service.WmsRuleDraftService;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import com.innolux.iws.wms.rule.service.WmsRuleQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规则服务集成测试基类。
 *
 * @author YONGJIE.PENG
 */
@Tag("local")
@SpringJUnitConfig(WmsRuleTestContext.class)
abstract class WmsRuleTestSupport {

    protected static final String SCOPE_TYPE = "WAREHOUSE";

    protected static final long SCOPE_ID = 501L;

    protected static final long COMPANY_ID = 1L;

    protected static final long WAREHOUSE_ID_2 = 502L;

    protected static final long MATERIAL_ZONE_ID = 1001L;

    protected static final long DEVICE_ZONE_ID = 1002L;

    protected static final long STORAGE_ID_1 = 10L;

    protected static final long STORAGE_ID_2 = 20L;

    protected static final long STORAGE_ID_3 = 30L;

    protected static final long STORAGE_ID_2_1 = 40L;

    protected static final String DEFAULT_CONDITION = """
        {"schema":"iws.rule.condition/1","root":{"type":"SCALAR_COMPARE",
        "attribute":"materialCode","operator":"EQ","value":"MAT-01"}}
        """;

    protected static final String DEFAULT_RESULT = """
        {"schema":"iws.rule.result/1","type":"FILTER","value":true}
        """;

    @Autowired
    protected WmsRuleDraftService draftService;

    @Autowired
    protected WmsRulePublishService publishService;

    @Autowired
    protected WmsRuleQueryService queryService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void installSaToken() {
        WmsRuleTestAuth.install();
    }

    @BeforeEach
    void resetRuleState() {
        jdbcTemplate.update("delete from wms_company_management_grant");
        jdbcTemplate.update("delete from wms_company_management_scope");
        jdbcTemplate.update("delete from wms_storage");
        jdbcTemplate.update("delete from wms_warehouse_zone");
        jdbcTemplate.update("delete from wms_warehouse");
        jdbcTemplate.update("delete from wms_company");
        jdbcTemplate.update("delete from sys_role_menu");
        jdbcTemplate.update("delete from sys_user_role");
        jdbcTemplate.update("delete from sys_menu");
        jdbcTemplate.update("delete from sys_role");
        for (String table : List.of(
            "wms_logical_scope_storage",
            "wms_logical_scope_version",
            "wms_logical_scope",
            "wms_rule_effective_pointer",
            "wms_rule_package_member",
            "wms_rule_package_version",
            "wms_rule_version",
            "wms_rule_definition",
            "wms_attribute_catalog",
            "wms_object_audit",
            "wms_command_attempt"
        )) {
            jdbcTemplate.update("delete from " + table);
        }
        seedScopeFixture();
        seedAttributeCatalog();
        WmsRuleTestAuth.loginAsAdmin();
    }

    @AfterEach
    void clearSaToken() {
        WmsRuleTestAuth.clear();
    }

    protected WmsRuleDefinitionVo createDefinition(String ruleKey, String purpose, String scopeType) {
        return createDefinition(ruleKey, purpose, scopeType, DEFAULT_CONDITION, DEFAULT_RESULT);
    }

    protected WmsRuleDefinitionVo createDefinition(String ruleKey, String purpose, String scopeType,
                                                    String conditionJson, String resultJson) {
        WmsRuleDefinitionCreateCommand command = new WmsRuleDefinitionCreateCommand();
        command.setCommandKey(newCommandKey("create"));
        command.setRuleKey(ruleKey);
        command.setPurpose(purpose);
        command.setRuleName("规则-" + ruleKey);
        command.setScopeType(scopeType);
        command.setCondition(json(conditionJson));
        command.setResult(json(resultJson));
        command.setReason("集成测试创建");
        return draftService.createDefinition(command);
    }

    protected WmsRuleVersionVo editDraft(WmsRuleVersionVo draft, String conditionJson, String resultJson) {
        WmsRuleDraftEditCommand command = new WmsRuleDraftEditCommand();
        command.setCommandKey(newCommandKey("edit"));
        command.setRuleVersionId(draft.ruleVersionId());
        command.setExpectedDigest(draft.canonicalDigest());
        command.setCondition(json(conditionJson));
        command.setResult(json(resultJson));
        command.setReason("集成测试编辑");
        return draftService.editDraft(command);
    }

    protected WmsRuleVersionVo activate(WmsRuleVersionVo draft) {
        WmsRuleVersionActivateCommand command = new WmsRuleVersionActivateCommand();
        command.setCommandKey(newCommandKey("activate"));
        command.setRuleVersionId(draft.ruleVersionId());
        command.setExpectedDigest(draft.canonicalDigest());
        command.setReason("集成测试激活");
        return draftService.activateVersion(command);
    }

    protected WmsRuleVersionVo copyDraft(Long sourceRuleVersionId) {
        WmsRuleVersionCopyCommand command = new WmsRuleVersionCopyCommand();
        command.setCommandKey(newCommandKey("copy"));
        command.setSourceRuleVersionId(sourceRuleVersionId);
        command.setReason("集成测试复制");
        return draftService.copyDraft(command);
    }

    protected WmsRulePackageVersionVo createPackage(String packageCode,
                                                    Map<String, Long> purposeToRuleVersionId) {
        return createPackage(packageCode, purposeToRuleVersionId, newCommandKey("package"));
    }

    protected WmsRulePackageVersionVo createPackage(String packageCode,
                                                    Map<String, Long> purposeToRuleVersionId,
                                                    String commandKey) {
        List<WmsRulePackageCreateCommand.Member> members = purposeToRuleVersionId.entrySet().stream()
            .map(entry -> {
                WmsRulePackageCreateCommand.Member member = new WmsRulePackageCreateCommand.Member();
                member.setPurpose(entry.getKey());
                member.setRuleVersionId(entry.getValue());
                return member;
            })
            .toList();
        WmsRulePackageCreateCommand command = new WmsRulePackageCreateCommand();
        command.setCommandKey(commandKey);
        command.setPackageCode(packageCode);
        command.setMembers(members);
        command.setReason("集成测试创建规则包");
        return publishService.createPackageVersion(command);
    }

    protected WmsRuleEffectiveVo publish(Long packageVersionId, String scopeType, long scopeId,
                                         List<Long> storageIds) {
        return publish(packageVersionId, scopeType, scopeId, storageIds, newCommandKey("publish"));
    }

    protected WmsRuleEffectiveVo publish(Long packageVersionId, String scopeType, long scopeId,
                                         List<Long> storageIds, String commandKey) {
        WmsRulePackagePublishCommand command = new WmsRulePackagePublishCommand();
        command.setCommandKey(commandKey);
        command.setPackageVersionId(packageVersionId);
        command.setScopeType(scopeType);
        command.setScopeId(scopeId);
        command.setStorageIds(storageIds);
        command.setReason("集成测试发布");
        return publishService.publish(command);
    }

    protected JsonNode json(String text) {
        return JsonUtils.getJsonMapper().readTree(text);
    }

    protected static String fixturePurpose(String scopeType) {
        return "STORAGE".equals(scopeType) ? "OUTBOUND_ALLOCATE" : "INBOUND_ACCEPT";
    }

    protected String newCommandKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    protected long countRows(String table, String where, Object... args) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + where, Long.class, args
        );
        return count == null ? 0L : count;
    }

    protected void grantRole(long userId, long roleId, String... permissions) {
        Integer existing = jdbcTemplate.queryForObject(
            "select count(*) from sys_role where role_id = ?", Integer.class, roleId
        );
        if (existing == null || existing == 0) {
            jdbcTemplate.update(
                "insert into sys_role (role_id, role_name) values (?, ?)",
                roleId, "rule-test-" + roleId
            );
        }
        jdbcTemplate.update(
            "insert into sys_user_role (user_id, role_id) values (?, ?)",
            userId, roleId
        );
        long menuId = roleId * 100L
            + jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_id >= ? and menu_id < ?",
                Long.class, roleId * 100L, roleId * 100L + 100L
            );
        for (String permission : permissions) {
            jdbcTemplate.update(
                "insert into sys_menu (menu_id, menu_name, perms) values (?, ?, ?)",
                menuId, permission, permission
            );
            jdbcTemplate.update(
                "insert into sys_role_menu (role_id, menu_id) values (?, ?)",
                roleId, menuId
            );
            menuId++;
        }
    }

    protected void grantCompany(long roleId, long companyId) {
        jdbcTemplate.update(
            "insert into wms_company_management_grant "
                + "(grant_id, company_id, role_id) values (?, ?, ?)",
            roleId, companyId, roleId
        );
    }

    private void seedScopeFixture() {
        jdbcTemplate.update(
            "insert into wms_company "
                + "(company_id, company_code, company_name, enabled, lifecycle_status, revision) "
                + "values (?, 'C-501', '规则范围公司', 0, 'ACTIVE', 1)",
            COMPANY_ID
        );
        jdbcTemplate.update(
            "insert into wms_company_management_scope (company_id, scope_revision) values (?, 0)",
            COMPANY_ID
        );
        jdbcTemplate.update(
            "insert into wms_warehouse "
                + "(warehouse_id, company_id, warehouse_code, warehouse_name, "
                + "warehouse_type_code, lifecycle_status, revision) "
                + "values (?, ?, 'W-501', '规则仓一', 'GENERAL', 'ACTIVE', 1), "
                + "(?, ?, 'W-502', '规则仓二', 'GENERAL', 'ACTIVE', 1)",
            SCOPE_ID, COMPANY_ID, WAREHOUSE_ID_2, COMPANY_ID
        );
        jdbcTemplate.update(
            "insert into wms_warehouse_zone "
                + "(zone_id, zone_code, zone_name, zone_type, warehouse_id, "
                + "parent_zone_id, zone_kind, del_flag) "
                + "values (?, 'M-501', '物料区', 1, ?, null, 'MATERIAL', '0'), "
                + "(?, 'D-501', '设备区', 1, ?, ?, 'DEVICE', '0'), "
                + "(?, 'M-502', '物料区二', 1, ?, null, 'MATERIAL', '0'), "
                + "(?, 'D-502', '设备区二', 1, ?, ?, 'DEVICE', '0')",
            MATERIAL_ZONE_ID, SCOPE_ID,
            DEVICE_ZONE_ID, SCOPE_ID, MATERIAL_ZONE_ID,
            MATERIAL_ZONE_ID + 10, WAREHOUSE_ID_2,
            DEVICE_ZONE_ID + 10, WAREHOUSE_ID_2, MATERIAL_ZONE_ID + 10
        );
        jdbcTemplate.update("update wms_warehouse_zone set lifecycle_status = 'ACTIVE'");
        jdbcTemplate.update(
            "insert into wms_storage "
                + "(storage_id, storage_code, zone_id, warehouse_id, lifecycle_status, aisle_text) values "
                + "(?, 'S-10', ?, ?, 'ACTIVE', 'A-10'), (?, 'S-20', ?, ?, 'ACTIVE', 'A-20'), "
                + "(?, 'S-30', ?, ?, 'ACTIVE', 'A-30'), (?, 'S-40', ?, ?, 'ACTIVE', 'A-40')",
            STORAGE_ID_1, DEVICE_ZONE_ID, SCOPE_ID,
            STORAGE_ID_2, DEVICE_ZONE_ID, SCOPE_ID,
            STORAGE_ID_3, DEVICE_ZONE_ID, SCOPE_ID,
            STORAGE_ID_2_1, DEVICE_ZONE_ID + 10, WAREHOUSE_ID_2
        );
    }

    private void seedAttributeCatalog() {
        jdbcTemplate.update("""
            insert into wms_attribute_catalog (
                attribute_key, source_type, value_type, cardinality, operators_json,
                unit, scale, groupable, status, contract_version, revision
            ) values
                ('materialCode', 'MATERIAL', 'STRING', 'SCALAR',
                 json_array('EQ', 'NE'), null, null, 1, 'ACTIVE', 'ATTR-1', 1),
                ('batchNo', 'MATERIAL', 'STRING', 'SCALAR',
                 json_array('EQ', 'NE'), null, null, 1, 'ACTIVE', 'ATTR-1', 1),
                ('supplierCode', 'MATERIAL', 'STRING', 'SCALAR',
                 json_array('EQ', 'NE'), null, null, 1, 'ACTIVE', 'ATTR-1', 1)
            """);
    }
}
