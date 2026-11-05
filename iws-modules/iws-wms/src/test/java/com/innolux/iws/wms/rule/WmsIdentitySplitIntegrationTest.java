/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 权威身份隔离
 * @Version      : V1.0
 * @Description  : 验证业务库不含身份表时仍读取权威身份并保持事务撤权锁语义；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.service.JdbcIamIdentityFactReader;
import com.innolux.iws.wms.domain.WmsCompanyManagementGrant;
import com.innolux.iws.wms.domain.WmsCompanyManagementScope;
import com.innolux.iws.wms.mapper.WmsCompanyManagementGrantMapper;
import com.innolux.iws.wms.mapper.WmsCompanyManagementScopeMapper;
import com.innolux.iws.wms.mapper.WmsCompanyMapper;
import com.innolux.iws.wms.process.WmsExecutionActor;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.runtime.ActionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 独立业务库与权威身份库分离时的 WMS 事务边界测试。
 */
@Tag("local")
class WmsIdentitySplitIntegrationTest {

    private static final String BUSINESS_DATABASE = "iws_wms_g20_business";
    private static final long USER_ID = 8_001L;
    private static final long DEPT_ID = 8_101L;
    private static final long ROLE_ID = 8_201L;
    private static final long COMPANY_ID = 8_301L;
    private static final long TASK_ID = 8_401L;
    private static final long INSTANCE_ID = 8_501L;
    private static final String SCOPED_PERMISSION = "inventory:company:scope:all";

    private static final DriverManagerDataSource IDENTITY_DATA_SOURCE = dataSource(
        WmsRuleTestDatabase.jdbcUrl()
    );
    private static final DriverManagerDataSource BUSINESS_DATA_SOURCE = dataSource(
        businessJdbcUrl()
    );
    private static final JdbcTemplate IDENTITY = new JdbcTemplate(IDENTITY_DATA_SOURCE);
    private static final JdbcTemplate BUSINESS = new JdbcTemplate(BUSINESS_DATA_SOURCE);
    private static final TransactionTemplate BUSINESS_TRANSACTION =
        new TransactionTemplate(new DataSourceTransactionManager(BUSINESS_DATA_SOURCE));

    static {
        initializeBusinessSchema();
        initializeIdentityDisplayTables();
    }

    private JdbcIamIdentityFactReader identityFacts;

    @BeforeEach
    void reset() {
        BUSINESS.update("delete from wms_company_management_grant");
        BUSINESS.update("delete from wms_business_task");
        BUSINESS.update("delete from wms_company");
        IDENTITY.update("delete from sys_role_menu");
        IDENTITY.update("delete from sys_menu");
        IDENTITY.update("delete from sys_role_dept");
        IDENTITY.update("delete from sys_user_role");
        IDENTITY.update("delete from sys_role");
        IDENTITY.update("delete from sys_user");
        IDENTITY.update("delete from sys_dept");

        IDENTITY.update("""
            insert into sys_user (user_id, user_name, nick_name, dept_id, status, del_flag)
            values (?, 'identity-user', 'Identity User', ?, '0', '0')
            """, USER_ID, DEPT_ID);
        IDENTITY.update("""
            insert into sys_dept (dept_id, dept_name, ancestors, status, del_flag)
            values (?, 'Identity Dept', '0', '0', '0')
            """, DEPT_ID);
        IDENTITY.update("""
            insert into sys_role (role_id, role_name, status, del_flag)
            values (?, 'identity-role', '0', '0')
            """, ROLE_ID);
        IDENTITY.update(
            "insert into sys_user_role (user_id, role_id) values (?, ?)",
            USER_ID,
            ROLE_ID
        );
        IDENTITY.update("""
            insert into sys_menu (menu_id, menu_name, perms, status)
            values (8301, 'Company Scope', ?, '0')
            """, SCOPED_PERMISSION);
        IDENTITY.update(
            "insert into sys_role_menu (role_id, menu_id) values (?, 8301)",
            ROLE_ID
        );
        BUSINESS.update("""
            insert into wms_company_management_grant (grant_id, company_id, role_id)
            values (8401, ?, ?)
            """, COMPANY_ID, ROLE_ID);
        BUSINESS.update("""
            insert into wms_company (company_id, company_code, company_name, enabled, lifecycle_status, revision)
            values (?, 'G20', 'G20 Company', 1, 'ACTIVE', 1)
            """, COMPANY_ID);

        DynamicRoutingDataSource routing = mock(DynamicRoutingDataSource.class);
        when(routing.getDataSource("identity")).thenReturn(IDENTITY_DATA_SOURCE);
        identityFacts = new JdbcIamIdentityFactReader(routing);
    }

    @AfterEach
    void clearThread() {
        BUSINESS.update("delete from wms_company_management_grant");
        BUSINESS.update("delete from wms_business_task");
        BUSINESS.update("delete from wms_company");
    }

    @Test
    void backgroundActorUsesIdentityDatabaseWithoutIdentityTableInBusinessSchema() {
        BUSINESS.update("""
            insert into wms_business_task
                (task_id, plan_instance_id, input_hash, input_json, create_by, create_dept)
            values (?, ?, 'hash', '{}', ?, ?)
            """, TASK_ID, INSTANCE_ID, USER_ID, DEPT_ID);
        WmsFrozenActionInputReader reader =
            new WmsFrozenActionInputReader(BUSINESS, identityFacts);

        WmsExecutionActor actor = reader.actor(new ActionContext(
            1L,
            INSTANCE_ID,
            "actor-test",
            1L,
            "request-1",
            EventDirection.INTERNAL,
            PostingMode.AUTOMATIC,
            "effect:actor-test",
            "local:actor-test",
            "idempotency-actor-test",
            "hash",
            "{}",
            "wms-business-task:" + TASK_ID,
            1L,
            30_000L,
            Map.of()
        ));

        assertThat(actor.userId()).isEqualTo(USER_ID);
        assertThat(actor.userName()).isEqualTo("identity-user");
        assertThat(actor.nickName()).isEqualTo("Identity User");
        assertThat(actor.deptName()).isEqualTo("Identity Dept");
    }

    @Test
    void committedIdentityRevocationDeniesCompanyWriteAfterBusinessSnapshot() {
        WmsCompanyAccessPolicy policy = companyPolicy();

        assertThatThrownBy(() -> BUSINESS_TRANSACTION.executeWithoutResult(status -> {
            assertThat(BUSINESS.queryForObject(
                "select count(*) from wms_company_management_grant",
                Integer.class
            )).isOne();
            IDENTITY.update(
                "delete from sys_user_role where user_id = ? and role_id = ?",
                USER_ID,
                ROLE_ID
            );
            policy.lockAndCheckCompanyForShare(COMPANY_ID, USER_ID);
        })).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith("COMPANY_SCOPE_DENIED")
        );
    }

    @Test
    void identityShareLockBlocksRoleDeleteUntilBusinessTransactionCompletes() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Integer>> deletion = new AtomicReference<>();
        try {
            BUSINESS_TRANSACTION.executeWithoutResult(status -> {
                assertThat(identityFacts.existingRoleIdsForShare(List.of(ROLE_ID)))
                    .containsExactly(ROLE_ID);
                deletion.set(executor.submit(() ->
                    IDENTITY.update("delete from sys_role where role_id = ?", ROLE_ID)
                ));
                sleep(350);
                assertThat(deletion.get().isDone()).isFalse();
            });

            assertThat(deletion.get().get(5, TimeUnit.SECONDS)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void currentPermissionComesFromIdentityAndTracksCommittedRevocation() {
        assertThat(identityFacts.currentPermission(USER_ID, SCOPED_PERMISSION)).isTrue();
        IDENTITY.update(
            "delete from sys_user_role where user_id = ? and role_id = ?",
            USER_ID,
            ROLE_ID
        );

        assertThat(identityFacts.currentPermission(USER_ID, SCOPED_PERMISSION)).isFalse();
    }

    @Test
    void businessRollbackKeepsIdentityReadIsolatedAndClearsJoinedSession() {
        assertThatThrownBy(() -> BUSINESS_TRANSACTION.executeWithoutResult(status -> {
            BUSINESS.update("""
                insert into wms_company
                    (company_id, company_code, company_name, enabled, lifecycle_status, revision)
                values (8999, 'ROLLBACK', 'Rollback Company', 0, 'ACTIVE', 1)
                """);
            assertThat(identityFacts.currentRoleIdsForShare(USER_ID))
                .containsExactly(ROLE_ID);
            assertThat(identityFacts.currentPermission(USER_ID, SCOPED_PERMISSION)).isTrue();
            throw new IllegalStateException("roll back business fixture");
        })).isInstanceOf(IllegalStateException.class)
            .hasMessage("roll back business fixture");

        assertThat(BUSINESS.queryForObject(
            "select count(*) from wms_company where company_id = 8999",
            Integer.class
        )).isZero();
        assertThat(identityFacts.currentPermission(USER_ID, SCOPED_PERMISSION)).isTrue();
    }

    @Test
    void strictDynamicRoutingRejectsMissingIdentityDataSource() {
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(List.of());
        routing.addDataSource("master", BUSINESS_DATA_SOURCE);
        routing.setPrimary("master");
        routing.setStrict(true);
        routing.afterPropertiesSet();

        assertThatThrownBy(() -> new JdbcIamIdentityFactReader(routing))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("identity");
    }

    @Test
    void companyScopeSqlUsesIdentityRoleIdsButOnlyBusinessGrantTable() {
        WmsCompanyAccessPolicy policy = companyPolicy();
        QueryWrapper<?> wrapper = new QueryWrapper<>();
        IDENTITY.update("delete from sys_role_menu where role_id = ?", ROLE_ID);
        try (MockedStatic<LoginHelper> login = org.mockito.Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);
            login.when(LoginHelper::getUserId).thenReturn(USER_ID);
            policy.applyCompanyScope(wrapper, "company_id");
        }
        assertThat(wrapper.getTargetSql()).doesNotContain("sys_user_role", "sys_role", "sys_menu");
        List<Long> companyIds = BUSINESS.queryForList(
            "select company_id from wms_company where " + wrapper.getTargetSql(),
            Long.class,
            wrapper.getParamNameValuePairs().values().toArray()
        );
        assertThat(companyIds).containsExactly(COMPANY_ID);
    }

    @Test
    void dataScopeFactsUseIdentityConnectionInsideBusinessTransaction() {
        long childDeptId = DEPT_ID + 1;
        long otherDeptId = DEPT_ID + 2;
        long deletedChildDeptId = DEPT_ID + 3;
        IDENTITY.update("""
            insert into sys_dept (dept_id, dept_name, ancestors, status, del_flag)
            values (?, 'Child Dept', ?, '0', '0'),
                   (?, 'Other Dept', '0', '0', '0'),
                   (?, 'Deleted Child', ?, '0', '1')
            """, childDeptId, "0," + DEPT_ID, otherDeptId,
            deletedChildDeptId, "0," + DEPT_ID);
        IDENTITY.update(
            "insert into sys_role_dept (role_id, dept_id) values (?, ?)",
            ROLE_ID,
            childDeptId
        );

        BUSINESS_TRANSACTION.executeWithoutResult(status -> {
            assertThat(BUSINESS.queryForObject(
                "select count(*) from information_schema.tables "
                    + "where table_schema = ? and table_name = 'sys_dept'",
                Integer.class,
                BUSINESS_DATABASE
            )).isZero();
            assertThat(identityFacts.roleCustomDeptIdsForShare(ROLE_ID))
                .containsExactly(childDeptId);
            List<Long> treeIds = identityFacts.deptAndChildIdsForShare(DEPT_ID);
            assertThat(treeIds).containsExactly(DEPT_ID, childDeptId);
            assertThat(treeIds).doesNotContain(deletedChildDeptId);
        });
    }

    @Test
    void authorityTransactionReusesItsOwnConnectionForScopeReads() {
        String url = WmsRuleTestDatabase.jdbcUrl();
        HikariDataSource masterPool = hikariPool(url, 2);
        // 单连接池：若修复失效再开第二条 identity 连接，会被 2 秒 connectionTimeout 直接拒绝。
        HikariDataSource identityPool = hikariPool(url, 1);
        identityPool.setConnectionTimeout(2_000);
        try {
            DynamicRoutingDataSource routing = routingDataSource(masterPool, identityPool);
            JdbcIamIdentityFactReader authorityReader = new JdbcIamIdentityFactReader(routing);
            TransactionTemplate authorityTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(routing));

            authorityTransaction.executeWithoutResult(status -> {
                new JdbcTemplate(routing).execute("set innodb_lock_wait_timeout = 5");
                assertThat(authorityReader.deptAndChildIdsForShare(DEPT_ID)).isNotEmpty();
                new JdbcTemplate(routing).update(
                    "update sys_dept set dept_name = 'Identity Dept Moved' where dept_id = ?",
                    DEPT_ID
                );
            });

            assertThat(IDENTITY.queryForObject(
                "select dept_name from sys_dept where dept_id = ?",
                String.class,
                DEPT_ID
            )).isEqualTo("Identity Dept Moved");
        } finally {
            masterPool.close();
            identityPool.close();
        }
    }

    @Test
    void differentCatalogDoesNotBorrowAuthorityConnection() {
        String upperCatalog = "IWS_WMS_RULE_TEST_G24NEG";
        WmsRuleTestDatabase.executeAsRoot(
            "create database if not exists `" + upperCatalog + "`"
        );
        String upperUrl = WmsRuleTestDatabase.jdbcUrl().replaceFirst(
            "/iws_wms_rule_test(?=\\?|$)",
            "/" + upperCatalog
        );
        HikariDataSource masterPool = hikariPool(upperUrl, 2);
        HikariDataSource identityPool = hikariPool(WmsRuleTestDatabase.jdbcUrl(), 2);
        try {
            DynamicRoutingDataSource routing = routingDataSource(masterPool, identityPool);
            JdbcIamIdentityFactReader authorityReader = new JdbcIamIdentityFactReader(routing);
            TransactionTemplate authorityTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(routing));

            // catalog 仅大小写不同也视为不同库；若误借，FOR SHARE 会打到
            // 大写 catalog 上并因缺少 sys_dept 直接失败。
            authorityTransaction.executeWithoutResult(status ->
                assertThat(authorityReader.deptAndChildIdsForShare(DEPT_ID))
                    .containsExactly(DEPT_ID)
            );
        } finally {
            masterPool.close();
            identityPool.close();
            WmsRuleTestDatabase.executeAsRoot(
                "drop database if exists `" + upperCatalog + "`"
            );
        }
    }

    private static DynamicRoutingDataSource routingDataSource(
        HikariDataSource masterPool,
        HikariDataSource identityPool
    ) {
        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(List.of());
        routing.addDataSource("master", masterPool);
        routing.addDataSource("identity", identityPool);
        routing.setPrimary("master");
        routing.setStrict(true);
        routing.afterPropertiesSet();
        return routing;
    }

    private static HikariDataSource hikariPool(String url, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(WmsRuleTestDatabase.username());
        config.setPassword(WmsRuleTestDatabase.password());
        config.setMaximumPoolSize(maxPoolSize);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    private WmsCompanyAccessPolicy companyPolicy() {
        WmsCompanyManagementScopeMapper scopes = mock(WmsCompanyManagementScopeMapper.class);
        WmsCompanyManagementGrantMapper grants = mock(WmsCompanyManagementGrantMapper.class);
        WmsCompanyMapper companies = mock(WmsCompanyMapper.class);
        WmsCompanyManagementScope scope = new WmsCompanyManagementScope();
        scope.setCompanyId(COMPANY_ID);
        when(scopes.selectByIdForShare(COMPANY_ID)).thenReturn(scope);
        when(scopes.selectByIdForUpdate(COMPANY_ID)).thenReturn(scope);
        when(grants.selectList(any(Wrapper.class))).thenAnswer(invocation ->
            BUSINESS.queryForObject(
                "select count(*) from wms_company_management_grant where company_id = ?",
                Integer.class,
                COMPANY_ID
            ) > 0
                ? List.of(new WmsCompanyManagementGrant())
                : List.of()
        );
        return new WmsCompanyAccessPolicy(scopes, grants, identityFacts, companies);
    }

    private static DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(WmsRuleTestDatabase.username());
        source.setPassword(WmsRuleTestDatabase.password());
        return source;
    }

    private static String businessJdbcUrl() {
        return WmsRuleTestDatabase.jdbcUrl().replaceFirst(
            "/iws_wms_rule_test(?=\\?|$)",
            "/" + BUSINESS_DATABASE
        );
    }

    private static void initializeBusinessSchema() {
        WmsRuleTestDatabase.executeAsRoot(
            "create database if not exists `" + BUSINESS_DATABASE + "`"
        );
        WmsRuleTestDatabase.executeAsRoot(
            "grant all privileges on `" + BUSINESS_DATABASE + "`.* to '"
                + WmsRuleTestDatabase.username() + "'@'%'"
        );
        try (var connection = BUSINESS_DATA_SOURCE.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                create table if not exists wms_business_task (
                    task_id bigint primary key,
                    plan_instance_id bigint not null,
                    input_hash varchar(64) not null,
                    input_json longtext not null,
                    create_by bigint null,
                    create_dept bigint null
                ) engine=InnoDB default charset=utf8mb4
                """);
            statement.execute("""
                create table if not exists wms_company_management_grant (
                    grant_id bigint primary key,
                    company_id bigint not null,
                    role_id bigint not null,
                    unique key uk_company_role (company_id, role_id)
                ) engine=InnoDB default charset=utf8mb4
                """);
            statement.execute("""
                create table if not exists wms_company (
                    company_id bigint primary key,
                    company_code varchar(64) not null,
                    company_name varchar(128) not null,
                    enabled tinyint not null,
                    lifecycle_status varchar(32) not null,
                    revision bigint not null
                ) engine=InnoDB default charset=utf8mb4
                """);
        } catch (Exception error) {
            throw new IllegalStateException("独立业务库结构初始化失败", error);
        }
    }

    private static void initializeIdentityDisplayTables() {
        IDENTITY.execute("""
            create table if not exists sys_user (
                user_id bigint primary key,
                user_name varchar(30) not null,
                nick_name varchar(30) not null,
                dept_id bigint null,
                status char(1) not null default '0',
                del_flag char(1) not null default '0'
            ) engine=InnoDB default charset=utf8mb4
            """);
        IDENTITY.execute("""
            create table if not exists sys_dept (
                dept_id bigint primary key,
                dept_name varchar(30) not null,
                ancestors varchar(500) not null default '0',
                status char(1) not null default '0',
                del_flag char(1) not null default '0'
            ) engine=InnoDB default charset=utf8mb4
            """);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被中断", error);
        }
    }
}
