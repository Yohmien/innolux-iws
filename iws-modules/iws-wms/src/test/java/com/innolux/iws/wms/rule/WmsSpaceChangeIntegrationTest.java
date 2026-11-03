/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 隔离MySQL验证空间创建、版本指针、CAS、退役阻碍和重试；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeCommand;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeResult;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeValidation;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.space.change.service.impl.WmsSpaceChangeServiceImpl;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Tag("local")
class WmsSpaceChangeIntegrationTest {

    private static final DriverManagerDataSource DATA_SOURCE = dataSource();

    private static final JdbcTemplate JDBC = new JdbcTemplate(DATA_SOURCE);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TransactionTemplate TRANSACTION =
        new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));

    private static final WmsSpaceChangeService SERVICE = new WmsSpaceChangeServiceImpl(
        JDBC,
        new IncrementingIdentifierGenerator(),
        JSON,
        mock(WmsCompanyAccessPolicy.class)
    );

    static {
        try {
            WmsRuleTestDatabase.jdbcUrl();
        } catch (RuntimeException error) {
            throw new IllegalStateException("空间生命周期集成测试需要隔离MySQL", error);
        }
    }

    @BeforeEach
    void reset() {
        JDBC.update("delete from wms_location_effective");
        JDBC.update("delete from wms_location_version");
        JDBC.update("delete from wms_space_change_order");
        JDBC.update("delete from wms_inventory");
        JDBC.update("delete from wms_container");
        JDBC.update("delete from wms_storage");
        JDBC.update("delete from wms_warehouse_zone");
        JDBC.update("delete from wms_warehouse");
        JDBC.update("delete from wms_company_management_grant");
        JDBC.update("delete from wms_company_management_scope");
        JDBC.update("delete from wms_company");
        JDBC.update("""
            insert into wms_company
                (company_id, company_code, company_name, enabled, lifecycle_status, revision)
            values (1, 'C1', '测试公司', 1, 'ACTIVE', 1)
            """);
    }

    @Test
    void createsWarehouseZoneStorageAndSwitchesOneCurrentVersion() {
        long warehouseId = create("WAREHOUSE", "WH", warehouse("WH"));
        long materialZoneId = create("ZONE", "ZM", zone(warehouseId, "ZM"));
        long deviceZoneId = create(
            "ZONE", "ZD", zone(warehouseId, "ZD", "DEVICE", materialZoneId));
        long storageId = create("STORAGE", "S1", storage(deviceZoneId, "S1"));

        assertThat(JDBC.queryForObject(
            "select warehouse_id from wms_warehouse where warehouse_code = 'WH'",
            Long.class
        )).isEqualTo(warehouseId);
        assertThat(JDBC.queryForObject(
            "select zone_id from wms_warehouse_zone where zone_code = 'ZM'",
            Long.class
        )).isEqualTo(materialZoneId);
        assertThat(JDBC.queryForObject(
            "select parent_zone_id from wms_warehouse_zone where zone_code = 'ZD'",
            Long.class
        )).isEqualTo(materialZoneId);
        assertThat(JDBC.queryForObject(
            "select warehouse_id from wms_storage where storage_id = ?",
            Long.class, storageId
        )).isEqualTo(warehouseId);
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_location_effective where object_type = 'STORAGE'
             and object_id = ? and revision = 1
            """, Integer.class, storageId)).isOne();
    }

    @Test
    void staleRevisionIsRejectedWithoutChangingObject() {
        long warehouseId = create("WAREHOUSE", "WH", warehouse("WH"));
        WmsSpaceChangeCommand update = warehouse(warehouseId, "WH-NEW");
        update.setChangeType("UPDATE");
        update.setObjectType("WAREHOUSE");
        update.setObjectId(warehouseId);
        update.setExpectedRevision(99);
        update.setReason("测试版本冲突");
        long orderId = inTransaction(() ->
            createOrder(update, "a".repeat(64), JSON.writeValueAsString(update)));

        assertThatThrownBy(() -> inTransaction(() -> {
            SERVICE.validateWithinTransaction(orderId);
            return SERVICE.applyWithinTransaction(orderId);
        })).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(
                WmsSpaceChangeService.ERROR_VERSION_CONFLICT));
        assertThat(JDBC.queryForObject(
            "select warehouse_name from wms_warehouse where warehouse_id = ?",
            String.class, warehouseId
        )).isEqualTo("WH");
    }

    @Test
    void retireChecksCurrentInventoryAndCanSucceedAfterItIsGone() {
        long warehouseId = create("WAREHOUSE", "WH", warehouse("WH"));
        long materialZoneId = create("ZONE", "ZM", zone(warehouseId, "ZM"));
        long deviceZoneId = create(
            "ZONE", "ZD", zone(warehouseId, "ZD", "DEVICE", materialZoneId));
        long storageId = create("STORAGE", "S1", storage(deviceZoneId, "S1"));
        JDBC.update("""
            insert into wms_inventory
                (inventory_id, stock_unit_code, material_code, storage_id, storage_code,
                 zone_id, zone_code, inventory_qty, unit, version)
            values (10, 'INV-1', 'MAT-1', ?, 'S1', ?, 'Z1', 1, 'EA', 1)
            """, storageId, deviceZoneId);

        WmsSpaceChangeCommand retire = retire("STORAGE", storageId, 1);
        long orderId = inTransaction(() ->
            createOrder(retire, "b".repeat(64), JSON.writeValueAsString(retire)));
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.checkRetirementWithinTransaction(orderId)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(
                WmsSpaceChangeService.ERROR_RETIRE_BLOCKED));

        JDBC.update("delete from wms_inventory where inventory_id = 10");
        inTransaction(() -> {
            SERVICE.checkRetirementWithinTransaction(orderId);
            return SERVICE.retireWithinTransaction(orderId);
        });
        assertThat(JDBC.queryForObject(
            "select lifecycle_status from wms_storage where storage_id = ?",
            String.class, storageId
        )).isEqualTo("RETIRED");
    }

    @Test
    void rejectsIllegalParentChainsAtTheDomainWriteBoundary() {
        long warehouseId = create("WAREHOUSE", "WH", warehouse("WH"));
        long materialZoneId = create("ZONE", "ZM", zone(warehouseId, "ZM"));
        long deviceZoneId = create(
            "ZONE", "ZD", zone(warehouseId, "ZD", "DEVICE", materialZoneId));
        long otherWarehouseId = create("WAREHOUSE", "WH-OTHER", warehouse("WH-OTHER"));
        long otherMaterialZoneId = create(
            "ZONE", "ZM-OTHER", zone(otherWarehouseId, "ZM-OTHER"));

        WmsSpaceChangeCommand nestedMaterial = zone(warehouseId, "ZM-BAD");
        nestedMaterial.setParentZoneId(materialZoneId);
        long nestedMaterialOrder = inTransaction(() -> {
            nestedMaterial.setChangeType("CREATE");
            nestedMaterial.setObjectType("ZONE");
            nestedMaterial.setCode("ZM-BAD");
            nestedMaterial.setReason("非法同类嵌套");
            return createOrder(nestedMaterial, "d".repeat(64),
                JSON.writeValueAsString(nestedMaterial));
        });
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.validateWithinTransaction(nestedMaterialOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsSpaceChangeService.ERROR_INVALID));

        long storageOrder = inTransaction(() -> {
            WmsSpaceChangeCommand command = storage(materialZoneId, "S-BAD");
            command.setChangeType("CREATE");
            command.setObjectType("STORAGE");
            command.setCode("S-BAD");
            command.setReason("储位跳层");
            return createOrder(command, "e".repeat(64), JSON.writeValueAsString(command));
        });
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.validateWithinTransaction(storageOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsSpaceChangeService.ERROR_INVALID));

        WmsSpaceChangeCommand jumpedDevice = zone(warehouseId, "ZD-JUMP", "DEVICE", null);
        long jumpedDeviceOrder = inTransaction(() -> {
            jumpedDevice.setChangeType("CREATE");
            jumpedDevice.setObjectType("ZONE");
            jumpedDevice.setCode("ZD-JUMP");
            jumpedDevice.setReason("设备库区跳层");
            return createOrder(jumpedDevice, "7".repeat(64),
                JSON.writeValueAsString(jumpedDevice));
        });
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.validateWithinTransaction(jumpedDeviceOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsSpaceChangeService.ERROR_INVALID));

        WmsSpaceChangeCommand crossWarehouseDevice = zone(
            warehouseId, "ZD-CROSS", "DEVICE", otherMaterialZoneId);
        long crossWarehouseOrder = inTransaction(() -> {
            crossWarehouseDevice.setChangeType("CREATE");
            crossWarehouseDevice.setObjectType("ZONE");
            crossWarehouseDevice.setCode("ZD-CROSS");
            crossWarehouseDevice.setReason("设备库区跨仓父链");
            return createOrder(crossWarehouseDevice, "8".repeat(64),
                JSON.writeValueAsString(crossWarehouseDevice));
        });
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.validateWithinTransaction(crossWarehouseOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsSpaceChangeService.ERROR_INVALID));

        assertThat(JDBC.queryForObject("""
            select count(*) from wms_warehouse_zone
             where parent_zone_id = ? and zone_kind = 'MATERIAL'
            """, Long.class, materialZoneId)).isZero();
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_storage where zone_id = ?
            """, Long.class, materialZoneId)).isZero();
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_warehouse_zone
             where zone_code in ('ZD-JUMP', 'ZD-CROSS')
            """, Long.class)).isZero();
        assertThat(deviceZoneId).isPositive();
    }

    @Test
    void rejectsWarehouseMoveAndRetiredObjectUpdate() {
        long warehouseOne = create("WAREHOUSE", "WH-1", warehouse("WH-1"));
        long warehouseTwo = create("WAREHOUSE", "WH-2", warehouse("WH-2"));
        long materialZoneId = create("ZONE", "ZM", zone(warehouseOne, "ZM"));
        long deviceZoneId = create(
            "ZONE", "ZD", zone(warehouseOne, "ZD", "DEVICE", materialZoneId));
        long storageId = create("STORAGE", "S1", storage(deviceZoneId, "S1"));
        JDBC.update("""
            insert into wms_inventory
                (inventory_id, stock_unit_code, material_code, storage_id, storage_code,
                 zone_id, zone_code, inventory_qty, unit, version)
            values (11, 'INV-MOVE', 'MAT-MOVE', ?, 'S1', ?, 'ZD', 1, 'EA', 1)
            """, storageId, deviceZoneId);

        WmsSpaceChangeCommand move = zone(warehouseTwo, "ZM");
        move.setChangeType("UPDATE");
        move.setObjectType("ZONE");
        move.setObjectId(materialZoneId);
        move.setExpectedRevision(1);
        move.setReason("普通更新跨仓");
        long moveOrder = inTransaction(() -> {
            move.setCode("ZM");
            return createOrder(move, "f".repeat(64), JSON.writeValueAsString(move));
        });
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.validateWithinTransaction(moveOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsSpaceChangeService.ERROR_INVALID));
        assertThat(JDBC.queryForObject("""
            select warehouse_id from wms_warehouse_zone where zone_id = ?
            """, Long.class, materialZoneId)).isEqualTo(warehouseOne);
        assertThat(JDBC.queryForObject("""
            select warehouse_id from wms_storage where storage_id = ?
            """, Long.class, storageId)).isEqualTo(warehouseOne);

        WmsSpaceChangeCommand retire = retire("STORAGE", storageId, 1);
        long retireOrder = inTransaction(() ->
            createOrder(retire, "1".repeat(64), JSON.writeValueAsString(retire)));
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.checkRetirementWithinTransaction(retireOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(
                WmsSpaceChangeService.ERROR_RETIRE_BLOCKED));
        JDBC.update("delete from wms_inventory where inventory_id = 11");
        inTransaction(() -> {
            SERVICE.checkRetirementWithinTransaction(retireOrder);
            return SERVICE.retireWithinTransaction(retireOrder);
        });

        WmsSpaceChangeCommand resurrect = storage(deviceZoneId, "S1-NEW");
        resurrect.setChangeType("UPDATE");
        resurrect.setObjectType("STORAGE");
        resurrect.setObjectId(storageId);
        resurrect.setExpectedRevision(2);
        resurrect.setReason("普通更新不能复活");
        long resurrectOrder = inTransaction(() -> {
            resurrect.setCode("S1-NEW");
            return createOrder(resurrect, "2".repeat(64),
                JSON.writeValueAsString(resurrect));
        });
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.validateWithinTransaction(resurrectOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).contains("ACTIVE"));
        assertThat(JDBC.queryForObject(
            "select lifecycle_status from wms_storage where storage_id = ?",
            String.class, storageId)).isEqualTo("RETIRED");
    }

    @Test
    void applyRechecksParentLifecycleAndVersionAfterValidation() {
        long warehouseId = create("WAREHOUSE", "WH", warehouse("WH"));
        WmsSpaceChangeCommand child = zone(warehouseId, "ZM-CHILD");
        child.setChangeType("CREATE");
        child.setObjectType("ZONE");
        child.setCode("ZM-CHILD");
        child.setReason("校验后父节点退役");
        long childOrder = inTransaction(() ->
            createOrder(child, "3".repeat(64), JSON.writeValueAsString(child)));
        inTransaction(() -> SERVICE.validateWithinTransaction(childOrder));

        WmsSpaceChangeCommand retire = retire("WAREHOUSE", warehouseId, 1);
        long retireOrder = inTransaction(() ->
            createOrder(retire, "4".repeat(64), JSON.writeValueAsString(retire)));
        inTransaction(() -> {
            SERVICE.checkRetirementWithinTransaction(retireOrder);
            return SERVICE.retireWithinTransaction(retireOrder);
        });

        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.applyWithinTransaction(childOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(
                WmsSpaceChangeService.ERROR_VERSION_CONFLICT));
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_warehouse_zone where zone_code = 'ZM-CHILD'
            """, Long.class)).isZero();
    }

    @Test
    void applyRechecksMaterialParentVersionForStorageApplyWindow() {
        long warehouseId = create("WAREHOUSE", "WH", warehouse("WH"));
        long materialZoneId = create("ZONE", "ZM", zone(warehouseId, "ZM"));
        long deviceZoneId = create(
            "ZONE", "ZD", zone(warehouseId, "ZD", "DEVICE", materialZoneId));

        WmsSpaceChangeCommand storage = storage(deviceZoneId, "S1");
        storage.setChangeType("CREATE");
        storage.setObjectType("STORAGE");
        storage.setCode("S1");
        storage.setReason("等待父链变化");
        long storageOrder = inTransaction(() ->
            createOrder(storage, "5".repeat(64), JSON.writeValueAsString(storage)));
        inTransaction(() -> SERVICE.validateWithinTransaction(storageOrder));

        WmsSpaceChangeCommand materialUpdate = zone(warehouseId, "ZM-NEW");
        materialUpdate.setChangeType("UPDATE");
        materialUpdate.setObjectType("ZONE");
        materialUpdate.setObjectId(materialZoneId);
        materialUpdate.setExpectedRevision(1);
        materialUpdate.setReason("更新物料父区");
        long materialOrder = inTransaction(() ->
            createOrder(materialUpdate, "6".repeat(64),
                JSON.writeValueAsString(materialUpdate)));
        inTransaction(() -> {
            SERVICE.validateWithinTransaction(materialOrder);
            return SERVICE.applyWithinTransaction(materialOrder);
        });

        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.applyWithinTransaction(storageOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(
                WmsSpaceChangeService.ERROR_VERSION_CONFLICT));
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_storage where storage_code = 'S1'
            """, Long.class)).isZero();
    }

    @Test
    void rejectsRetiredWarehouseAndZoneUpdates() {
        long warehouseId = create("WAREHOUSE", "WH-RETIRED", warehouse("WH-RETIRED"));
        WmsSpaceChangeCommand retireWarehouse =
            retire("WAREHOUSE", warehouseId, 1);
        long warehouseOrder = inTransaction(() ->
            createOrder(retireWarehouse, "9".repeat(64),
                JSON.writeValueAsString(retireWarehouse)));
        inTransaction(() -> {
            SERVICE.checkRetirementWithinTransaction(warehouseOrder);
            return SERVICE.retireWithinTransaction(warehouseOrder);
        });

        WmsSpaceChangeCommand warehouseUpdate = warehouse(warehouseId, "WH-NEW");
        warehouseUpdate.setChangeType("UPDATE");
        warehouseUpdate.setObjectType("WAREHOUSE");
        warehouseUpdate.setObjectId(warehouseId);
        warehouseUpdate.setExpectedRevision(2);
        warehouseUpdate.setReason("退役仓库不得普通更新");
        long warehouseUpdateOrder = inTransaction(() ->
            createOrder(warehouseUpdate, "a".repeat(64),
                JSON.writeValueAsString(warehouseUpdate)));
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.validateWithinTransaction(warehouseUpdateOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).contains("ACTIVE"));
        assertThat(JDBC.queryForObject("""
            select lifecycle_status from wms_warehouse where warehouse_id = ?
            """, String.class, warehouseId)).isEqualTo("RETIRED");

        long secondWarehouse = create("WAREHOUSE", "WH-ZONE", warehouse("WH-ZONE"));
        long zoneId = create("ZONE", "Z-RETIRED", zone(secondWarehouse, "Z-RETIRED"));
        WmsSpaceChangeCommand retireZone = retire("ZONE", zoneId, 1);
        long zoneOrder = inTransaction(() ->
            createOrder(retireZone, "b".repeat(64), JSON.writeValueAsString(retireZone)));
        inTransaction(() -> {
            SERVICE.checkRetirementWithinTransaction(zoneOrder);
            return SERVICE.retireWithinTransaction(zoneOrder);
        });

        WmsSpaceChangeCommand zoneUpdate = zone(secondWarehouse, "Z-NEW");
        zoneUpdate.setChangeType("UPDATE");
        zoneUpdate.setObjectType("ZONE");
        zoneUpdate.setObjectId(zoneId);
        zoneUpdate.setExpectedRevision(2);
        zoneUpdate.setReason("退役库区不得普通更新");
        long zoneUpdateOrder = inTransaction(() ->
            createOrder(zoneUpdate, "c".repeat(64), JSON.writeValueAsString(zoneUpdate)));
        assertThatThrownBy(() -> inTransaction(() ->
            SERVICE.validateWithinTransaction(zoneUpdateOrder)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).contains("ACTIVE"));
        assertThat(JDBC.queryForObject("""
            select lifecycle_status from wms_warehouse_zone where zone_id = ?
            """, String.class, zoneId)).isEqualTo("RETIRED");
    }

    private long create(String type, String code, WmsSpaceChangeCommand command) {
        command.setChangeType("CREATE");
        command.setObjectType(type);
        command.setCode(code);
        command.setReason("创建空间");
        return inTransaction(() -> {
            String hash = "a".repeat(64);
            long orderId = createOrder(command, hash, JSON.writeValueAsString(command));
            WmsSpaceChangeValidation validation =
                SERVICE.validateWithinTransaction(orderId);
            SERVICE.applyWithinTransaction(orderId);
            SERVICE.verifyWithinTransaction(orderId);
            return validation.objectId();
        });
    }

    private static WmsSpaceChangeCommand warehouse(String name) {
        WmsSpaceChangeCommand command = base();
        command.setCompanyId(1L);
        command.setName(name);
        command.setWarehouseTypeCode("GENERAL");
        command.setEnabled(false);
        return command;
    }

    private static WmsSpaceChangeCommand warehouse(long id, String name) {
        WmsSpaceChangeCommand command = warehouse(name);
        command.setObjectId(id);
        return command;
    }

    private static WmsSpaceChangeCommand zone(long warehouseId, String name) {
        return zone(warehouseId, name, "MATERIAL", null);
    }

    private static WmsSpaceChangeCommand zone(
        long warehouseId,
        String name,
        String kind,
        Long parentZoneId
    ) {
        WmsSpaceChangeCommand command = base();
        command.setWarehouseId(warehouseId);
        command.setName(name);
        command.setZoneKind(kind);
        command.setParentZoneId(parentZoneId);
        command.setZoneType(1);
        return command;
    }

    private static WmsSpaceChangeCommand storage(long zoneId, String name) {
        WmsSpaceChangeCommand command = base();
        command.setZoneId(zoneId);
        command.setName(name);
        command.setStorageType(1);
        command.setAisleText(name);
        return command;
    }

    private static WmsSpaceChangeCommand retire(String type, long objectId, int revision) {
        WmsSpaceChangeCommand command = base();
        command.setChangeType("RETIRE");
        command.setObjectType(type);
        command.setObjectId(objectId);
        command.setExpectedRevision(revision);
        command.setReason("退役空间");
        return command;
    }

    private static WmsSpaceChangeCommand base() {
        WmsSpaceChangeCommand command = new WmsSpaceChangeCommand();
        command.setCommandKey("space-" + Long.toUnsignedString(System.nanoTime()));
        command.setRemark("测试");
        return command;
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(WmsRuleTestDatabase.jdbcUrl());
        source.setUsername(WmsRuleTestDatabase.username());
        source.setPassword(WmsRuleTestDatabase.password());
        return source;
    }

    private static <T> T inTransaction(java.util.function.Supplier<T> action) {
        return TRANSACTION.execute(status -> action.get());
    }

    private static long createOrder(
        WmsSpaceChangeCommand command,
        String hash,
        String json
    ) {
        long orderId = SERVICE.createOrder(command, hash, json);
        JDBC.update(
            "update wms_space_change_order set create_by = 1000 where change_order_id = ?",
            orderId
        );
        return orderId;
    }

    private static final class IncrementingIdentifierGenerator implements IdentifierGenerator {

        private final AtomicLong next = new AtomicLong(500_000L);

        @Override
        public Number nextId(Object entity) {
            return next.incrementAndGet();
        }
    }
}
