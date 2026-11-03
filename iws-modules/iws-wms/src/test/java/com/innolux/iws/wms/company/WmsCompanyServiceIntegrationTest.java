/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环集成测试
 * @Version      : V1.0
 * @Description  : 验证公司修订 CAS、管理范围撤权、审计事务与命令幂等；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.company;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.hutool.core.lang.Dict;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.system.api.domain.RoleDTO;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.dto.WmsCompanyCreateDTO;
import com.innolux.iws.wms.domain.dto.WmsCompanyManagementScopeReplaceCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyUpdateDTO;
import com.innolux.iws.wms.domain.vo.WmsCompanyManagementScopeVo;
import com.innolux.iws.wms.service.IWmsCompanyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.innolux.iws.wms.support.company.WmsCompanyContract.PERMISSION_CREATE;
import static com.innolux.iws.wms.support.company.WmsCompanyContract.PERMISSION_DISABLE;
import static com.innolux.iws.wms.support.company.WmsCompanyContract.PERMISSION_ENABLE;
import static com.innolux.iws.wms.support.company.WmsCompanyContract.PERMISSION_QUERY;
import static com.innolux.iws.wms.support.company.WmsCompanyContract.PERMISSION_SCOPE_ALL;
import static com.innolux.iws.wms.support.company.WmsCompanyContract.PERMISSION_SCOPE_MANAGE;
import static com.innolux.iws.wms.support.company.WmsCompanyContract.PERMISSION_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 公司最小闭环的真实 MySQL 集成测试。
 *
 * <p>测试只启动一次性 MySQL 容器，使用真实 SQL、行锁、唯一键和 Spring 事务，
 * 不访问本机已有 MySQL 或业务库。</p>
 *
 * @author YONGJIE.PENG
 */
@Tag("local")
@SpringJUnitConfig(WmsCompanyTestContext.class)
class WmsCompanyServiceIntegrationTest {

    @Autowired
    private IWmsCompanyService companyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WmsCompanyTestInfrastructure.FaultInjectingTransactionManager faultTransactionManager;

    @Autowired
    private WmsCompanyTestInfrastructure.InterceptingDataSource interceptingDataSource;

    @BeforeAll
    static void installSaToken() {
        WmsCompanyTestAuth.install();
    }

    @BeforeEach
    void resetDatabaseAndLogin() {
        faultTransactionManager.reset();
        interceptingDataSource.reset();
        jdbcTemplate.update("delete from wms_upgrade_checkpoint");
        jdbcTemplate.update("delete from wms_upgrade_run");
        jdbcTemplate.update("delete from wms_command_attempt");
        jdbcTemplate.update("delete from wms_object_audit");
        jdbcTemplate.update("delete from wms_company_management_grant");
        jdbcTemplate.update("delete from wms_company_management_scope");
        jdbcTemplate.update("delete from wms_company");
        jdbcTemplate.update("delete from sys_role_menu");
        jdbcTemplate.update("delete from sys_menu");
        jdbcTemplate.update("delete from sys_user_role");
        jdbcTemplate.update("delete from sys_role");
        for (long roleId : List.of(100L, 200L, 300L)) {
            jdbcTemplate.update(
                "insert into sys_role (role_id, role_name, status, del_flag) values (?, ?, '0', '0')",
                roleId,
                "role-" + roleId
            );
        }
        WmsCompanyTestAuth.loginAsAdmin();
    }

    @AfterEach
    void clearSaToken() {
        WmsCompanyTestAuth.clear();
    }

    @Test
    void createReplayIsIdempotentAndAuditHoldsCreateSnapshot() {
        WmsCompanyCreateDTO create = createCommand("C-01", "公司一", "create-1");
        companyService.create(create);
        companyService.create(createCommand("C-01", "公司一", "create-1"));

        assertThat(countRows("wms_company", "company_code = ?", "C-01")).isEqualTo(1L);
        assertThat(countRows("wms_object_audit", "command_key = ?", "create-1")).isEqualTo(1L);
        assertThat(countRows("wms_command_attempt", "command_key = ?", "create-1")).isEqualTo(1L);

        Map<String, Object> attempt = findAttempt("create-1");
        assertThat(attempt.get("status")).isEqualTo("APPLIED");
        assertThat(attempt.get("action")).isEqualTo("COMPANY_CREATE");
        assertThat(attempt.get("fact_type")).isEqualTo("WMS_OBJECT_AUDIT");
        Long auditId = jdbcTemplate.queryForObject(
            "select audit_id from wms_object_audit where command_key = ?", Long.class, "create-1"
        );
        assertThat(attempt.get("fact_id")).isEqualTo(String.valueOf(auditId));

        Map<String, Object> audit = findAudit("create-1");
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        assertThat((String) audit.get("before_summary")).isNullOrEmpty();
        Dict after = JsonUtils.parseMap((String) audit.get("after_summary"));
        assertThat(after.get("companyCode")).isEqualTo("C-01");
        assertThat(after.get("companyName")).isEqualTo("公司一");
        assertThat(after.get("enabled")).isEqualTo(false);
        assertThat(((Number) after.get("revision")).longValue()).isEqualTo(1L);
        assertThat(after.get("lifecycleStatus")).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> companyService.create(createCommand("C-02", "公司二", "create-1")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("INPUT_HASH_CONFLICT");
        assertThat(countRows("wms_company", "1 = 1")).isEqualTo(1L);
        assertThat(countRows("wms_object_audit", "1 = 1")).isEqualTo(1L);
        assertThat(findAttempt("create-1").get("status")).isEqualTo("APPLIED");
    }

    @Test
    void staleCompanyRevisionIsRejectedAndFailureAttemptSurvivesRollback() {
        long companyId = createCompany("C-10");
        updateCompany(companyId, "甲公司", 1L, "update-1");

        assertThatThrownBy(() -> updateCompany(companyId, "乙公司", 1L, "update-2"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("VERSION_CONFLICT");

        CompanyRow company = companyRow(companyId);
        assertThat(company.name()).isEqualTo("甲公司");
        assertThat(company.revision()).isEqualTo(2L);
        assertThat(auditActions()).containsExactly("CREATE", "UPDATE");
        assertThat(findAudit("update-2")).isNull();

        Dict before = JsonUtils.parseMap((String) findAudit("update-1").get("before_summary"));
        Dict after = JsonUtils.parseMap((String) findAudit("update-1").get("after_summary"));
        assertThat(before.get("companyName")).isEqualTo("公司C-10");
        assertThat(((Number) before.get("revision")).longValue()).isEqualTo(1L);
        assertThat(after.get("companyName")).isEqualTo("甲公司");
        assertThat(((Number) after.get("revision")).longValue()).isEqualTo(2L);

        Map<String, Object> failed = findAttempt("update-2");
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("error_code")).isEqualTo("VERSION_CONFLICT");
        assertThat(failed.get("action")).isEqualTo("COMPANY_UPDATE");
        assertThat(failed.get("input_digest")).asString().hasSize(64);
    }

    @Test
    void revokedGrantBlocksWriteFromOldSessionAndRemovesVisibility() {
        long companyId = createCompany("C-20");
        replaceScope(companyId, 0L, "grant-1", 100L);
        assignRoleToUser(2L, 100L);
        grantMenuPermission(100L, PERMISSION_QUERY);
        grantMenuPermission(100L, PERMISSION_UPDATE);

        LoginUser grantedUser = WmsCompanyTestAuth.login(
            2L, Set.of(PERMISSION_UPDATE, PERMISSION_QUERY), 100L
        );
        updateCompany(companyId, "授权后名称", 1L, "update-1");
        assertThat(companyRow(companyId).revision()).isEqualTo(2L);

        runAsAdmin(() -> replaceScope(companyId, 1L, "revoke-1"));

        assertThat(grantedUser.getRoles())
            .extracting(RoleDTO::getRoleId)
            .containsExactly(100L);
        assertThatThrownBy(() -> updateCompany(companyId, "撤权后名称", 2L, "update-2"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("COMPANY_SCOPE_DENIED");

        CompanyRow company = companyRow(companyId);
        assertThat(company.name()).isEqualTo("授权后名称");
        assertThat(company.revision()).isEqualTo(2L);
        assertThat(rolesOf(companyId)).isEmpty();
        assertThat(findAttempt("update-2").get("status")).isEqualTo("REJECTED");
        assertThat(findAttempt("update-2").get("error_code")).isEqualTo("COMPANY_SCOPE_DENIED");

        PageResult<Map<String, Object>> userPage = companyService.selectPage(new IwsTableQueryBo());
        assertThat(userPage.getTotal()).isZero();
        assertThat(userPage.getRows()).isEmpty();

        WmsCompanyTestAuth.loginAsAdmin();
        PageResult<Map<String, Object>> adminPage = companyService.selectPage(new IwsTableQueryBo());
        assertThat(adminPage.getTotal()).isEqualTo(1L);
    }

    @Test
    void removingUserRoleBlocksOldSessionAndVisibility() {
        long companyId = createCompany("C-21");
        replaceScope(companyId, 0L, "grant-1", 100L);
        assignRoleToUser(2L, 100L, 200L);
        grantMenuPermission(200L, PERMISSION_QUERY);
        grantMenuPermission(200L, PERMISSION_UPDATE);
        LoginUser user = WmsCompanyTestAuth.login(
            2L, Set.of(PERMISSION_UPDATE, PERMISSION_QUERY), 100L
        );
        updateCompany(companyId, "角色存在名称", 1L, "role-ok");
        assertThat(companyRow(companyId).revision()).isEqualTo(2L);

        runAsAdmin(() -> jdbcTemplate.update(
            "delete from sys_user_role where user_id = ? and role_id = ?", 2L, 100L
        ));

        assertThat(user.getRoles()).extracting(RoleDTO::getRoleId).containsExactly(100L);
        assertThatThrownBy(() -> updateCompany(companyId, "角色移除名称", 2L, "role-denied"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("COMPANY_SCOPE_DENIED");

        assertThat(companyRow(companyId).name()).isEqualTo("角色存在名称");
        assertThat(companyRow(companyId).revision()).isEqualTo(2L);
        assertThat(rolesOf(companyId)).containsExactly(100L);
        assertThat(findAttempt("role-denied").get("status")).isEqualTo("REJECTED");
        assertThat(findAttempt("role-denied").get("error_code")).isEqualTo("COMPANY_SCOPE_DENIED");

        PageResult<Map<String, Object>> page = companyService.selectPage(new IwsTableQueryBo());
        assertThat(page.getTotal()).isZero();
        assertThat(page.getRows()).isEmpty();
    }

    @Test
    void managementScopeReadReflectsLatestRevisionAndRevoke() {
        long companyId = createCompany("C-22");
        WmsCompanyManagementScopeVo initial = companyService.queryManagementScope(companyId);
        assertThat(initial.getCompanyId()).isEqualTo(companyId);
        assertThat(initial.getScopeRevision()).isZero();
        assertThat(initial.getRoleIds()).isEmpty();

        WmsCompanyManagementScopeVo granted = replaceScopeReturning(companyId, 0L, "grant-1", 100L);
        assertThat(granted.getScopeRevision()).isEqualTo(1L);
        assertThat(granted.getRoleIds()).containsExactly(100L);

        WmsCompanyManagementScopeVo revoked = replaceScopeReturning(companyId, 1L, "revoke-1");
        assertThat(revoked.getScopeRevision()).isEqualTo(2L);
        assertThat(revoked.getRoleIds()).isEmpty();

        WmsCompanyManagementScopeVo current = companyService.queryManagementScope(companyId);
        assertThat(current.getScopeRevision()).isEqualTo(2L);
        assertThat(current.getRoleIds()).isEmpty();
    }

    @Test
    void staleScopeRevisionIsRejectedByLockedCurrentRead() {
        long companyId = createCompany("C-40");
        replaceScope(companyId, 0L, "grant-1", 100L);
        replaceScope(companyId, 1L, "grant-2", 200L);

        assertThatThrownBy(() -> replaceScope(companyId, 1L, "scope-stale", 300L))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("SCOPE_VERSION_CONFLICT");

        assertThat(scopeRevision(companyId)).isEqualTo(2L);
        assertThat(rolesOf(companyId)).containsExactly(200L);
        assertThat(findAttempt("scope-stale").get("status")).isEqualTo("FAILED");
        assertThat(findAttempt("scope-stale").get("error_code")).isEqualTo("SCOPE_VERSION_CONFLICT");
        assertThat(findAudit("scope-stale")).isNull();
    }

    @Test
    void replayingRevokedScopeCommandsNeverRestoresGrants() {
        long companyId = createCompany("C-50");
        replaceScope(companyId, 0L, "grant-1", 100L);
        replaceScope(companyId, 1L, "revoke-1");

        replaceScope(companyId, 0L, "grant-1", 100L);
        replaceScope(companyId, 1L, "revoke-1");

        assertThat(rolesOf(companyId)).isEmpty();
        assertThat(scopeRevision(companyId)).isEqualTo(2L);
        assertThat(countRows("wms_object_audit", "1 = 1")).isEqualTo(3L);

        assertThatThrownBy(() -> replaceScope(companyId, 0L, "grant-stale", 200L))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("SCOPE_VERSION_CONFLICT");
        assertThat(rolesOf(companyId)).isEmpty();
        assertThat(scopeRevision(companyId)).isEqualTo(2L);

        Dict before = JsonUtils.parseMap((String) findAudit("revoke-1").get("before_summary"));
        Dict after = JsonUtils.parseMap((String) findAudit("revoke-1").get("after_summary"));
        assertThat(before.get("roleIds")).isEqualTo(List.of(100));
        assertThat(((Number) before.get("scopeRevision")).longValue()).isEqualTo(1L);
        assertThat(after.get("roleIds")).isEqualTo(List.of());
        assertThat(((Number) after.get("scopeRevision")).longValue()).isEqualTo(2L);
    }

    @Test
    void scopeReplaceAuditCapturesCommittedBeforeState() {
        long companyId = createCompany("C-60");
        replaceScope(companyId, 0L, "grant-1", 100L);
        replaceScope(companyId, 1L, "revoke-1");
        replaceScope(companyId, 2L, "grant-after", 200L);

        assertThat(rolesOf(companyId)).containsExactly(200L);
        assertThat(scopeRevision(companyId)).isEqualTo(3L);
        Dict before = JsonUtils.parseMap((String) findAudit("grant-after").get("before_summary"));
        Dict after = JsonUtils.parseMap((String) findAudit("grant-after").get("after_summary"));
        assertThat(before.get("roleIds")).isEqualTo(List.of());
        assertThat(((Number) before.get("scopeRevision")).longValue()).isEqualTo(2L);
        assertThat(after.get("roleIds")).isEqualTo(List.of(200));
        assertThat(((Number) after.get("scopeRevision")).longValue()).isEqualTo(3L);
    }

    @Test
    void concurrentSameCommandKeyCreateAppliesExactlyOnce() throws Exception {
        createCompany("C-70");
        WmsCompanyCreateDTO request = createCommand("C-71", "并发公司", "create-concurrent");

        ConcurrentOutcome outcome = runConcurrently(
            () -> companyService.create(request),
            () -> companyService.create(request)
        );

        assertThat(outcome.failures().size()).isLessThanOrEqualTo(1);
        outcome.failures().forEach(failure ->
            assertThat(failure).isInstanceOf(ServiceException.class)
        );
        assertThat(countRows("wms_company", "company_code = ?", "C-71")).isEqualTo(1L);
        assertThat(countRows("wms_object_audit", "command_key = ?", "create-concurrent")).isEqualTo(1L);
        assertThat(countRows("wms_command_attempt", "command_key = ?", "create-concurrent")).isEqualTo(1L);
        assertThat(findAttempt("create-concurrent").get("status")).isEqualTo("APPLIED");
    }

    @Test
    void concurrentSameKeyDifferentCommandsKeepSingleWinner() throws Exception {
        long companyId = createCompany("C-120");

        ConcurrentOutcome outcome = runConcurrently(
            () -> updateCompany(companyId, "竞争甲", 1L, "same-key"),
            () -> updateCompany(companyId, "竞争乙", 1L, "same-key")
        );

        assertThat(outcome.successes()).isEqualTo(1);
        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0)).isInstanceOf(ServiceException.class);
        assertThat(outcome.failures().get(0).getMessage())
            .containsAnyOf("INPUT_HASH_CONFLICT", "COMMAND_KEY_CONFLICT");

        CompanyRow company = companyRow(companyId);
        assertThat(company.revision()).isEqualTo(2L);
        assertThat(company.name()).isIn("竞争甲", "竞争乙");
        assertThat(countRows(
            "wms_command_attempt",
            "command_key = ? and status = 'APPLIED'",
            "same-key"
        )).isEqualTo(1L);
        assertThat(countRows(
            "wms_command_attempt",
            "command_key = ? and status = 'FAILED'",
            "same-key"
        )).isZero();
        Dict after = JsonUtils.parseMap((String) findAudit("same-key").get("after_summary"));
        assertThat(after.get("companyName")).isEqualTo(company.name());
    }

    @Test
    void concurrentUpdateWithSameRevisionAppliesExactlyOnce() throws Exception {
        long companyId = createCompany("C-80");

        ConcurrentOutcome outcome = runConcurrently(
            () -> updateCompany(companyId, "并发甲", 1L, "update-a"),
            () -> updateCompany(companyId, "并发乙", 1L, "update-b")
        );

        assertThat(outcome.successes()).isEqualTo(1);
        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("VERSION_CONFLICT");
        CompanyRow company = companyRow(companyId);
        assertThat(company.revision()).isEqualTo(2L);
        assertThat(company.name()).isIn("并发甲", "并发乙");
        assertThat(auditActions()).containsExactly("CREATE", "UPDATE");
        assertThat(countRows(
            "wms_command_attempt",
            "action = 'COMPANY_UPDATE' and status = 'APPLIED'"
        )).isEqualTo(1L);
        assertThat(countRows(
            "wms_command_attempt",
            "action = 'COMPANY_UPDATE' and status = 'FAILED'"
        )).isEqualTo(1L);
    }

    @Test
    void databasePermissionRevocationDeniesNextRequest() {
        long companyId = createCompany("C-90");
        replaceScope(companyId, 0L, "grant-1", 100L);
        assignRoleToUser(2L, 100L);
        grantMenuPermission(100L, PERMISSION_UPDATE);
        LoginUser user = WmsCompanyTestAuth.login(2L, Set.of(PERMISSION_UPDATE), 100L);
        updateCompany(companyId, "权限在时名称", 1L, "perm-ok");

        revokeMenuPermission(100L, PERMISSION_UPDATE);

        assertThat(user.getMenuPermission()).contains(PERMISSION_UPDATE);
        assertThatThrownBy(() -> updateCompany(companyId, "权限撤后名称", 2L, "perm-denied"))
            .isInstanceOf(NotPermissionException.class);

        assertThat(companyRow(companyId).name()).isEqualTo("权限在时名称");
        assertThat(companyRow(companyId).revision()).isEqualTo(2L);
        assertThat(findAttempt("perm-denied").get("status")).isEqualTo("REJECTED");
        assertThat(findAttempt("perm-denied").get("error_code")).isEqualTo("PERMISSION_DENIED");
        assertThat(findAudit("perm-denied")).isNull();
    }

    @Test
    void cachedScopeAllCannotBypassCurrentDatabasePermission() {
        long granted = createCompany("C-100");
        createCompany("C-101");
        assignRoleToUser(2L, 100L);
        replaceScope(granted, 0L, "grant-100", 100L);
        grantMenuPermission(100L, PERMISSION_QUERY);

        WmsCompanyTestAuth.login(2L, Set.of(PERMISSION_QUERY, PERMISSION_SCOPE_ALL), 100L);

        PageResult<Map<String, Object>> page = companyService.selectPage(new IwsTableQueryBo());
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getRows()).extracting(row -> row.get("companyId")).containsExactly(granted);
    }

    @Test
    void databaseScopeAllAppliesWithoutSessionCache() {
        createCompany("C-102");
        createCompany("C-103");
        assignRoleToUser(2L, 200L);
        grantMenuPermission(200L, PERMISSION_QUERY);
        grantMenuPermission(200L, PERMISSION_SCOPE_ALL);

        WmsCompanyTestAuth.login(2L, Set.of(PERMISSION_QUERY), 200L);

        PageResult<Map<String, Object>> page = companyService.selectPage(new IwsTableQueryBo());
        assertThat(page.getTotal()).isEqualTo(2L);
    }

    @Test
    void scopeManageUsesLiveDatabaseMenuPermissions() {
        long companyId = createCompany("C-104");
        assignRoleToUser(2L, 100L);
        WmsCompanyTestAuth.login(2L, Set.of(PERMISSION_SCOPE_MANAGE, PERMISSION_SCOPE_ALL), 100L);

        assertThatThrownBy(() -> replaceScope(companyId, 0L, "cached-scope", 200L))
            .isInstanceOf(NotPermissionException.class);
        assertThatThrownBy(() -> companyService.queryManagementScope(companyId))
            .isInstanceOf(NotPermissionException.class);
        assertThat(rolesOf(companyId)).isEmpty();

        grantMenuPermission(100L, PERMISSION_SCOPE_MANAGE);
        grantMenuPermission(100L, PERMISSION_SCOPE_ALL);
        WmsCompanyManagementScopeVo granted = replaceScopeReturning(companyId, 0L, "live-scope", 200L);
        assertThat(granted.getScopeRevision()).isEqualTo(1L);
        assertThat(granted.getRoleIds()).containsExactly(200L);
        WmsCompanyManagementScopeVo read = companyService.queryManagementScope(companyId);
        assertThat(read.getScopeRevision()).isEqualTo(1L);
        assertThat(read.getRoleIds()).containsExactly(200L);

        revokeMenuPermission(100L, PERMISSION_SCOPE_MANAGE);
        assertThatThrownBy(() -> replaceScope(companyId, 1L, "revoked-scope"))
            .isInstanceOf(NotPermissionException.class);
        assertThatThrownBy(() -> companyService.queryManagementScope(companyId))
            .isInstanceOf(NotPermissionException.class);
        assertThat(rolesOf(companyId)).containsExactly(200L);
    }

    @Test
    void commandEntryRejectsExistingTransaction() {
        long companyId = createCompany("C-105");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.execute(status -> {
            status.setRollbackOnly();
            assertThatThrownBy(() -> updateCompany(companyId, "禁止加入", 1L, "tx-boundary"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止加入已有事务");
            return null;
        });

        assertThat(companyRow(companyId).name()).isEqualTo("公司C-105");
        assertThat(findAttempt("tx-boundary")).isNull();
    }

    @Test
    void replayedScopeReturnsCurrentCommittedGrants() {
        long companyId = createCompany("C-106");
        replaceScope(companyId, 0L, "grant-100", 100L);
        replaceScope(companyId, 1L, "revoke-100");

        WmsCompanyManagementScopeVo replay =
            replaceScopeReturning(companyId, 0L, "grant-100", 100L);
        assertThat(replay.getScopeRevision()).isEqualTo(2L);
        assertThat(replay.getRoleIds()).isEmpty();
    }

    @Test
    void databaseQueryPermissionRevocationHidesListOnNextRequest() {
        long companyId = createCompany("C-110");
        replaceScope(companyId, 0L, "grant-1", 100L);
        assignRoleToUser(2L, 100L);
        grantMenuPermission(100L, PERMISSION_QUERY);
        WmsCompanyTestAuth.login(2L, Set.of(PERMISSION_QUERY), 100L);

        assertThat(companyService.selectPage(new IwsTableQueryBo()).getTotal()).isEqualTo(1L);

        revokeMenuPermission(100L, PERMISSION_QUERY);
        assertThatThrownBy(() -> companyService.selectPage(new IwsTableQueryBo()))
            .isInstanceOf(NotPermissionException.class);
    }

    @Test
    void revocationDuringListKeepsCountAndRowsInOneSnapshot() {
        long companyId = createCompany("C-107");
        replaceScope(companyId, 0L, "grant-1", 100L);
        assignRoleToUser(2L, 100L);
        grantMenuPermission(100L, PERMISSION_QUERY);
        WmsCompanyTestAuth.login(2L, Set.of(PERMISSION_QUERY), 100L);

        interceptingDataSource.runAfterNextCompanyCount(() ->
            runAsAdmin(() -> replaceScope(companyId, 1L, "revoke-during-list"))
        );

        PageResult<Map<String, Object>> current = companyService.selectPage(new IwsTableQueryBo());
        assertThat(current.getTotal()).isEqualTo(1L);
        assertThat(current.getRows()).hasSize(1);

        PageResult<Map<String, Object>> next = companyService.selectPage(new IwsTableQueryBo());
        assertThat(next.getTotal()).isZero();
        assertThat(next.getRows()).isEmpty();
    }

    @Test
    void lostCommitAckIsReconfirmedAsApplied() {
        long companyId = createCompany("C-111");
        faultTransactionManager.failNextCommit(
            WmsCompanyTestInfrastructure.CommitFault.COMMIT_THEN_THROW
        );

        updateCompany(companyId, "提交已生效", 1L, "commit-applied");

        CompanyRow company = companyRow(companyId);
        assertThat(company.name()).isEqualTo("提交已生效");
        assertThat(company.revision()).isEqualTo(2L);
        assertThat(findAttempt("commit-applied").get("status")).isEqualTo("APPLIED");
        assertThat(countRows(
            "wms_command_attempt",
            "command_key = ? and status = 'FAILED'",
            "commit-applied"
        )).isZero();
    }

    @Test
    void unresolvedCommitWithoutAttemptReturnsUnknownWithoutFailureRecord() {
        long companyId = createCompany("C-112");
        faultTransactionManager.failNextCommit(
            WmsCompanyTestInfrastructure.CommitFault.ROLLBACK_THEN_THROW
        );

        assertThatThrownBy(() -> updateCompany(companyId, "提交未生效", 1L, "commit-failed"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("COMMIT_OUTCOME_UNKNOWN");

        assertThat(companyRow(companyId).name()).isEqualTo("公司C-112");
        assertThat(companyRow(companyId).revision()).isEqualTo(1L);
        assertThat(findAttempt("commit-failed")).isNull();
    }

    @Test
    void commitLookupFailureReturnsUnknownWithoutFailureRecord() {
        long companyId = createCompany("C-118");
        faultTransactionManager.failNextCommit(
            WmsCompanyTestInfrastructure.CommitFault.COMMIT_THEN_THROW
        );
        faultTransactionManager.runAfterNextCommitThenThrow(
            interceptingDataSource::failNextAttemptLookup
        );

        assertThatThrownBy(() -> updateCompany(companyId, "复核失败已生效", 1L, "commit-lookup-failed"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("COMMIT_OUTCOME_UNKNOWN");

        assertThat(companyRow(companyId).name()).isEqualTo("复核失败已生效");
        assertThat(companyRow(companyId).revision()).isEqualTo(2L);
        assertThat(findAttempt("commit-lookup-failed").get("status")).isEqualTo("APPLIED");
        assertThat(countRows(
            "wms_command_attempt",
            "command_key = ? and status = 'FAILED'",
            "commit-lookup-failed"
        )).isZero();
    }

    @Test
    void commitWithConflictingAttemptRejectsSuccess() {
        long companyId = createCompany("C-119");
        faultTransactionManager.failNextCommit(
            WmsCompanyTestInfrastructure.CommitFault.COMMIT_THEN_THROW
        );
        faultTransactionManager.runAfterNextCommitThenThrow(
            () -> runAsAdmin(() -> jdbcTemplate.update(
                "update wms_command_attempt set input_digest = ? where command_key = ?",
                "f".repeat(64),
                "commit-conflict"
            ))
        );

        assertThatThrownBy(() -> updateCompany(companyId, "冲突提交", 1L, "commit-conflict"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("COMMIT_KEY_CONFLICT");

        assertThat(companyRow(companyId).name()).isEqualTo("冲突提交");
        assertThat(findAttempt("commit-conflict").get("status")).isEqualTo("APPLIED");
        assertThat(countRows(
            "wms_command_attempt",
            "command_key = ? and status = 'FAILED'",
            "commit-conflict"
        )).isZero();
    }

    @Test
    void lostScopeCommitAckReturnsCommittedScope() {
        long companyId = createCompany("C-113");
        faultTransactionManager.failNextCommit(
            WmsCompanyTestInfrastructure.CommitFault.COMMIT_THEN_THROW
        );

        WmsCompanyManagementScopeVo result =
            replaceScopeReturning(companyId, 0L, "scope-commit-applied", 200L);

        assertThat(result.getScopeRevision()).isEqualTo(1L);
        assertThat(result.getRoleIds()).containsExactly(200L);
        assertThat(findAttempt("scope-commit-applied").get("status")).isEqualTo("APPLIED");
    }

    @Test
    void revokedAfterCommitDoesNotLeakManagementScope() {
        long companyId = createCompany("C-114");
        assignRoleToUser(2L, 100L);
        grantMenuPermission(100L, PERMISSION_SCOPE_MANAGE);
        grantMenuPermission(100L, PERMISSION_SCOPE_ALL);
        WmsCompanyTestAuth.login(2L, Set.of(), 100L);

        faultTransactionManager.failNextCommit(
            WmsCompanyTestInfrastructure.CommitFault.COMMIT_THEN_THROW
        );
        faultTransactionManager.runAfterNextCommitThenThrow(
            () -> runAsAdmin(() -> revokeMenuPermission(100L, PERMISSION_SCOPE_MANAGE))
        );

        assertThatThrownBy(() -> replaceScopeReturning(companyId, 0L, "scope-commit-revoked", 200L))
            .isInstanceOf(NotPermissionException.class);

        assertThat(scopeRevision(companyId)).isEqualTo(1L);
        assertThat(rolesOf(companyId)).containsExactly(200L);
    }

    @Test
    void concurrentScopeReplacesBySameUserDoNotDeadlock() throws Exception {
        long first = createCompany("C-108");
        long second = createCompany("C-109");
        assignRoleToUser(2L, 100L);
        grantMenuPermission(100L, PERMISSION_SCOPE_MANAGE);
        grantMenuPermission(100L, PERMISSION_SCOPE_ALL);

        ConcurrentOutcome outcome = runConcurrently(
            () -> WmsCompanyTestAuth.login(2L, Set.of(), 100L),
            () -> replaceScopeReturning(first, 0L, "lock-a", 100L),
            () -> replaceScopeReturning(second, 0L, "lock-b", 100L)
        );

        assertThat(outcome.failures()).isEmpty();
        assertThat(rolesOf(first)).containsExactly(100L);
        assertThat(rolesOf(second)).containsExactly(100L);
    }

    @Test
    void mixedCompanyCommandsWithRoleUpdateDoNotDeadlock() throws Exception {
        long first = createCompany("C-115");
        long second = createCompany("C-116");
        replaceScope(first, 0L, "grant-first", 100L);
        assignRoleToUser(2L, 100L);
        grantMenuPermission(100L, PERMISSION_CREATE);
        grantMenuPermission(100L, PERMISSION_UPDATE);
        grantMenuPermission(100L, PERMISSION_SCOPE_MANAGE);
        grantMenuPermission(100L, PERMISSION_SCOPE_ALL);

        ConcurrentOutcome commands = runConcurrently(
            () -> WmsCompanyTestAuth.login(2L, Set.of(), 100L),
            () -> updateCompany(first, "混合更新", 1L, "mix-update"),
            () -> replaceScopeReturning(second, 0L, "mix-scope", 100L)
        );
        ConcurrentOutcome roleMix = runConcurrently(
            () -> WmsCompanyTestAuth.login(2L, Set.of(), 100L),
            () -> companyService.create(createCommand("C-117", "混合公司", "mix-create")),
            () -> jdbcTemplate.update(
                "update sys_role set role_name = 'role-renamed' where role_id = 100"
            )
        );

        assertThat(commands.failures()).isEmpty();
        assertThat(roleMix.failures()).isEmpty();
        assertThat(companyRow(first).name()).isEqualTo("混合更新");
        assertThat(rolesOf(second)).containsExactly(100L);
        assertThat(countRows("wms_company", "company_code = ?", "C-117")).isEqualTo(1L);
    }

    private long createCompany(String code) {
        companyService.create(createCommand(code, "公司" + code, "create-" + code));
        Long companyId = jdbcTemplate.queryForObject(
            "select company_id from wms_company where company_code = ?", Long.class, code
        );
        return Objects.requireNonNull(companyId);
    }

    private static WmsCompanyCreateDTO createCommand(String code, String name, String commandKey) {
        WmsCompanyCreateDTO dto = new WmsCompanyCreateDTO();
        dto.setCompanyCode(code);
        dto.setCompanyName(name);
        dto.setRemark("测试备注");
        dto.setCommandKey(commandKey);
        return dto;
    }

    private void updateCompany(long companyId, String name, long expectedRevision, String commandKey) {
        WmsCompanyUpdateDTO dto = new WmsCompanyUpdateDTO();
        dto.setCompanyId(companyId);
        dto.setCompanyName(name);
        dto.setExpectedRevision(expectedRevision);
        dto.setCommandKey(commandKey);
        dto.setReason("测试修改");
        companyService.update(dto);
    }

    private void replaceScope(long companyId, long expectedScopeRevision, String commandKey, Long... roleIds) {
        replaceScopeReturning(companyId, expectedScopeRevision, commandKey, roleIds);
    }

    private WmsCompanyManagementScopeVo replaceScopeReturning(
        long companyId, long expectedScopeRevision, String commandKey, Long... roleIds
    ) {
        WmsCompanyManagementScopeReplaceCommand command = new WmsCompanyManagementScopeReplaceCommand();
        command.setCompanyId(companyId);
        command.setRoleIds(new ArrayList<>(Arrays.asList(roleIds)));
        command.setExpectedScopeRevision(expectedScopeRevision);
        command.setCommandKey(commandKey);
        command.setReason("测试授权");
        return companyService.replaceManagementScope(companyId, command);
    }

    private void assignRoleToUser(long userId, Long... roleIds) {
        for (Long roleId : roleIds) {
            jdbcTemplate.update(
                "insert into sys_user_role (user_id, role_id) values (?, ?)", userId, roleId
            );
        }
    }

    private void grantMenuPermission(long roleId, String permission) {
        long menuId = menuIdOf(permission);
        jdbcTemplate.update(
            "insert ignore into sys_menu (menu_id, menu_name, perms, status) values (?, ?, ?, '0')",
            menuId, permission, permission
        );
        jdbcTemplate.update(
            "insert ignore into sys_role_menu (role_id, menu_id) values (?, ?)", roleId, menuId
        );
    }

    private void revokeMenuPermission(long roleId, String permission) {
        jdbcTemplate.update(
            "delete from sys_role_menu where role_id = ? and menu_id = ?",
            roleId, menuIdOf(permission)
        );
    }

    private static long menuIdOf(String permission) {
        return switch (permission) {
            case PERMISSION_QUERY -> 9001L;
            case PERMISSION_CREATE -> 9002L;
            case PERMISSION_UPDATE -> 9003L;
            case PERMISSION_ENABLE -> 9004L;
            case PERMISSION_DISABLE -> 9005L;
            case PERMISSION_SCOPE_ALL -> 9006L;
            case PERMISSION_SCOPE_MANAGE -> 9007L;
            default -> throw new IllegalArgumentException("未配置测试菜单: " + permission);
        };
    }

    private CompanyRow companyRow(long companyId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "select company_code, company_name, enabled, lifecycle_status, revision "
                + "from wms_company where company_id = ?",
            companyId
        );
        Object enabled = row.get("enabled");
        boolean enabledFlag = enabled instanceof Boolean flag
            ? flag : ((Number) enabled).intValue() != 0;
        return new CompanyRow(
            (String) row.get("company_code"),
            (String) row.get("company_name"),
            enabledFlag,
            (String) row.get("lifecycle_status"),
            ((Number) row.get("revision")).longValue()
        );
    }

    private List<Long> rolesOf(long companyId) {
        return jdbcTemplate.queryForList(
            "select role_id from wms_company_management_grant where company_id = ? order by role_id",
            Long.class,
            companyId
        );
    }

    private long scopeRevision(long companyId) {
        Long revision = jdbcTemplate.queryForObject(
            "select scope_revision from wms_company_management_scope where company_id = ?",
            Long.class,
            companyId
        );
        return Objects.requireNonNull(revision);
    }

    private List<String> auditActions() {
        return jdbcTemplate.queryForList(
            "select action from wms_object_audit order by audit_id", String.class
        );
    }

    private Map<String, Object> findAudit(String commandKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select before_summary, after_summary, result from wms_object_audit where command_key = ?",
            commandKey
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findAttempt(String commandKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select status, action, error_code, fact_type, fact_id, input_digest "
                + "from wms_command_attempt where command_key = ?",
            commandKey
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long countRows(String table, String where, Object... args) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + where, Long.class, args
        );
        return Objects.requireNonNull(count);
    }

    private void runAsAdmin(ThrowingRunnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            WmsCompanyTestAuth.loginAsAdmin();
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                WmsCompanyTestAuth.clear();
            }
        }, "wms-test-admin-operation");
        thread.start();
        try {
            thread.join(30_000L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待撤权操作被中断", error);
        }
        if (thread.isAlive()) {
            thread.interrupt();
            throw new AssertionError("撤权操作在30秒内未完成");
        }
        if (failure.get() != null) {
            throw new AssertionError("撤权操作失败", failure.get());
        }
    }

    private ConcurrentOutcome runConcurrently(ThrowingRunnable first, ThrowingRunnable second)
        throws Exception {
        return runConcurrently(WmsCompanyTestAuth::loginAsAdmin, first, second);
    }

    private ConcurrentOutcome runConcurrently(
        Supplier<LoginUser> login, ThrowingRunnable first, ThrowingRunnable second
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> firstResult = executor.submit(worker(login, first, ready, start));
            Future<Throwable> secondResult = executor.submit(worker(login, second, ready, start));
            assertThat(ready.await(20L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new ConcurrentOutcome(
                firstResult.get(60L, TimeUnit.SECONDS),
                secondResult.get(60L, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Throwable> worker(Supplier<LoginUser> login, ThrowingRunnable action,
                                       CountDownLatch ready, CountDownLatch start) {
        return () -> {
            login.get();
            try {
                ready.countDown();
                start.await();
                action.run();
                return null;
            } catch (Throwable error) {
                return error;
            } finally {
                WmsCompanyTestAuth.clear();
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private record CompanyRow(String code, String name, boolean enabled, String lifecycleStatus, long revision) {
    }

    private record ConcurrentOutcome(Throwable first, Throwable second) {

        List<Throwable> failures() {
            return Stream.of(first, second).filter(Objects::nonNull).toList();
        }

        int successes() {
            return 2 - failures().size();
        }
    }
}
