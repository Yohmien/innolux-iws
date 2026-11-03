/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型集成测试
 * @Version      : V1.0
 * @Description  : 验证空间查询的跨公司范围、权限、分页、精确地址和父链阻碍；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.space;

import cn.dev33.satoken.exception.NotPermissionException;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.bo.IwsQueryConditionBo;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.bo.WmsStorageLocateBo;
import com.innolux.iws.wms.domain.vo.WmsStorageVo;
import com.innolux.iws.wms.domain.vo.WmsWarehouseVo;
import com.innolux.iws.wms.domain.vo.WmsWarehouseZoneVo;
import com.innolux.iws.wms.service.IWmsSpaceQueryService;
import com.innolux.iws.wms.service.WmsSpaceContract;
import com.innolux.iws.wms.service.WmsSpaceReadinessGuard;
import com.innolux.iws.wms.support.company.WmsCompanyContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 空间只读查询的真实 MySQL 集成测试。
 *
 * <p>只使用一次性容器和测试库，不访问产品数据源；测试不提供任何空间写入 API。</p>
 *
 * @author YONGJIE.PENG
 */
@Tag("local")
@SpringJUnitConfig(WmsSpaceTestContext.class)
class WmsSpaceQueryIntegrationTest {

    private static final long ROLE_VIEWER = 100L;

    private static final long ROLE_SECOND = 200L;

    private static final long USER_VIEWER = 2L;

    private static final long WAREHOUSE_MENU = 9001L;

    private static final long ZONE_MENU = 9002L;

    private static final long STORAGE_MENU = 9003L;

    private static final long SCOPE_ALL_MENU = 9004L;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private IWmsSpaceQueryService spaceQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WmsSpaceReadinessGuard readinessGuard;

    @BeforeAll
    static void installSaToken() {
        WmsSpaceTestAuth.install();
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("delete from wms_storage");
        jdbcTemplate.update("delete from wms_warehouse_zone");
        jdbcTemplate.update("delete from wms_warehouse");
        jdbcTemplate.update("delete from wms_company_management_grant");
        jdbcTemplate.update("delete from sys_role_menu");
        jdbcTemplate.update("delete from sys_menu");
        jdbcTemplate.update("delete from sys_user_role");
        jdbcTemplate.update("delete from sys_role");
        jdbcTemplate.update("delete from wms_company");
        insertRole(ROLE_VIEWER);
        insertRole(ROLE_SECOND);
    }

    @AfterEach
    void clearSaToken() {
        WmsSpaceTestAuth.clear();
    }

    @Test
    void warehouseZoneAndStoragePagesApplyCompanyGrantCrossCompanyIsolation() {
        seedTwoCompanies();
        grantRoleCompany(ROLE_VIEWER, 1L);
        grantAllSpacePermissions(ROLE_VIEWER);
        loginViewer();

        PageResult<WmsWarehouseVo> warehouses = spaceQueryService.selectWarehousePage(new IwsTableQueryBo());
        assertThat(warehouses.getTotal()).isEqualTo(2L);
        assertThat(warehouses.getRows())
            .extracting(WmsWarehouseVo::getWarehouseCode)
            .containsExactly("W-A1", "W-A2");

        PageResult<WmsWarehouseZoneVo> zones = spaceQueryService.selectZonePage(new IwsTableQueryBo());
        assertThat(zones.getTotal()).isEqualTo(2L);
        assertThat(zones.getRows())
            .extracting(WmsWarehouseZoneVo::getZoneCode)
            .containsExactly("Z-A1", "Z-A2");

        PageResult<WmsStorageVo> storages = spaceQueryService.selectStoragePage(new IwsTableQueryBo());
        assertThat(storages.getTotal()).isEqualTo(2L);
        assertThat(storages.getRows())
            .extracting(WmsStorageVo::getStorageCode)
            .containsExactly("S-A1", "S-A2");

        assertThat(spaceQueryService.locateStorage(locate(201L, "B1", null, "B-L", "01", "D1"))
            .getTotal()).isZero();
    }

    @Test
    void scopeAllAllowsAllCompaniesWithoutCompanyGrantAndRevocationHidesRows() {
        seedTwoCompanies();
        grantMenu(ROLE_VIEWER, WmsSpaceContract.PERMISSION_WAREHOUSE_QUERY, WAREHOUSE_MENU);
        grantMenu(ROLE_VIEWER, WmsCompanyContract.PERMISSION_SCOPE_ALL, SCOPE_ALL_MENU);
        loginViewer();

        assertThat(spaceQueryService.selectWarehousePage(new IwsTableQueryBo()).getTotal()).isEqualTo(3L);
        assertThat(spaceQueryService.selectWarehousePage(new IwsTableQueryBo()).getRows())
            .extracting(WmsWarehouseVo::getWarehouseCode)
            .containsExactly("W-A1", "W-A2", "W-B1");

        jdbcTemplate.update("delete from sys_role_menu where role_id = ? and menu_id = ?",
            ROLE_VIEWER, SCOPE_ALL_MENU);
        assertThat(spaceQueryService.selectWarehousePage(new IwsTableQueryBo()).getTotal()).isZero();
    }

    @Test
    void companyGrantRevocationAndWarehousePermissionRevocationTakeEffectOnNextRequest() {
        seedTwoCompanies();
        grantRoleCompany(ROLE_VIEWER, 1L);
        grantAllSpacePermissions(ROLE_VIEWER);
        loginViewer();

        assertThat(spaceQueryService.selectWarehousePage(new IwsTableQueryBo()).getTotal()).isEqualTo(2L);
        jdbcTemplate.update("delete from wms_company_management_grant where role_id = ?", ROLE_VIEWER);
        assertThat(spaceQueryService.selectWarehousePage(new IwsTableQueryBo()).getTotal()).isZero();

        grantRoleCompany(ROLE_VIEWER, 1L);
        jdbcTemplate.update("delete from sys_role_menu where role_id = ? and menu_id = ?",
            ROLE_VIEWER, WAREHOUSE_MENU);
        assertThatThrownBy(() -> spaceQueryService.selectWarehousePage(new IwsTableQueryBo()))
            .isInstanceOf(NotPermissionException.class);
        assertThat(spaceQueryService.selectZonePage(new IwsTableQueryBo()).getTotal()).isEqualTo(2L);
        assertThat(spaceQueryService.selectStoragePage(new IwsTableQueryBo()).getTotal()).isEqualTo(2L);
    }

    @Test
    void paginationReturnsTotalAndStableWarehouseOrder() {
        insertCompany(1L, "C-A");
        for (int index = 1; index <= 5; index++) {
            insertWarehouse(100L + index, 1L, "W-00" + index, "仓库" + index);
        }
        grantRoleCompany(ROLE_VIEWER, 1L);
        grantAllSpacePermissions(ROLE_VIEWER);
        loginViewer();

        IwsTableQueryBo query = new IwsTableQueryBo();
        query.setPageNum(2);
        query.setPageSize(2);
        PageResult<WmsWarehouseVo> page = spaceQueryService.selectWarehousePage(query);

        assertThat(page.getTotal()).isEqualTo(5L);
        assertThat(page.getRows()).hasSize(2);
        assertThat(page.getRows())
            .extracting(WmsWarehouseVo::getWarehouseCode)
            .containsExactly("W-003", "W-004");
    }

    @Test
    void locateDistinguishesNullEmptyAsciiCaseLeadingZeroAndUnicode() {
        seedLocatableStorage();
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertThat(spaceQueryService.locateStorage(
            locate(101L, " A1 ", null, "L-01", "01", "D-01")
        ).getTotal()).isEqualTo(1L);
        assertThat(spaceQueryService.locateStorage(
            locate(101L, "A1", "   ", "L-01", "01", "D-01")
        ).getTotal()).isEqualTo(1L);
        assertThat(spaceQueryService.locateStorage(
            locate(101L, "a1", null, "L-01", "01", "D-01")
        ).getTotal()).isZero();
        assertThat(spaceQueryService.locateStorage(
            locate(101L, "A1", null, "L-01", "1", "D-01")
        ).getTotal()).isZero();
        assertThat(spaceQueryService.locateStorage(
            locate(101L, "\u00A0巷道-甲", null, "L-02", "02", "D-02")
        ).getTotal()).isZero();
        assertThat(spaceQueryService.locateStorage(
            locate(101L, " 巷道-甲 ", null, "L-02", "02", "D-02")
        ).getTotal()).isEqualTo(1L);

        IwsTableQueryBo emptyRowCondition = query(
            condition("rowText", "eq", "")
        );
        assertThat(spaceQueryService.selectStoragePage(emptyRowCondition).getTotal()).isZero();
        assertThat(spaceQueryService.selectStoragePage(new IwsTableQueryBo()).getTotal()).isEqualTo(2L);
    }

    @Test
    void allFiveAddressValuesCanBeExplicitlyNull() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertStorage(501L, 101L, 301L, "S-ALL-NULL", null, null, null, null, null);
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        WmsStorageLocateBo request = locate(101L, null, null, null, null, null);
        assertThat(request.getAddressFields())
            .allMatch(field -> field.isValuePresent());
        assertThat(spaceQueryService.locateStorage(request).getTotal()).isEqualTo(1L);
    }

    @Test
    void malformedAddressEntriesAndMissingValueKeyAreRejectedBeforeLookup() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        WmsStorageLocateBo explicitNull = locate(101L, "A1", null, "L-01", "01", "D-01");
        assertThat(explicitNull.getAddressFields().get(1).isValuePresent()).isTrue();
        assertThat(spaceQueryService.locateStorage(explicitNull).getTotal()).isZero();

        WmsStorageLocateBo missingValue = locateMissingValue(101L);
        assertThat(missingValue.getAddressFields().get(1).isValuePresent()).isFalse();
        assertBlocked(missingValue, WmsSpaceContract.ERROR_ADDRESS_PRESENT_MISMATCH, 400);

        assertBlocked(locateDuplicate(101L), WmsSpaceContract.ERROR_ADDRESS_PRESENT_MISMATCH, 400);
        assertBlocked(locateUnknownField(101L), WmsSpaceContract.ERROR_ADDRESS_PRESENT_MISMATCH, 400);
        assertBlocked(locateWithFieldCount(101L, 4), WmsSpaceContract.ERROR_ADDRESS_PRESENT_MISMATCH, 400);
        assertBlocked(locateWithNumberValue(101L), WmsSpaceContract.ERROR_ADDRESS_VALUE_INVALID, 400);
        assertBlocked(locateWithOverlongValue(101L), WmsSpaceContract.ERROR_ADDRESS_VALUE_INVALID, 400);
    }

    @Test
    void duplicateS1AddressIsRejectedByWarehouseUniqueIndex() {
        seedLocatableStorage();

        assertThatThrownBy(() ->
            insertStorage(503L, 101L, 301L, "S-A3", "A1", null, "L-01", "01", "D-01"))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void mismatchedStorageAndZoneWarehouseBlockOnlyTheirOwnScope() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(102L, 1L, "W-A2", "仓库A2");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertStorage(501L, 102L, 301L, "S-MISMATCHED", "A1", null, "L1", "01", "D1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertBlocked(() -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
        assertThat(spaceQueryService.selectStoragePage(
            query(condition("warehouseId", "eq", 101L))
        ).getTotal()).isZero();

        insertZone(302L, 102L, "Z-A2", "库区A2");
        assertThat(spaceQueryService.selectZonePage(
            query(condition("warehouseId", "eq", 101L))
        ).getRows()).extracting(WmsWarehouseZoneVo::getZoneCode).containsExactly("Z-A1");

        insertZone(303L, 102L, "Z-A3", "跨仓父区", 301L, "0");
        assertBlocked(() -> spaceQueryService.selectZonePage(
            query(condition("warehouseId", "eq", 102L))
        ), WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
    }

    @Test
    void foreignBadDataDoesNotBlockAuthorizedCompany() {
        insertCompany(1L, "C-A");
        insertCompany(2L, "C-B");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(201L, 2L, "W-B1", "仓库B1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertStorage(501L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");

        insertZone(401L, 201L, "Z-B1", "库区B1");
        insertWarehouse(202L, 2L, "W-B2", "仓库B2");
        insertZone(402L, 202L, "Z-B2", "B跨仓父区", 401L, "0");
        insertZone(403L, 202L, "Z-B3", "B独立库区");
        insertZone(404L, 201L, "Z-B4", "B环一", 405L, "0");
        insertZone(405L, 201L, "Z-B5", "B环二", 404L, "0");
        insertStorage(601L, 202L, 401L, "S-B1", "B1", null, "L1", "01", "D1");
        insertStorage(602L, 201L, 404L, "S-B2", "B2", null, "L1", "01", "D1");

        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertThat(spaceQueryService.selectWarehousePage(new IwsTableQueryBo()).getTotal()).isEqualTo(1L);
        assertThat(spaceQueryService.selectZonePage(new IwsTableQueryBo()).getTotal()).isEqualTo(1L);
        assertThat(spaceQueryService.selectStoragePage(new IwsTableQueryBo()).getTotal()).isEqualTo(1L);
        assertThat(spaceQueryService.locateStorage(
            locate(101L, "A1", null, "L1", "01", "D1")
        ).getTotal()).isEqualTo(1L);
    }

    @Test
    void explicitTargetIgnoresBadDataInAnotherAuthorizedWarehouse() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(102L, 1L, "W-A2", "仓库A2");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertZone(302L, 102L, "Z-A2", "库区A2");
        insertStorage(501L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");
        insertStorage(502L, 102L, 302L, "S-A2", "A2", null, "L1", "01", "D1");
        insertZone(303L, 102L, "Z-C1", "A2环一", 304L, "0");
        insertZone(304L, 102L, "Z-C2", "A2环二", 303L, "0");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertBlocked(() -> spaceQueryService.selectZonePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
        assertBlocked(() -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);

        assertThat(spaceQueryService.selectZonePage(
            query(condition("warehouseId", "eq", 101L))
        ).getRows()).extracting(WmsWarehouseZoneVo::getZoneCode).containsExactly("Z-A1");
        assertThat(spaceQueryService.selectStoragePage(
            query(condition("warehouseId", "eq", 101L))
        ).getRows()).extracting(WmsStorageVo::getStorageCode).containsExactly("S-A1");
        assertThat(spaceQueryService.selectZonePage(
            query(condition("zoneId", "eq", 301L))
        ).getRows()).extracting(WmsWarehouseZoneVo::getZoneCode).containsExactly("Z-A1");
        assertThat(spaceQueryService.selectStoragePage(
            query(condition("zoneId", "eq", 301L))
        ).getRows()).extracting(WmsStorageVo::getStorageCode).containsExactly("S-A1");
        assertThat(spaceQueryService.selectStoragePage(
            query(condition("storageId", "eq", 501L))
        ).getRows()).extracting(WmsStorageVo::getStorageCode).containsExactly("S-A1");
        assertThat(spaceQueryService.locateStorage(
            locate(101L, "A1", null, "L1", "01", "D1")
        ).getTotal()).isEqualTo(1L);
    }

    @Test
    void storageQueryDoesNotFollowForeignOrUnknownZoneAfterGuardExit() {
        insertCompany(1L, "C-A");
        insertCompany(2L, "C-B");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(201L, 2L, "W-B1", "仓库B1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertZone(401L, 201L, "Z-B1", "B-SECRET");
        insertStorage(501L, 101L, 401L, "S-LEAK", "LEAK", null, "L1", "01", "D1");
        insertStorage(502L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertThatCode(() -> readinessGuard.assertStorageReadable(
            new WmsSpaceReadinessGuard.Scope(Set.of(1L), 101L, 401L, null)
        )).doesNotThrowAnyException();

        PageResult<WmsStorageVo> warehouseZone = spaceQueryService.selectStoragePage(query(
            condition("warehouseId", "eq", 101L),
            condition("zoneId", "eq", 401L)
        ));
        assertThat(warehouseZone.getTotal()).isZero();
        assertThat(warehouseZone.getRows()).isEmpty();
        assertThat(spaceQueryService.selectStoragePage(
            query(condition("zoneId", "eq", 401L))
        ).getTotal()).isZero();
        assertThat(spaceQueryService.selectStoragePage(query(
            condition("warehouseId", "eq", 101L),
            condition("zoneId", "eq", 9999L)
        )).getTotal()).isZero();

        assertBlocked(() -> spaceQueryService.selectStoragePage(
                query(condition("storageId", "eq", 501L))),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
        assertThat(spaceQueryService.selectStoragePage(
            query(condition("storageId", "eq", 502L))
        ).getRows()).extracting(WmsStorageVo::getStorageCode).containsExactly("S-A1");
    }

    @Test
    void sameCompanyCrossWarehouseStorageIsRejectedOrFilteredByExplicitScope() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(201L, 1L, "W-A2", "仓库A2");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertZone(401L, 201L, "Z-A2", "库区A2");
        insertStorage(501L, 101L, 401L, "S-CROSS", "CROSS", null, "L1", "01", "D1");
        insertStorage(502L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertBlocked(() -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);

        assertThatCode(() -> readinessGuard.assertStorageReadable(
            new WmsSpaceReadinessGuard.Scope(Set.of(1L), 101L, 401L, null)
        )).doesNotThrowAnyException();
        assertThat(spaceQueryService.selectStoragePage(query(
            condition("warehouseId", "eq", 101L),
            condition("zoneId", "eq", 401L)
        )).getTotal()).isZero();

        assertBlocked(() -> spaceQueryService.selectStoragePage(
                query(condition("storageId", "eq", 501L))),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
        assertThat(spaceQueryService.selectStoragePage(
            query(condition("storageId", "eq", 502L))
        ).getRows()).extracting(WmsStorageVo::getStorageCode).containsExactly("S-A1");
    }

    @Test
    void deletedBadDataDoesNotAffectValidQueries() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertStorage(501L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");
        insertZone(302L, 101L, "Z-D1", "删除缺失父区", 999L, "1");
        insertZone(303L, 101L, "Z-D2", "删除独立库区", null, "1");
        insertZone(304L, 101L, "Z-D3", "删除环一", 305L, "1");
        insertZone(305L, 101L, "Z-D4", "删除环二", 304L, "1");
        insertStorage(502L, 101L, 301L, "S-D1", "D1", null, "L1", "01", "D1", "1");

        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertThat(spaceQueryService.selectZonePage(new IwsTableQueryBo()).getRows())
            .extracting(WmsWarehouseZoneVo::getZoneCode)
            .containsExactly("Z-A1");
        assertThat(spaceQueryService.selectStoragePage(new IwsTableQueryBo()).getRows())
            .extracting(WmsStorageVo::getStorageCode)
            .containsExactly("S-A1");
        assertThat(spaceQueryService.locateStorage(
            locate(101L, "A1", null, "L1", "01", "D1")
        ).getTotal()).isEqualTo(1L);
    }

    @Test
    void missingZoneParentIsRejectedWithoutParentIdentifier() {
        seedOneCompanyWithStorage();
        insertZone(302L, 101L, "Z-MISSING", "缺失父区", 999L, "0");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertChainBlockedWithout("999", () -> spaceQueryService.selectZonePage(new IwsTableQueryBo()));
        assertChainBlockedWithout("999", () -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()));
    }

    @Test
    void crossCompanyZoneParentIsRejectedWithoutLeakingParentIdentifier() {
        insertCompany(1L, "C-A");
        insertCompany(2L, "C-B");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(201L, 2L, "W-B1", "仓库B1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertZone(401L, 201L, "Z-B1", "库区B1");
        insertZone(302L, 101L, "Z-A2", "跨公司父区", 401L, "0");
        insertStorage(501L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertChainBlockedWithout("401", () -> spaceQueryService.selectZonePage(new IwsTableQueryBo()));
        assertChainBlockedWithout("401", () -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()));
    }

    @Test
    void selfParentZoneIsRejected() {
        seedOneCompanyWithStorage();
        jdbcTemplate.update("update wms_warehouse_zone set parent_zone_id = zone_id where zone_id = 301");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertBlocked(() -> spaceQueryService.selectZonePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
        assertBlocked(() -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
    }

    @Test
    void cyclicZoneParentsAreRejected() {
        seedOneCompanyWithStorage();
        insertZone(302L, 101L, "Z-C1", "环一", 303L, "0");
        insertZone(303L, 101L, "Z-C2", "环二", 302L, "0");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertBlocked(() -> spaceQueryService.selectZonePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
        assertBlocked(() -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
    }

    @Test
    void storageUnderDeletedZoneIsRejected() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertZone(301L, 101L, "Z-DELETED", "删除库区", null, "1");
        insertStorage(501L, 101L, 301L, "S-ORPHAN", "A1", null, "L1", "01", "D1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertThat(spaceQueryService.selectZonePage(new IwsTableQueryBo()).getTotal()).isZero();
        assertBlocked(() -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
    }

    @Test
    void unauthorizedOrMissingSingleTargetsRemainEmptyWhenOwnScopeIsBlocked() {
        insertCompany(1L, "C-A");
        insertCompany(2L, "C-B");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(102L, 1L, "W-A2", "仓库A2");
        insertWarehouse(201L, 2L, "W-B1", "仓库B1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertZone(401L, 201L, "Z-B1", "库区B1");
        insertStorage(501L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");
        insertStorage(502L, 102L, 301L, "S-A-MISMATCHED", "A2", null, "L1", "01", "D1");
        insertStorage(601L, 201L, 401L, "S-B1", "B1", null, "L1", "01", "D1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertThat(spaceQueryService.selectStoragePage(query(condition("storageId", "eq", 601L)))
            .getTotal()).isZero();
        assertThat(spaceQueryService.selectStoragePage(query(condition("storageId", "eq", 9999L)))
            .getTotal()).isZero();
        assertThat(spaceQueryService.selectZonePage(query(condition("zoneId", "eq", 401L)))
            .getTotal()).isZero();
        assertThat(spaceQueryService.locateStorage(locate(201L, "B1", null, "L1", "01", "D1"))
            .getTotal()).isZero();

        assertBlocked(() -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
        assertThat(spaceQueryService.selectStoragePage(
            query(condition("storageId", "eq", 501L))
        ).getRows()).extracting(WmsStorageVo::getStorageCode).containsExactly("S-A1");
    }

    @Test
    void duplicateRawJsonKeysAreRejectedLocally() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        WmsStorageLocateBo duplicateValue = readLocate(rawLocate(101L,
            "{\"field\":\"AISLE\",\"value\":\"A1\",\"value\":\"A2\"}"));
        assertThat(duplicateValue.getAddressFields().get(0).hasDuplicateValueKey()).isTrue();
        assertBlocked(duplicateValue, WmsSpaceContract.ERROR_ADDRESS_PRESENT_MISMATCH, 400);

        WmsStorageLocateBo duplicateField = readLocate(rawLocate(101L,
            "{\"field\":\"AISLE\",\"field\":\"ROW\",\"value\":\"A1\"}"));
        assertThat(duplicateField.getAddressFields().get(0).hasDuplicateKeys()).isTrue();
        assertBlocked(duplicateField, WmsSpaceContract.ERROR_ADDRESS_PRESENT_MISMATCH, 400);
    }

    @Test
    void crossWarehouseStorageParentIsRejected() {
        insertCompany(1L, "C-A");
        insertCompany(2L, "C-B");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(201L, 2L, "W-B1", "仓库B1");
        insertZone(401L, 201L, "Z-B1", "库区B1");
        insertStorage(501L, 101L, 401L, "S-CROSS", "A1", null, "L1", "01", "D1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        assertBlocked(() -> spaceQueryService.selectStoragePage(new IwsTableQueryBo()),
            WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT, 409);
    }

    @Test
    void missingTypeConfigVersionColumnIsReportedBeforeAnyEntityQuery() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        grantAllSpacePermissions(ROLE_VIEWER);
        grantRoleCompany(ROLE_VIEWER, 1L);
        loginViewer();

        try {
            jdbcTemplate.execute("""
                alter table wms_warehouse
                change column type_config_version_id warehouse_type_config_version_id bigint default null
                """);
            assertBlocked(() -> spaceQueryService.selectWarehousePage(new IwsTableQueryBo()),
                WmsSpaceContract.ERROR_SCHEMA_STATE_UNSUPPORTED, 409);
        } finally {
            jdbcTemplate.execute("""
                alter table wms_warehouse
                change column warehouse_type_config_version_id type_config_version_id bigint default null
                """);
        }
    }

    private void seedTwoCompanies() {
        insertCompany(1L, "C-A");
        insertCompany(2L, "C-B");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertWarehouse(102L, 1L, "W-A2", "仓库A2");
        insertWarehouse(201L, 2L, "W-B1", "仓库B1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertZone(302L, 102L, "Z-A2", "库区A2");
        insertZone(401L, 201L, "Z-B1", "库区B1");
        insertStorage(501L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");
        insertStorage(502L, 102L, 302L, "S-A2", "A2", null, "L2", "02", "D2");
        insertStorage(601L, 201L, 401L, "S-B1", "B1", null, "L3", "03", "D3");
    }

    private void seedLocatableStorage() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertStorage(501L, 101L, 301L, "S-A1", "A1", null, "L-01", "01", "D-01");
        insertStorage(502L, 101L, 301L, "S-A2", "巷道-甲", null, "L-02", "02", "D-02");
    }

    private void seedOneCompanyWithStorage() {
        insertCompany(1L, "C-A");
        insertWarehouse(101L, 1L, "W-A1", "仓库A1");
        insertZone(301L, 101L, "Z-A1", "库区A1");
        insertStorage(501L, 101L, 301L, "S-A1", "A1", null, "L1", "01", "D1");
    }

    private void loginViewer() {
        assignRole(USER_VIEWER, ROLE_VIEWER);
        WmsSpaceTestAuth.login(USER_VIEWER, Set.of(), ROLE_VIEWER);
    }

    private void assertBlocked(ThrowingRunnable action, String errorCode, int expectedStatus) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).contains(errorCode);
                assertThat(error.getCode()).isEqualTo(expectedStatus);
            });
    }

    private void assertBlocked(WmsStorageLocateBo locateBo, String errorCode, int expectedStatus) {
        assertBlocked(() -> spaceQueryService.locateStorage(locateBo), errorCode, expectedStatus);
    }

    private void assertChainBlockedWithout(String forbiddenText, ThrowingRunnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage())
                    .contains(WmsSpaceContract.ERROR_SPACE_PARENT_CHAIN_INCONSISTENT)
                    .doesNotContain(forbiddenText);
                assertThat(error.getCode()).isEqualTo(409);
            });
    }

    private WmsStorageLocateBo locate(long warehouseId, String... values) {
        ObjectNode root = locateRoot(warehouseId);
        ArrayNode fields = root.putArray("addressFields");
        for (int index = 0; index < WmsSpaceContract.ADDRESS_FIELDS.size(); index++) {
            ObjectNode item = fields.addObject();
            item.put("field", WmsSpaceContract.ADDRESS_FIELDS.get(index));
            if (values[index] == null) {
                item.putNull("value");
            } else {
                item.put("value", values[index]);
            }
        }
        return readLocate(root);
    }

    private WmsStorageLocateBo locateMissingValue(long warehouseId) {
        ObjectNode root = locateRoot(warehouseId);
        ArrayNode fields = root.putArray("addressFields");
        for (int index = 0; index < WmsSpaceContract.ADDRESS_FIELDS.size(); index++) {
            ObjectNode item = fields.addObject();
            item.put("field", WmsSpaceContract.ADDRESS_FIELDS.get(index));
            if (index != 1) {
                item.put("value", "V" + index);
            }
        }
        return readLocate(root);
    }

    private WmsStorageLocateBo locateDuplicate(long warehouseId) {
        ObjectNode root = locateRoot(warehouseId);
        ArrayNode fields = root.putArray("addressFields");
        for (int index = 0; index < WmsSpaceContract.ADDRESS_FIELDS.size(); index++) {
            ObjectNode item = fields.addObject();
            item.put("field", index == 4 ? WmsSpaceContract.ADDRESS_AISLE : WmsSpaceContract.ADDRESS_FIELDS.get(index));
            item.put("value", "V" + index);
        }
        return readLocate(root);
    }

    private WmsStorageLocateBo locateUnknownField(long warehouseId) {
        ObjectNode root = locateRoot(warehouseId);
        ArrayNode fields = root.putArray("addressFields");
        for (int index = 0; index < WmsSpaceContract.ADDRESS_FIELDS.size(); index++) {
            ObjectNode item = fields.addObject();
            item.put("field", index == 0 ? "UNKNOWN" : WmsSpaceContract.ADDRESS_FIELDS.get(index));
            item.put("value", "V" + index);
        }
        return readLocate(root);
    }

    private WmsStorageLocateBo locateWithFieldCount(long warehouseId, int count) {
        ObjectNode root = locateRoot(warehouseId);
        ArrayNode fields = root.putArray("addressFields");
        for (int index = 0; index < count; index++) {
            ObjectNode item = fields.addObject();
            item.put("field", WmsSpaceContract.ADDRESS_FIELDS.get(index));
            item.put("value", "V" + index);
        }
        return readLocate(root);
    }

    private WmsStorageLocateBo locateWithNumberValue(long warehouseId) {
        ObjectNode root = locateRoot(warehouseId);
        ArrayNode fields = root.putArray("addressFields");
        for (int index = 0; index < WmsSpaceContract.ADDRESS_FIELDS.size(); index++) {
            ObjectNode item = fields.addObject();
            item.put("field", WmsSpaceContract.ADDRESS_FIELDS.get(index));
            if (index == 0) {
                item.put("value", 42);
            } else {
                item.put("value", "V" + index);
            }
        }
        return readLocate(root);
    }

    private WmsStorageLocateBo locateWithOverlongValue(long warehouseId) {
        ObjectNode root = locateRoot(warehouseId);
        ArrayNode fields = root.putArray("addressFields");
        for (int index = 0; index < WmsSpaceContract.ADDRESS_FIELDS.size(); index++) {
            ObjectNode item = fields.addObject();
            item.put("field", WmsSpaceContract.ADDRESS_FIELDS.get(index));
            item.put("value", index == 0 ? "x".repeat(65) : "V" + index);
        }
        return readLocate(root);
    }

    private ObjectNode locateRoot(long warehouseId) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("warehouseId", warehouseId);
        return root;
    }

    private String rawLocate(long warehouseId, String firstEntry) {
        return """
            {"warehouseId":%d,"addressFields":[
              %s,
              {"field":"ROW","value":"R1"},
              {"field":"LEVEL","value":"L1"},
              {"field":"COLUMN","value":"C1"},
              {"field":"DEPTH","value":"D1"}
            ]}
            """.formatted(warehouseId, firstEntry);
    }

    private WmsStorageLocateBo readLocate(String rawJson) {
        try {
            return jsonMapper.readValue(rawJson, WmsStorageLocateBo.class);
        } catch (Exception error) {
            throw new IllegalStateException("定位请求原始 JSON 绑定失败", error);
        }
    }

    private WmsStorageLocateBo readLocate(ObjectNode root) {
        try {
            return jsonMapper.treeToValue(root, WmsStorageLocateBo.class);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("定位请求 JSON 绑定失败", error);
        }
    }

    private IwsTableQueryBo query(IwsQueryConditionBo... conditions) {
        IwsTableQueryBo query = new IwsTableQueryBo();
        query.setConditions(List.of(conditions));
        return query;
    }

    private IwsQueryConditionBo condition(String field, String mode, Object value) {
        IwsQueryConditionBo condition = new IwsQueryConditionBo();
        condition.setField(field);
        condition.setQueryMode(mode);
        condition.setValue(value);
        return condition;
    }

    private void insertRole(long roleId) {
        jdbcTemplate.update(
            "insert into sys_role (role_id, role_name, status, del_flag) values (?, ?, '0', '0')",
            roleId, "role-" + roleId
        );
    }

    private void assignRole(long userId, long roleId) {
        jdbcTemplate.update("insert ignore into sys_user_role (user_id, role_id) values (?, ?)",
            userId, roleId);
    }

    private void grantRoleCompany(long roleId, long companyId) {
        jdbcTemplate.update(
            "insert into wms_company_management_grant (grant_id, company_id, role_id) values (?, ?, ?)",
            companyId * 1000 + roleId, companyId, roleId
        );
    }

    private void grantAllSpacePermissions(long roleId) {
        grantMenu(roleId, WmsSpaceContract.PERMISSION_WAREHOUSE_QUERY, WAREHOUSE_MENU);
        grantMenu(roleId, WmsSpaceContract.PERMISSION_ZONE_QUERY, ZONE_MENU);
        grantMenu(roleId, WmsSpaceContract.PERMISSION_STORAGE_QUERY, STORAGE_MENU);
    }

    private void grantMenu(long roleId, String permission, long menuId) {
        jdbcTemplate.update(
            "insert ignore into sys_menu (menu_id, menu_name, perms, status) values (?, ?, ?, '0')",
            menuId, permission, permission
        );
        jdbcTemplate.update("insert ignore into sys_role_menu (role_id, menu_id) values (?, ?)",
            roleId, menuId);
    }

    private void insertCompany(long companyId, String code) {
        jdbcTemplate.update(
            "insert into wms_company (company_id, company_code, company_name, enabled, lifecycle_status, revision) "
                + "values (?, ?, ?, 0, 'ACTIVE', 1)",
            companyId, code, "公司" + code
        );
    }

    private void insertWarehouse(long warehouseId, long companyId, String code, String name) {
        jdbcTemplate.update(
            "insert into wms_warehouse (warehouse_id, company_id, warehouse_code, warehouse_name, "
                + "warehouse_type_code, type_config_version_id, lifecycle_status, revision) "
                + "values (?, ?, ?, ?, 'GENERAL', 7001, 'ACTIVE', 1)",
            warehouseId, companyId, code, name
        );
    }

    private void insertZone(long zoneId, Long warehouseId, String code, String name) {
        insertZone(zoneId, warehouseId, code, name, null, "0");
    }

    private void insertZone(long zoneId, Long warehouseId, String code, String name,
                            Long parentZoneId, String delFlag) {
        jdbcTemplate.update(
            "insert into wms_warehouse_zone (zone_id, zone_code, zone_name, zone_type, zone_kind, "
                + "status, sort_no, del_flag, warehouse_id, parent_zone_id) "
                + "values (?, ?, ?, 1, 'MATERIAL', 1, 0, ?, ?, ?)",
            zoneId, code, name, delFlag, warehouseId, parentZoneId
        );
    }

    private void insertStorage(long storageId, Long warehouseId, long zoneId, String code,
                               String aisle, String row, String level, String column, String depth) {
        insertStorage(storageId, warehouseId, zoneId, code, aisle, row, level, column, depth, "0");
    }

    private void insertStorage(long storageId, Long warehouseId, long zoneId, String code,
                               String aisle, String row, String level, String column, String depth,
                               String delFlag) {
        jdbcTemplate.update(
            "insert into wms_storage (storage_id, storage_code, zone_id, warehouse_id, status, "
                + "lock_status, disable_status, version, del_flag, aisle_text, row_text, level_text, "
                + "column_text, depth_text) values (?, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?, ?, ?, ?)",
            storageId, code, zoneId, warehouseId, delFlag, aisle, row, level, column, depth
        );
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run();
    }
}
