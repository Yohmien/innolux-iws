/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录集成测试
 * @Version      : V1.0
 * @Description  : 验证空目录、停用历史、调度解析、合同版本与新挂载拒绝；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.exception.NotPermissionException;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.catalog.controller.WmsInventoryCatalogController;
import com.innolux.iws.wms.catalog.domain.WmsBusinessPlanMountCatalogVo;
import com.innolux.iws.wms.catalog.domain.WmsInventoryCatalogVo;
import com.innolux.iws.wms.catalog.domain.WmsInventoryMountValidationVo;
import com.innolux.iws.wms.catalog.service.WmsInventoryCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Set;

import static com.innolux.iws.wms.catalog.WmsInventoryCatalogContract.CONTRACT_VERSION;
import static com.innolux.iws.wms.catalog.WmsInventoryCatalogContract.ERROR_MOUNT_NOT_ALLOWED;
import static com.innolux.iws.wms.catalog.WmsInventoryCatalogContract.ERROR_PARENT_TYPE_NOT_REGISTERED;
import static com.innolux.iws.wms.catalog.WmsInventoryCatalogContract.PERMISSION_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存业务目录真实 MySQL 集成测试。
 *
 * @author YONGJIE.PENG
 */
@Tag("local")
@SpringJUnitConfig(WmsCatalogTestContext.class)
class WmsInventoryCatalogIntegrationTest {

    private static final long BUSINESS_TYPE_MOUNT = 9001L;

    private static final long SCHEDULE_ID = 8001L;

    private static final long PLAN_TYPE_ID = 8101L;

    @Autowired
    private WmsInventoryCatalogService catalogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void installSaToken() {
        WmsCatalogTestAuth.install();
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("delete from wms_business_plan_mount");
        jdbcTemplate.update("delete from wms_business_type");
        jdbcTemplate.update("delete from iws_business_schedule_plan_type");
        jdbcTemplate.update("delete from iws_business_schedule");
        WmsCatalogTestAuth.loginAsAdmin();
    }

    @AfterEach
    void clearSaToken() {
        WmsCatalogTestAuth.clear();
    }

    @Test
    void emptyCatalogRequiresInventoryBusinessQueryPermission() throws Exception {
        WmsInventoryCatalogVo empty = catalogService.getCatalog();
        assertThat(empty.contractVersion()).isEqualTo(CONTRACT_VERSION);
        assertThat(empty.businessTypes()).isEmpty();
        assertThat(empty.mounts()).isEmpty();

        WmsCatalogTestAuth.login(2L, Set.of());
        assertThatThrownBy(() -> catalogService.getCatalog())
            .isInstanceOf(NotPermissionException.class);

        Method catalog = WmsInventoryCatalogController.class.getMethod("catalog");
        RequestMapping basePath = WmsInventoryCatalogController.class.getAnnotation(RequestMapping.class);
        GetMapping route = catalog.getAnnotation(GetMapping.class);
        SaCheckPermission permission = catalog.getAnnotation(SaCheckPermission.class);
        assertThat(basePath.value()).containsExactly("/wms/inventory");
        assertThat(route.value()).containsExactly("/catalog");
        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly(PERMISSION_QUERY);
    }

    @Test
    void activeMountResolvesExistingScheduleAndPlanType() {
        seedBusinessType("MATERIAL_CHANGE", "ACTIVE", CONTRACT_VERSION);
        seedSchedule("MC_SCHEDULE", 1, "0");
        seedPlanType("MATERIAL_CHANGE", "物料变更", 1);
        seedMount("MATERIAL_CHANGE", "MATERIAL_CHANGE", "MC_SCHEDULE", 1, CONTRACT_VERSION);

        WmsInventoryCatalogVo catalog = catalogService.getCatalog();
        assertThat(catalog.businessTypes()).singleElement()
            .satisfies(type -> {
                assertThat(type.businessTypeCode()).isEqualTo("MATERIAL_CHANGE");
                assertThat(type.status()).isEqualTo("ACTIVE");
            });
        WmsBusinessPlanMountCatalogVo mount = catalog.mounts().get(0);
        assertThat(mount.planTypeName()).isEqualTo("物料变更");
        assertThat(mount.scheduleCode()).isEqualTo("MC_SCHEDULE");
        assertThat(mount.allowMount()).isTrue();

        WmsInventoryMountValidationVo decision = catalogService.requireMountAllowed(
            "MATERIAL_CHANGE", "MATERIAL_CHANGE", "MC_SCHEDULE", CONTRACT_VERSION
        );
        assertThat(decision.scheduleCode()).isEqualTo("MC_SCHEDULE");
        assertThat(decision.contractVersion()).isEqualTo(CONTRACT_VERSION);
    }

    @Test
    void disabledTypeAndMountRemainReadableForHistoryButRejectAdmission() {
        seedBusinessType("MATERIAL_CHANGE", "ACTIVE", CONTRACT_VERSION);
        seedSchedule("MC_SCHEDULE", 1, "0");
        seedPlanType("MATERIAL_CHANGE", "物料变更", 1);
        seedMount("MATERIAL_CHANGE", "MATERIAL_CHANGE", "MC_SCHEDULE", 1, CONTRACT_VERSION);

        jdbcTemplate.update("update wms_business_type set status = 'DISABLED' where business_type_code = 'MATERIAL_CHANGE'");
        jdbcTemplate.update("update wms_business_plan_mount set enabled = 0 where business_type_code = 'MATERIAL_CHANGE'");

        WmsInventoryCatalogVo catalog = catalogService.getCatalog();
        assertThat(catalog.businessTypes()).singleElement()
            .satisfies(type -> assertThat(type.status()).isEqualTo("DISABLED"));
        assertThat(catalog.mounts()).singleElement()
            .satisfies(mount -> {
                assertThat(mount.enabled()).isFalse();
                assertThat(mount.allowMount()).isFalse();
                assertThat(mount.planTypeName()).isEqualTo("物料变更");
            });

        assertAdmissionRejected(ERROR_PARENT_TYPE_NOT_REGISTERED);

        jdbcTemplate.update("update wms_business_type set status = 'ACTIVE' where business_type_code = 'MATERIAL_CHANGE'");
        assertThat(catalogService.getCatalog().mounts().get(0).allowMount()).isFalse();
        assertAdmissionRejected(ERROR_MOUNT_NOT_ALLOWED);
    }

    @Test
    void disabledScheduleOrPlanTypePreservesRowButBlocksAdmission() {
        seedBusinessType("MATERIAL_CHANGE", "ACTIVE", CONTRACT_VERSION);
        seedSchedule("MC_SCHEDULE", 0, "0");
        seedPlanType("MATERIAL_CHANGE", "物料变更", 1);
        seedMount("MATERIAL_CHANGE", "MATERIAL_CHANGE", "MC_SCHEDULE", 1, CONTRACT_VERSION);

        assertThat(catalogService.getCatalog().mounts().get(0).allowMount()).isFalse();
        assertAdmissionRejected(ERROR_MOUNT_NOT_ALLOWED);

        jdbcTemplate.update("update iws_business_schedule set enabled = 1 where id = ?", SCHEDULE_ID);
        jdbcTemplate.update("update iws_business_schedule_plan_type set enabled = 0 where id = ?", PLAN_TYPE_ID);
        assertThat(catalogService.getCatalog().mounts().get(0).allowMount()).isFalse();
        assertAdmissionRejected(ERROR_MOUNT_NOT_ALLOWED);

        jdbcTemplate.update("update iws_business_schedule_plan_type set enabled = 1 where id = ?", PLAN_TYPE_ID);
        jdbcTemplate.update("update iws_business_schedule set del_flag = '1' where id = ?", SCHEDULE_ID);
        assertThat(catalogService.getCatalog().mounts().get(0).allowMount()).isFalse();
        assertAdmissionRejected(ERROR_MOUNT_NOT_ALLOWED);
    }

    @Test
    void unknownMountAndContractVersionMismatchAreRejected() {
        assertThatThrownBy(() -> catalogService.requireMountAllowed(
            "UNKNOWN", "MATERIAL_CHANGE", "MC_SCHEDULE", CONTRACT_VERSION
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith(ERROR_PARENT_TYPE_NOT_REGISTERED);
            assertThat(error.getCode()).isEqualTo(409);
        });

        seedBusinessType("MATERIAL_CHANGE", "ACTIVE", CONTRACT_VERSION);
        seedSchedule("MC_SCHEDULE", 1, "0");
        seedPlanType("MATERIAL_CHANGE", "物料变更", 1);
        assertAdmissionRejected(ERROR_MOUNT_NOT_ALLOWED);
        seedMount("MATERIAL_CHANGE", "MATERIAL_CHANGE", "MC_SCHEDULE", 1, "2");
        assertThat(catalogService.getCatalog().mounts().get(0).allowMount()).isFalse();
        assertAdmissionRejected(ERROR_MOUNT_NOT_ALLOWED);

        jdbcTemplate.update("update wms_business_plan_mount set contract_version = ? where mount_id = ?",
            CONTRACT_VERSION, BUSINESS_TYPE_MOUNT);
        assertThatThrownBy(() -> catalogService.requireMountAllowed(
            "MATERIAL_CHANGE", "MATERIAL_CHANGE", "MC_SCHEDULE", "2"
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith(ERROR_MOUNT_NOT_ALLOWED);
            assertThat(error.getCode()).isEqualTo(409);
        });
    }

    private void assertAdmissionRejected(String errorCode) {
        assertThatThrownBy(() -> catalogService.requireMountAllowed(
            "MATERIAL_CHANGE", "MATERIAL_CHANGE", "MC_SCHEDULE", CONTRACT_VERSION
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith(errorCode);
            assertThat(error.getCode()).isEqualTo(409);
        });
    }

    private void seedBusinessType(String code, String status, String contractVersion) {
        jdbcTemplate.update(
            "insert into wms_business_type (business_type_code, business_type_name, status, "
                + "contract_version, object_policy_key, revision) values (?, ?, ?, ?, ?, 1)",
            code, "业务" + code, status, contractVersion, "WMS_OBJECT_" + code
        );
    }

    private void seedSchedule(String businessCode, int enabled, String delFlag) {
        jdbcTemplate.update(
            "insert into iws_business_schedule (id, business_code, business_name, enabled, "
                + "revision, del_flag, create_time, update_time) values (?, ?, ?, ?, 1, ?, now(3), now(3))",
            SCHEDULE_ID, businessCode, "调度" + businessCode, enabled, delFlag
        );
    }

    private void seedPlanType(String planType, String planTypeName, int enabled) {
        jdbcTemplate.update(
            "insert into iws_business_schedule_plan_type (id, schedule_id, plan_type, plan_type_name, "
                + "priority, enabled, create_time, update_time) values (?, ?, ?, ?, 0, ?, now(3), now(3))",
            PLAN_TYPE_ID, SCHEDULE_ID, planType, planTypeName, enabled
        );
    }

    private void seedMount(String businessTypeCode, String planTypeCode, String scheduleCode,
                           int enabled, String contractVersion) {
        jdbcTemplate.update(
            "insert into wms_business_plan_mount (mount_id, business_type_code, plan_type_code, "
                + "schedule_code, contract_version, enabled, revision) values (?, ?, ?, ?, ?, ?, 1)",
            BUSINESS_TYPE_MOUNT, businessTypeCode, planTypeCode, scheduleCode, contractVersion, enabled
        );
    }
}
