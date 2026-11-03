package com.innolux.iws.wms.rule;

import cn.dev33.satoken.exception.NotPermissionException;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.WmsCompanyManagementGrant;
import com.innolux.iws.wms.domain.WmsCompanyManagementScope;
import com.innolux.iws.wms.mapper.WmsCompanyManagementGrantMapper;
import com.innolux.iws.wms.mapper.WmsCompanyManagementScopeMapper;
import com.innolux.iws.wms.mapper.WmsCompanyMapper;
import com.innolux.iws.wms.mapper.WmsCompanyRoleMapper;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeCommand;
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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 显式发起人授权的空间后台执行隔离测试。
 *
 * <p>创建阶段使用真实 Sa-Token 会话；执行阶段清除 HTTP 会话，角色、菜单和公司授权
 * 由隔离 MySQL 当前行提供。该测试覆盖 worker 只能通过持久发起人而非当前线程登录执行。</p>
 */
@Tag("local")
class WmsSpaceBackgroundAuthorizationIntegrationTest {

    private static final long COMPANY_ID = 1L;

    private static final long USER_ID = 1_001L;

    private static final long ROLE_ID = 9_001L;

    private static final long WAREHOUSE_ID = 9_101L;

    private static final DriverManagerDataSource DATA_SOURCE = dataSource();

    private static final JdbcTemplate JDBC = new JdbcTemplate(DATA_SOURCE);

    private static final TransactionTemplate TRANSACTION =
        new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WmsSpaceChangeService service;

    static {
        WmsRuleTestDatabase.jdbcUrl();
    }

    @BeforeEach
    void reset() {
        JDBC.update("delete from wms_location_effective");
        JDBC.update("delete from wms_location_version");
        JDBC.update("delete from wms_space_change_order");
        JDBC.update("delete from wms_warehouse_zone");
        JDBC.update("delete from wms_warehouse");
        JDBC.update("delete from wms_company_management_grant");
        JDBC.update("delete from wms_company_management_scope");
        JDBC.update("delete from wms_company");
        JDBC.update("delete from sys_role_menu");
        JDBC.update("delete from sys_menu");
        JDBC.update("delete from sys_user_role");
        JDBC.update("delete from sys_role");

        JDBC.update("""
            insert into wms_company
                (company_id, company_code, company_name, enabled, lifecycle_status, revision)
            values (?, 'C-BG', '后台授权公司', 1, 'ACTIVE', 1)
            """, COMPANY_ID);
        JDBC.update("""
            insert into wms_company_management_scope (company_id, scope_revision)
            values (?, 0)
            """, COMPANY_ID);
        JDBC.update("""
            insert into sys_role (role_id, role_name, status, del_flag)
            values (?, 'space-business-role', '0', '0')
            """, ROLE_ID);
        JDBC.update("""
            insert into sys_user_role (user_id, role_id) values (?, ?)
            """, USER_ID, ROLE_ID);
        JDBC.update("""
            insert into sys_menu (menu_id, menu_name, perms, status)
            values (9001, '空间变更', 'inventory:space:change', '0')
            """);
        JDBC.update("""
            insert into sys_role_menu (role_id, menu_id) values (?, 9001)
            """, ROLE_ID);
        JDBC.update("""
            insert into wms_company_management_grant (grant_id, company_id, role_id)
            values (9001, ?, ?)
            """, COMPANY_ID, ROLE_ID);
        JDBC.update("""
            insert into wms_warehouse
                (warehouse_id, company_id, warehouse_code, warehouse_name,
                 warehouse_type_code, lifecycle_status, revision)
            values (?, ?, 'WH-BG', '后台父仓', 'GENERAL', 'ACTIVE', 1)
            """, WAREHOUSE_ID, COMPANY_ID);
        JDBC.update("""
            insert into wms_location_version
                (location_version_id, object_type, object_id, version_no,
                 config_json, canonical_digest, status)
            values (9101, 'WAREHOUSE', ?, 1, json_object(), ?, 'ACTIVE')
            """, WAREHOUSE_ID, "a".repeat(64));
        JDBC.update("""
            insert into wms_location_effective
                (location_effective_id, object_type, object_id, location_version_id, revision)
            values (9101, 'WAREHOUSE', ?, 9101, 1)
            """, WAREHOUSE_ID);

        service = new WmsSpaceChangeServiceImpl(
            JDBC,
            new IncrementingGenerator(),
            JSON,
            accessPolicy()
        );
        WmsRuleTestAuth.login(USER_ID, Set.of());
    }

    @Test
    void workerExecutesWithPersistedInitiatorAfterHttpSessionIsCleared() throws Exception {
        WmsSpaceChangeCommand command = zoneCommand("ZM-BG", "后台物料区");
        long orderId = inTransaction(() -> service.createOrder(
            command,
            "b".repeat(64),
            JSON.writeValueAsString(command)
        ));

        WmsRuleTestAuth.clear();
        inTransaction(() -> {
            service.validateWithinTransaction(orderId);
            service.applyWithinTransaction(orderId);
            return service.verifyWithinTransaction(orderId);
        });

        assertThat(JDBC.queryForObject("""
            select lifecycle_status from wms_warehouse_zone
             where zone_code = 'ZM-BG'
            """, String.class)).isEqualTo("ACTIVE");
        assertThat(JDBC.queryForObject("""
            select create_by from wms_space_change_order where change_order_id = ?
            """, Long.class, orderId)).isEqualTo(USER_ID);
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_location_effective effective
              join wms_warehouse_zone zone
                on zone.zone_id = effective.object_id
             where effective.object_type = 'ZONE' and zone.zone_code = 'ZM-BG'
            """, Integer.class)).isOne();
    }

    @Test
    void workerFailsClosedWhenInitiatorPermissionIsRevoked() throws Exception {
        WmsSpaceChangeCommand command = zoneCommand("ZM-REVOKED", "撤权物料区");
        long orderId = inTransaction(() -> service.createOrder(
            command,
            "c".repeat(64),
            JSON.writeValueAsString(command)
        ));
        JDBC.update("delete from sys_role_menu where role_id = ?", ROLE_ID);
        WmsRuleTestAuth.clear();

        assertThatThrownBy(() -> inTransaction(() ->
            service.validateWithinTransaction(orderId)
        )).isInstanceOf(NotPermissionException.class);
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_warehouse_zone where zone_code = 'ZM-REVOKED'
            """, Integer.class)).isZero();
    }

    @Test
    void workerFailsClosedWhenCompanyGrantIsRevoked() {
        WmsSpaceChangeCommand command = zoneCommand("ZM-GRANT-REVOKED", "公司撤权物料区");
        long orderId = inTransaction(() -> service.createOrder(
            command,
            "d".repeat(64),
            writeJson(command)
        ));
        JDBC.update("delete from wms_company_management_grant");
        WmsRuleTestAuth.clear();

        assertThatThrownBy(() -> inTransaction(() ->
            service.validateWithinTransaction(orderId)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith("COMPANY_SCOPE_DENIED"));
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_warehouse_zone where zone_code = 'ZM-GRANT-REVOKED'
            """, Integer.class)).isZero();
    }

    @Test
    void workerFailsClosedWhenRoleIsStopped() {
        WmsSpaceChangeCommand command = zoneCommand("ZM-ROLE-STOPPED", "角色停用物料区");
        long orderId = inTransaction(() -> service.createOrder(
            command,
            "e".repeat(64),
            writeJson(command)
        ));
        JDBC.update("update sys_role set status = '1' where role_id = ?", ROLE_ID);
        WmsRuleTestAuth.clear();

        assertThatThrownBy(() -> inTransaction(() ->
            service.validateWithinTransaction(orderId)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith("COMPANY_SCOPE_DENIED"));
        assertThat(JDBC.queryForObject("""
            select count(*) from wms_warehouse_zone where zone_code = 'ZM-ROLE-STOPPED'
            """, Integer.class)).isZero();
    }

    private WmsCompanyAccessPolicy accessPolicy() {
        WmsCompanyManagementScopeMapper scopes = mock(WmsCompanyManagementScopeMapper.class);
        WmsCompanyManagementGrantMapper grants = mock(WmsCompanyManagementGrantMapper.class);
        WmsCompanyRoleMapper roles = mock(WmsCompanyRoleMapper.class);
        WmsCompanyMapper companies = mock(WmsCompanyMapper.class);

        WmsCompanyManagementScope scope = new WmsCompanyManagementScope();
        scope.setCompanyId(COMPANY_ID);
        when(scopes.selectByIdForShare(COMPANY_ID)).thenReturn(scope);
        when(scopes.selectByIdForUpdate(COMPANY_ID)).thenReturn(scope);
        when(roles.selectSuperAdminForShare(any())).thenReturn(null);
        when(roles.selectCurrentUserPermissionForShare(any(), any())).thenAnswer(invocation -> {
            Integer count = JDBC.queryForObject("""
                select count(*) from sys_user_role ur
                  join sys_role_menu rm on rm.role_id = ur.role_id
                  join sys_menu m on m.menu_id = rm.menu_id
                 where ur.user_id = ? and m.perms = ? and m.status = '0'
                """, Integer.class, new Object[]{
                    invocation.getArgument(0), invocation.getArgument(1)
                });
            return count != null && count > 0 ? 1 : null;
        });
        when(roles.selectCurrentRoleIdsForShare(any())).thenAnswer(invocation ->
            JDBC.queryForList("""
                select ur.role_id from sys_user_role ur
                  join sys_role r on r.role_id = ur.role_id
                 where ur.user_id = ? and r.status = '0' and r.del_flag = '0'
                """, Long.class, new Object[]{invocation.getArgument(0)})
        );
        when(grants.selectList(any(Wrapper.class))).thenAnswer(invocation ->
            JDBC.queryForObject("""
                select count(*) from wms_company_management_grant where company_id = ?
                """, Integer.class, COMPANY_ID) > 0
                ? List.of(new WmsCompanyManagementGrant())
                : List.of()
        );
        return new WmsCompanyAccessPolicy(scopes, grants, roles, companies);
    }

    private static WmsSpaceChangeCommand zoneCommand(String code, String name) {
        WmsSpaceChangeCommand command = new WmsSpaceChangeCommand();
        command.setCommandKey("bg-" + code);
        command.setChangeType("CREATE");
        command.setObjectType("ZONE");
        command.setWarehouseId(WAREHOUSE_ID);
        command.setCode(code);
        command.setName(name);
        command.setZoneKind("MATERIAL");
        command.setZoneType(1);
        command.setReason("后台授权验收");
        return command;
    }

    private static String writeJson(WmsSpaceChangeCommand command) {
        try {
            return JSON.writeValueAsString(command);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static <T> T inTransaction(java.util.function.Supplier<T> action) {
        return TRANSACTION.execute(status -> action.get());
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(WmsRuleTestDatabase.jdbcUrl());
        source.setUsername(WmsRuleTestDatabase.username());
        source.setPassword(WmsRuleTestDatabase.password());
        return source;
    }

    private static final class IncrementingGenerator implements IdentifierGenerator {
        private final AtomicLong next = new AtomicLong(800_000L);

        @Override
        public Number nextId(Object entity) {
            return next.incrementAndGet();
        }
    }
}
