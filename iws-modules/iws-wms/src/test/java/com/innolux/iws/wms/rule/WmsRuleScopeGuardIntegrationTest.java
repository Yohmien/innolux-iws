/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 验证规则发布五类目标、空间父链、公司授权与历史读取边界；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import cn.dev33.satoken.exception.NotPermissionException;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则范围 Guard 集成测试。
 *
 * <p>fixture 只建立 guard 的预期目标 schema，不代表正式 core/init 已开放发布。</p>
 *
 * @author YONGJIE.PENG
 */
class WmsRuleScopeGuardIntegrationTest extends WmsRuleTestSupport {

    @Test
    void publishesAllFiveExactScopeTypesAndAllowsEmptyStorageSnapshot() {
        assertPublished("RULE-SCOPE-COMPANY", WmsRuleContract.SCOPE_COMPANY,
            COMPANY_ID, List.of());
        assertPublished("RULE-SCOPE-WAREHOUSE", WmsRuleContract.SCOPE_WAREHOUSE,
            SCOPE_ID, List.of());
        assertPublished("RULE-SCOPE-MATERIAL", WmsRuleContract.SCOPE_MATERIAL_ZONE,
            MATERIAL_ZONE_ID, List.of(STORAGE_ID_1));
        assertPublished("RULE-SCOPE-DEVICE", WmsRuleContract.SCOPE_DEVICE_ZONE,
            DEVICE_ZONE_ID, List.of(STORAGE_ID_2));
        assertPublished("RULE-SCOPE-STORAGE", WmsRuleContract.SCOPE_STORAGE,
            STORAGE_ID_3, List.of());
    }

    @Test
    void unknownTypeFailsClosedBeforePackageLookup() {
        assertThatThrownBy(() -> publish(999999L, "warehouse", SCOPE_ID, List.of()))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith(
                    WmsRuleContract.ERROR_SCOPE_TYPE_UNSUPPORTED);
                assertThat(error.getCode()).isEqualTo(409);
            });
    }

    @Test
    void missingStorageRetiredStorageAndCrossCompanyStorageRejectWithoutWrites() {
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_WAREHOUSE);

        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE, 999999L,
            List.of(), WmsRuleContract.ERROR_SCOPE_TARGET_NOT_FOUND, 404);
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID,
            List.of(999999L), WmsRuleContract.ERROR_SCOPE_STORAGE_NOT_FOUND, 404);

        jdbcTemplate.update("update wms_storage set lifecycle_status = 'RETIRING' where storage_id = ?",
            STORAGE_ID_1);
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID,
            List.of(STORAGE_ID_1), WmsRuleContract.ERROR_SCOPE_STORAGE_RETIRED, 409);

        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID,
            List.of(STORAGE_ID_2_1), WmsRuleContract.ERROR_SCOPE_STORAGE_OUT_OF_SCOPE, 409);

        jdbcTemplate.update(
            "update wms_storage set lifecycle_status = 'ACTIVE', del_flag = '1' where storage_id = ?",
            STORAGE_ID_1
        );
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID,
            List.of(STORAGE_ID_1), WmsRuleContract.ERROR_SCOPE_STORAGE_NOT_FOUND, 404);

        jdbcTemplate.update(
            "insert into wms_company "
                + "(company_id, company_code, company_name, enabled, lifecycle_status, revision) "
                + "values (2, 'C-OTHER', '其他公司', 1, 'ACTIVE', 1)"
        );
        jdbcTemplate.update(
            "insert into wms_company_management_scope (company_id, scope_revision) values (2, 0)"
        );
        jdbcTemplate.update("update wms_warehouse set company_id = 2 where warehouse_id = ?",
            WAREHOUSE_ID_2);
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE,
            SCOPE_ID, List.of(STORAGE_ID_2_1),
            WmsRuleContract.ERROR_SCOPE_PARENT_CHAIN_INVALID, 409);
        assertNoEffectiveEvidence();
    }

    @Test
    void wrongZoneKindsBrokenParentsAndDeletedTargetsReject() {
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_MATERIAL_ZONE);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "update wms_warehouse_zone set zone_kind = 'LEGACY' where zone_id = ?",
            MATERIAL_ZONE_ID
        )).isInstanceOf(DataAccessException.class);
        jdbcTemplate.update("update wms_warehouse_zone set parent_zone_id = null where zone_id = ?",
            DEVICE_ZONE_ID);
        WmsRulePackageVersionVo devicePackage =
            packageFor("RULE-SCOPE-DEVICE-BROKEN", WmsRuleContract.SCOPE_DEVICE_ZONE);
        assertPublishRejected(devicePackage, WmsRuleContract.SCOPE_DEVICE_ZONE,
            DEVICE_ZONE_ID, List.of(STORAGE_ID_1),
            WmsRuleContract.ERROR_SCOPE_PARENT_CHAIN_INVALID, 409);

        jdbcTemplate.update("update wms_warehouse_zone set parent_zone_id = ? where zone_id = ?",
            MATERIAL_ZONE_ID, DEVICE_ZONE_ID);
        jdbcTemplate.update("update wms_storage set zone_id = ? where storage_id = ?",
            MATERIAL_ZONE_ID, STORAGE_ID_1);
        WmsRulePackageVersionVo storagePackage =
            packageFor("RULE-SCOPE-STORAGE-WRONG-KIND", WmsRuleContract.SCOPE_STORAGE);
        assertPublishRejected(storagePackage, WmsRuleContract.SCOPE_STORAGE,
            STORAGE_ID_1, List.of(STORAGE_ID_1),
            WmsRuleContract.ERROR_SCOPE_PARENT_CHAIN_INVALID, 409);

        jdbcTemplate.update("update wms_warehouse_zone set del_flag = '1' where zone_id = ?",
            MATERIAL_ZONE_ID);
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_MATERIAL_ZONE,
            MATERIAL_ZONE_ID, List.of(STORAGE_ID_1),
            WmsRuleContract.ERROR_SCOPE_TARGET_NOT_FOUND, 404);
    }

    @Test
    void disabledCompanyCanConfigureButRetiredCompanyTargetRejects() {
        assertPublished("RULE-SCOPE-DISABLED", WmsRuleContract.SCOPE_COMPANY,
            COMPANY_ID, List.of());
        jdbcTemplate.update("update wms_company set lifecycle_status = 'RETIRING' where company_id = ?",
            COMPANY_ID);
        assertThat(queryService.getEffective(WmsRuleContract.SCOPE_COMPANY, COMPANY_ID))
            .isNotNull();
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_COMPANY);
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_COMPANY,
            COMPANY_ID, List.of(), WmsRuleContract.ERROR_SCOPE_TARGET_RETIRED, 409);
    }

    @Test
    void retiredWarehouseTargetOrStorageParentWarehouseRejects() {
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_WAREHOUSE);
        jdbcTemplate.update(
            "update wms_warehouse set lifecycle_status = 'RETIRING' where warehouse_id = ?",
            SCOPE_ID
        );
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE,
            SCOPE_ID, List.of(), WmsRuleContract.ERROR_SCOPE_TARGET_RETIRED, 409);

        jdbcTemplate.update(
            "update wms_warehouse set lifecycle_status = 'ACTIVE' where warehouse_id = ?",
            SCOPE_ID
        );
        jdbcTemplate.update(
            "update wms_warehouse set lifecycle_status = 'RETIRED' where warehouse_id = ?",
            WAREHOUSE_ID_2
        );
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE,
            SCOPE_ID, List.of(STORAGE_ID_2_1), WmsRuleContract.ERROR_SCOPE_TARGET_RETIRED, 409);
    }

    @Test
    void companyGrantIsCheckedFromDatabaseAndRevocationBlocksReplayAndQueryOnlyDoesNotPublish() {
        grantRole(2L, 2L, WmsRuleContract.PERMISSION_PUBLISH);
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_COMPANY);
        WmsRuleTestAuth.login(2L, Set.of(WmsRuleContract.PERMISSION_PUBLISH));
        assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_COMPANY,
            COMPANY_ID, List.of(), WmsRuleContract.ERROR_SCOPE_COMPANY_DENIED, 403);

        grantCompany(2L, COMPANY_ID);
        String key = newCommandKey("scope-replay");
        WmsRuleEffectiveVo published = publish(packageVersion.packageVersionId(),
            WmsRuleContract.SCOPE_COMPANY, COMPANY_ID, List.of(), key);
        assertThat(published.pointer().scopeType()).isEqualTo(WmsRuleContract.SCOPE_COMPANY);

        WmsRulePackageVersionVo packageAfterRevoke = createPackage(
            packageVersion.packageCode(),
            Map.of(
                fixturePurpose(WmsRuleContract.SCOPE_COMPANY),
                packageVersion.members().get(0).ruleVersionId()
            )
        );
        jdbcTemplate.update("delete from wms_company_management_grant where role_id = 2");
        assertPublishRejected(packageAfterRevoke, WmsRuleContract.SCOPE_COMPANY,
            COMPANY_ID, List.of(), WmsRuleContract.ERROR_SCOPE_COMPANY_DENIED, 403);
        assertThatThrownBy(() -> publish(packageVersion.packageVersionId(),
            WmsRuleContract.SCOPE_COMPANY, COMPANY_ID, List.of(), key))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getCode()).isEqualTo(403);
                assertThat(error.getMessage()).startsWith(WmsRuleContract.ERROR_SCOPE_COMPANY_DENIED);
            });
    }

    @Test
    void queryOnlyReadsButCannotPublishAndPublishOnlyDoesNotNeedQuery() {
        grantRole(3L, 3L, WmsRuleContract.PERMISSION_PUBLISH);
        grantCompany(3L, COMPANY_ID);
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_WAREHOUSE);
        WmsRuleTestAuth.login(3L, Set.of(WmsRuleContract.PERMISSION_PUBLISH));
        publish(packageVersion.packageVersionId(), WmsRuleContract.SCOPE_WAREHOUSE,
            SCOPE_ID, List.of());
        assertThatThrownBy(() -> queryService.getEffective(
            WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID))
            .isInstanceOf(NotPermissionException.class);

        grantRole(4L, 4L, WmsRuleContract.PERMISSION_QUERY);
        grantCompany(4L, COMPANY_ID);
        WmsRuleTestAuth.login(4L, Set.of(WmsRuleContract.PERMISSION_QUERY));
        assertThat(queryService.getEffective(WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID))
            .isNotNull();
        assertThatThrownBy(() -> publish(packageVersion.packageVersionId(),
            WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID, List.of()))
            .isInstanceOf(NotPermissionException.class);
    }

    @Test
    void historyReadAllowsRetiredAndRemountedStorageInSameCompanyButNewPublishRequiresDescendant() {
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_WAREHOUSE);
        WmsRuleEffectiveVo effective = publish(packageVersion.packageVersionId(),
            WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID, List.of(STORAGE_ID_1));
        assertThat(effective.logicalScopeVersion().storageIds()).containsExactly(STORAGE_ID_1);

        jdbcTemplate.update(
            "update wms_storage set zone_id = ?, warehouse_id = ?, lifecycle_status = 'RETIRED' "
                + "where storage_id = ?",
            DEVICE_ZONE_ID + 10, WAREHOUSE_ID_2, STORAGE_ID_1
        );
        jdbcTemplate.update(
            "update wms_warehouse set lifecycle_status = 'RETIRING' where warehouse_id = ?",
            WAREHOUSE_ID_2
        );
        assertThat(queryService.getEffective(WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID))
            .isNotNull();
        jdbcTemplate.update(
            "update wms_warehouse set lifecycle_status = 'ACTIVE' where warehouse_id = ?",
            WAREHOUSE_ID_2
        );
        WmsRulePackageVersionVo next = packageFor("RULE-HISTORY-NEXT", WmsRuleContract.SCOPE_WAREHOUSE);
        assertPublishRejected(next, WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID,
            List.of(STORAGE_ID_1), WmsRuleContract.ERROR_SCOPE_STORAGE_RETIRED, 409);
        jdbcTemplate.update(
            "update wms_storage set lifecycle_status = 'ACTIVE' where storage_id = ?",
            STORAGE_ID_1
        );
        assertPublishRejected(next, WmsRuleContract.SCOPE_WAREHOUSE, SCOPE_ID,
            List.of(STORAGE_ID_1), WmsRuleContract.ERROR_SCOPE_STORAGE_OUT_OF_SCOPE, 409);
    }

    @Test
    void crossCompanyHistoricalStorageRejects() {
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_COMPANY);
        publish(packageVersion.packageVersionId(), WmsRuleContract.SCOPE_COMPANY,
            COMPANY_ID, List.of(STORAGE_ID_1));
        jdbcTemplate.update("update wms_storage set warehouse_id = ? where storage_id = ?",
            WAREHOUSE_ID_2, STORAGE_ID_1);
        jdbcTemplate.update(
            "insert into wms_company "
                + "(company_id, company_code, company_name, enabled, lifecycle_status, revision) "
                + "values (2, 'C-OTHER', '其他公司', 1, 'ACTIVE', 1)"
        );
        jdbcTemplate.update("update wms_warehouse set company_id = 2 where warehouse_id = ?",
            WAREHOUSE_ID_2);
        assertThatThrownBy(() -> queryService.getEffective(
            WmsRuleContract.SCOPE_COMPANY, COMPANY_ID))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).startsWith(
                    WmsRuleContract.ERROR_SCOPE_PARENT_CHAIN_INVALID));
    }

    @Test
    void missingZoneKindOrZoneOrStorageLifecycleColumnFailsSchemaClosed() {
        WmsRulePackageVersionVo packageVersion = packageFor(WmsRuleContract.SCOPE_WAREHOUSE);
        jdbcTemplate.execute("alter table wms_warehouse_zone drop check ck_wms_zone_kind");
        try {
            jdbcTemplate.execute(
                "alter table wms_warehouse_zone change column zone_kind "
                    + "zone_kind_guard_missing varchar(16) null"
            );
            assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE,
                SCOPE_ID, List.of(), WmsRuleContract.ERROR_SCOPE_SCHEMA_UNSUPPORTED, 409);
        } finally {
            jdbcTemplate.execute(
                "alter table wms_warehouse_zone change column zone_kind_guard_missing "
                    + "zone_kind varchar(16) collate utf8mb4_0900_bin not null"
            );
            jdbcTemplate.execute(
                "alter table wms_warehouse_zone add constraint ck_wms_zone_kind "
                    + "check (zone_kind in ('MATERIAL','DEVICE'))"
            );
        }
        jdbcTemplate.execute(
            "alter table wms_warehouse_zone drop check ck_wms_warehouse_zone_lifecycle_status"
        );
        try {
            jdbcTemplate.execute(
                "alter table wms_warehouse_zone change column lifecycle_status "
                    + "lifecycle_status_guard_missing varchar(16) null"
            );
            assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE,
                SCOPE_ID, List.of(), WmsRuleContract.ERROR_SCOPE_SCHEMA_UNSUPPORTED, 409);
        } finally {
            jdbcTemplate.execute(
                "alter table wms_warehouse_zone change column lifecycle_status_guard_missing "
                    + "lifecycle_status varchar(16) collate utf8mb4_0900_bin not null "
                    + "default 'ACTIVE'"
            );
            jdbcTemplate.execute(
                "alter table wms_warehouse_zone add constraint "
                    + "ck_wms_warehouse_zone_lifecycle_status "
                    + "check (lifecycle_status in ('ACTIVE','RETIRING','RETIRED'))"
            );
        }
        jdbcTemplate.execute(
            "alter table wms_storage drop check ck_wms_storage_lifecycle_status"
        );
        try {
            jdbcTemplate.execute(
                "alter table wms_storage change column lifecycle_status "
                    + "lifecycle_status_guard_missing varchar(16) null"
            );
            assertPublishRejected(packageVersion, WmsRuleContract.SCOPE_WAREHOUSE,
                SCOPE_ID, List.of(), WmsRuleContract.ERROR_SCOPE_SCHEMA_UNSUPPORTED, 409);
        } finally {
            jdbcTemplate.execute(
                "alter table wms_storage change column lifecycle_status_guard_missing "
                    + "lifecycle_status varchar(16) collate utf8mb4_0900_bin not null "
                    + "default 'ACTIVE'"
            );
            jdbcTemplate.execute(
                "alter table wms_storage add constraint ck_wms_storage_lifecycle_status "
                    + "check (lifecycle_status in ('ACTIVE','RETIRING','RETIRED'))"
            );
        }
    }

    @Test
    void zoneLifecycleAppliesToTargetsAncestorsAndHistoricalReads() {
        WmsRulePackageVersionVo published = packageFor("RULE-ZONE-HISTORY", SCOPE_TYPE);
        publish(published.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(STORAGE_ID_1));
        WmsRulePackageVersionVo next = packageFor("RULE-ZONE-NEXT", SCOPE_TYPE);
        for (long zoneId : List.of(MATERIAL_ZONE_ID, DEVICE_ZONE_ID)) {
            for (String state : List.of("RETIRING", "RETIRED")) {
                jdbcTemplate.update(
                    "update wms_warehouse_zone set lifecycle_status = ? where zone_id = ?",
                    state, zoneId
                );
                assertThat(queryService.getEffective(SCOPE_TYPE, SCOPE_ID)).isNotNull();
                assertPublishRejected(next, SCOPE_TYPE, SCOPE_ID, List.of(STORAGE_ID_1),
                    WmsRuleContract.ERROR_SCOPE_TARGET_RETIRED, 409);
            }
            jdbcTemplate.update(
                "update wms_warehouse_zone set lifecycle_status = 'ACTIVE' where zone_id = ?",
                zoneId
            );
        }
        for (String type : List.of(WmsRuleContract.SCOPE_MATERIAL_ZONE,
            WmsRuleContract.SCOPE_DEVICE_ZONE)) {
            long targetId = WmsRuleContract.SCOPE_MATERIAL_ZONE.equals(type)
                ? MATERIAL_ZONE_ID : DEVICE_ZONE_ID;
            WmsRulePackageVersionVo targetPackage = packageFor("RULE-ZONE-TARGET-" + type, type);
            jdbcTemplate.update(
                "update wms_warehouse_zone set lifecycle_status = 'RETIRED' where zone_id = ?",
                targetId
            );
            assertPublishRejected(targetPackage, type, targetId, List.of(),
                WmsRuleContract.ERROR_SCOPE_TARGET_RETIRED, 409);
            jdbcTemplate.update(
                "update wms_warehouse_zone set lifecycle_status = 'ACTIVE' where zone_id = ?",
                targetId
            );
        }
        assertThatThrownBy(() -> jdbcTemplate.update(
            "update wms_warehouse_zone set lifecycle_status = 'UNKNOWN' where zone_id = ?",
            DEVICE_ZONE_ID
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void nullAndUnknownLifecycleValuesRejectWithStable409AcrossThePhysicalTree() {
        WmsRulePackageVersionVo first = packageFor("RULE-NULL-HISTORY", SCOPE_TYPE);
        publish(first.packageVersionId(), SCOPE_TYPE, SCOPE_ID, List.of(STORAGE_ID_1));
        WmsRulePackageVersionVo next = packageFor("RULE-NULL-NEXT", SCOPE_TYPE);
        for (String table : List.of("wms_company", "wms_warehouse",
            "wms_warehouse_zone", "wms_storage")) {
            String idColumn = switch (table) {
                case "wms_company" -> "company_id";
                case "wms_warehouse" -> "warehouse_id";
                case "wms_warehouse_zone" -> "zone_id";
                default -> "storage_id";
            };
            long id = switch (table) {
                case "wms_company" -> COMPANY_ID;
                case "wms_warehouse" -> SCOPE_ID;
                case "wms_warehouse_zone" -> DEVICE_ZONE_ID;
                default -> STORAGE_ID_1;
            };
            String collation = table.equals("wms_company") || table.equals("wms_warehouse")
                ? "utf8mb4_bin" : "utf8mb4_0900_bin";
            String lifecycleCheck = switch (table) {
                case "wms_warehouse" -> "ck_wms_warehouse_lifecycle_status";
                case "wms_warehouse_zone" -> "ck_wms_warehouse_zone_lifecycle_status";
                case "wms_storage" -> "ck_wms_storage_lifecycle_status";
                default -> null;
            };
            if (lifecycleCheck != null) {
                jdbcTemplate.execute("alter table " + table + " drop check " + lifecycleCheck);
            }
            // 隔离 fixture 放宽 NOT NULL，验证 Guard 不只依赖数据库列声明就拒绝非法事实。
            jdbcTemplate.execute("alter table " + table
                + " modify column lifecycle_status varchar(16) collate " + collation + " null");
            try {
                for (int state = 0; state < 2; state++) {
                    jdbcTemplate.update("update " + table + " set lifecycle_status = ? where "
                        + idColumn + " = ?", state == 0 ? null : "UNKNOWN", id);
                    assertThatThrownBy(() -> queryService.getEffective(SCOPE_TYPE, SCOPE_ID))
                        .isInstanceOfSatisfying(ServiceException.class, error -> {
                            assertThat(error.getCode()).isEqualTo(409);
                            assertThat(error.getMessage()).startsWith(
                                WmsRuleContract.ERROR_SCOPE_PARENT_CHAIN_INVALID);
                        });
                    assertPublishRejected(next, SCOPE_TYPE, SCOPE_ID, List.of(STORAGE_ID_1),
                        table.equals("wms_storage") ? WmsRuleContract.ERROR_SCOPE_STORAGE_RETIRED
                            : WmsRuleContract.ERROR_SCOPE_TARGET_RETIRED, 409);
                }
            } finally {
                jdbcTemplate.update("update " + table + " set lifecycle_status = 'ACTIVE' where "
                    + idColumn + " = ?", id);
                jdbcTemplate.execute("alter table " + table
                    + " modify column lifecycle_status varchar(16) collate " + collation
                    + " not null default 'ACTIVE'");
                if (lifecycleCheck != null) {
                    jdbcTemplate.execute("alter table " + table + " add constraint "
                        + lifecycleCheck
                        + " check (lifecycle_status in ('ACTIVE','RETIRING','RETIRED'))");
                }
            }
        }
    }

    private void assertPublished(String ruleKey, String scopeType, long scopeId,
                                 List<Long> storageIds) {
        WmsRulePackageVersionVo packageVersion = packageFor(ruleKey, scopeType);
        WmsRuleEffectiveVo effective = publish(packageVersion.packageVersionId(),
            scopeType, scopeId, storageIds);
        assertThat(effective.pointer().scopeType()).isEqualTo(scopeType);
        assertThat(effective.pointer().scopeId()).isEqualTo(scopeId);
    }

    private WmsRulePackageVersionVo packageFor(String scopeType) {
        return packageFor("RULE-" + scopeType, scopeType);
    }

    private WmsRulePackageVersionVo packageFor(String ruleKey, String scopeType) {
        String purpose = fixturePurpose(scopeType);
        WmsRuleDefinitionVo definition = createDefinition(ruleKey, purpose, scopeType);
        WmsRuleVersionVo version = activate(definition.versions().get(0));
        return createPackage("PKG-" + ruleKey, Map.of(purpose, version.ruleVersionId()));
    }

    private void assertPublishRejected(WmsRulePackageVersionVo packageVersion, String scopeType,
                                       long scopeId, List<Long> storageIds,
                                       String errorCode, int status) {
        assertThatThrownBy(() -> publish(packageVersion.packageVersionId(), scopeType,
            scopeId, storageIds))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith(errorCode);
                assertThat(error.getCode()).isEqualTo(status);
            });
    }

    private void assertNoEffectiveEvidence() {
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope_version", "1 = 1")).isZero();
    }
}
