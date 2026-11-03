/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务集成测试
 * @Version      : V1.0
 * @Description  : 验证规则发布、定义退役与公司授权撤销的确定性锁序边界；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.wms.mapper.WmsCommandAttemptMapper;
import com.innolux.iws.wms.mapper.WmsObjectAuditMapper;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionRetireCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleAttributeVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeMapper;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeStorageMapper;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeVersionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleDefinitionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleEffectivePointerMapper;
import com.innolux.iws.wms.rule.mapper.WmsRulePackageMemberMapper;
import com.innolux.iws.wms.rule.mapper.WmsRulePackageVersionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleVersionMapper;
import com.innolux.iws.wms.rule.service.WmsRuleQueryService;
import com.innolux.iws.wms.rule.service.impl.WmsRuleDraftServiceImpl;
import com.innolux.iws.wms.rule.service.impl.WmsRulePublishServiceImpl;
import com.innolux.iws.wms.rule.support.WmsRuleAuditService;
import com.innolux.iws.wms.rule.support.WmsRuleCommandExecutor;
import com.innolux.iws.wms.rule.support.WmsRuleScopeGuard;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则发布与定义退役并发边界集成测试。
 *
 * <p>测试在一次性 MySQL 容器内运行，通过最终读回或成功审计处的测试闩锁保持事务打开。
 * 共享锁用例验证两个同公司不同 warehouse 发布可同时通过同一不可变定义；排他锁用例从
 * {@code performance_schema.data_lock_waits} 确认定义退役、公司授权撤销与发布按固定顺序形成边界。</p>
 *
 * @author YONGJIE.PENG
 */
class WmsRulePublishRetirementRaceIntegrationTest extends WmsRuleTestSupport {

    private static final long WAIT_TIMEOUT_SECONDS = 20L;

    @Autowired
    private WmsRuleDefinitionMapper definitionMapper;

    @Autowired
    private WmsRuleVersionMapper ruleVersionMapper;

    @Autowired
    private WmsRulePackageVersionMapper packageVersionMapper;

    @Autowired
    private WmsRulePackageMemberMapper packageMemberMapper;

    @Autowired
    private WmsRuleEffectivePointerMapper pointerMapper;

    @Autowired
    private WmsLogicalScopeMapper logicalScopeMapper;

    @Autowired
    private WmsLogicalScopeVersionMapper logicalScopeVersionMapper;

    @Autowired
    private WmsLogicalScopeStorageMapper logicalScopeStorageMapper;

    @Autowired
    private WmsRuleAuditService auditService;

    @Autowired
    private WmsRuleCommandExecutor commandExecutor;

    @Autowired
    private WmsRuleScopeGuard scopeGuard;

    @Autowired
    private WmsCompanyAccessPolicy accessPolicy;

    @Autowired
    private WmsRuleBindingService bindingService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WmsObjectAuditMapper auditMapper;

    @Autowired
    private WmsCommandAttemptMapper attemptMapper;

    @Test
    void publishHoldingDefinitionLockCommitsBeforeRetire() throws Exception {
        WmsRuleDefinitionVo definition = createDefinition("RULE-RACE-P", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));
        WmsRulePackageVersionVo packageVersion = createPackage(
            "PKG-RACE-P", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        int revision = definitionRevision(definition.ruleDefinitionId());

        CountDownLatch readBackReached = new CountDownLatch(1);
        CountDownLatch releaseReadBack = new CountDownLatch(1);
        WmsRuleQueryService blockingQueryService =
            new BlockingPublishQueryService(queryService, readBackReached, releaseReadBack);
        WmsRulePublishServiceImpl blockingPublishService = publishService(blockingQueryService);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> publishResult = executor.submit(asAdmin(
                () -> blockingPublishService.publish(publishCommand(packageVersion.packageVersionId()))
            ));
            assertThat(readBackReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> retireResult = executor.submit(asAdmin(
                () -> draftService.retireDefinition(retireCommand(definition.ruleDefinitionId(), revision))
            ));
            awaitDefinitionLockWait();
            assertThat(retireResult.isDone()).isFalse();

            releaseReadBack.countDown();
            assertThat(publishResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
            assertThat(retireResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
        } finally {
            releaseReadBack.countDown();
            executor.shutdownNow();
        }

        assertThat(definitionStatus(definition.ruleDefinitionId())).isEqualTo("DISABLED");
        assertThat(countRows("wms_rule_effective_pointer", "scope_type = ? and scope_id = ?",
            SCOPE_TYPE, SCOPE_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select package_version_id from wms_rule_effective_pointer "
                + "where scope_type = ? and scope_id = ?",
            Long.class, SCOPE_TYPE, SCOPE_ID
        )).isEqualTo(packageVersion.packageVersionId());
    }

    @Test
    void retireThatReachedSuccessAuditBlocksPublishUntilDefinitionIsDisabled() throws Exception {
        WmsRuleDefinitionVo definition = createDefinition("RULE-RACE-R", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));
        WmsRulePackageVersionVo packageVersion = createPackage(
            "PKG-RACE-R", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        int revision = definitionRevision(definition.ruleDefinitionId());

        CountDownLatch auditReached = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        WmsRuleAuditService blockingAuditService =
            new BlockingSuccessAuditService(auditMapper, attemptMapper, auditReached, releaseAudit);
        WmsRuleDraftServiceImpl blockingDraftService =
            new WmsRuleDraftServiceImpl(
                definitionMapper,
                ruleVersionMapper,
                blockingAuditService,
                commandExecutor,
                bindingService
            );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> retireResult = executor.submit(asAdmin(
                () -> blockingDraftService.retireDefinition(
                    retireCommand(definition.ruleDefinitionId(), revision)
                )
            ));
            assertThat(auditReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> publishResult = executor.submit(asAdmin(
                () -> publishService.publish(publishCommand(packageVersion.packageVersionId()))
            ));
            awaitDefinitionLockWait();
            assertThat(publishResult.isDone()).isFalse();

            releaseAudit.countDown();
            assertThat(retireResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
            Throwable publishError = publishResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(publishError).isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith("RULE_DEFINITION_NOT_ACTIVE");
                assertThat(error.getCode()).isEqualTo(409);
            });
        } finally {
            releaseAudit.countDown();
            executor.shutdownNow();
        }

        assertThat(definitionStatus(definition.ruleDefinitionId())).isEqualTo("DISABLED");
        assertThat(packageStatus(packageVersion.packageVersionId())).isEqualTo("DRAFT");
        assertThat(countRows("wms_rule_effective_pointer", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope_version", "1 = 1")).isZero();
        assertThat(countRows("wms_logical_scope_storage", "1 = 1")).isZero();
    }

    @Test
    void twoScopePublishesCanShareDefinitionLockAndContinueConcurrently() throws Exception {
        WmsRuleDefinitionVo definition = createDefinition("RULE-RACE-S", "INBOUND_ACCEPT", SCOPE_TYPE);
        WmsRuleVersionVo active = activate(definition.versions().get(0));
        WmsRulePackageVersionVo firstPackage = createPackage(
            "PKG-RACE-S-A", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        WmsRulePackageVersionVo secondPackage = createPackage(
            "PKG-RACE-S-B", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );

        CountDownLatch firstReadBackReached = new CountDownLatch(1);
        CountDownLatch releaseFirstReadBack = new CountDownLatch(1);
        CountDownLatch secondReadBackReached = new CountDownLatch(1);
        CountDownLatch releaseSecondReadBack = new CountDownLatch(1);
        WmsRulePublishServiceImpl firstPublisher = publishService(new BlockingPublishQueryService(
            queryService, firstReadBackReached, releaseFirstReadBack
        ));
        WmsRulePublishServiceImpl secondPublisher = publishService(new BlockingPublishQueryService(
            queryService, secondReadBackReached, releaseSecondReadBack
        ));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstResult = executor.submit(asAdmin(
                () -> firstPublisher.publish(
                    publishCommand(firstPackage.packageVersionId(), SCOPE_ID)
                )
            ));
            assertThat(firstReadBackReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> secondResult = executor.submit(asAdmin(
                () -> secondPublisher.publish(
                    publishCommand(secondPackage.packageVersionId(), SCOPE_ID + 1)
                )
            ));
            assertThat(secondReadBackReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(firstResult.isDone()).isFalse();
            assertThat(secondResult.isDone()).isFalse();

            releaseSecondReadBack.countDown();
            assertThat(secondResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
            assertThat(firstResult.isDone()).isFalse();

            releaseFirstReadBack.countDown();
            assertThat(firstResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
        } finally {
            releaseFirstReadBack.countDown();
            releaseSecondReadBack.countDown();
            executor.shutdownNow();
        }

        assertThat(countRows(
            "wms_rule_effective_pointer",
            "package_version_id = ? or package_version_id = ?",
            firstPackage.packageVersionId(),
            secondPackage.packageVersionId()
        )).isEqualTo(2);
        assertThat(countRows("wms_logical_scope", "scope_code in (?, ?)",
            "WAREHOUSE:" + SCOPE_ID, "WAREHOUSE:" + (SCOPE_ID + 1))).isEqualTo(2);
    }

    @Test
    void companyGrantRevocationAndPublishRespectSharedLockOrderInBothSequences() throws Exception {
        WmsRuleDefinitionVo definition = createDefinition(
            "RULE-RACE-G", "INBOUND_ACCEPT", SCOPE_TYPE
        );
        WmsRuleVersionVo active = activate(definition.versions().get(0));
        WmsRulePackageVersionVo firstPackage = createPackage(
            "PKG-RACE-G-A", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        WmsRulePackageVersionVo secondPackage = createPackage(
            "PKG-RACE-G-B", Map.of("INBOUND_ACCEPT", active.ruleVersionId())
        );
        grantRole(2L, 200L, WmsRuleContract.PERMISSION_PUBLISH);
        grantCompany(200L, COMPANY_ID);

        CountDownLatch firstReadBackReached = new CountDownLatch(1);
        CountDownLatch releaseFirstReadBack = new CountDownLatch(1);
        WmsRulePublishServiceImpl blockingPublisher = publishService(
            new BlockingPublishQueryService(
                queryService, firstReadBackReached, releaseFirstReadBack
            )
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> publishResult = executor.submit(asUser(
                2L,
                Set.of(WmsRuleContract.PERMISSION_PUBLISH),
                () -> blockingPublisher.publish(publishCommand(firstPackage.packageVersionId()))
            ));
            assertThat(firstReadBackReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Integer> revokeResult = executor.submit(revokeCompanyGrant());
            awaitDatabaseLockWait("wms_company_management_grant");
            assertThat(revokeResult.isDone()).isFalse();

            releaseFirstReadBack.countDown();
            assertThat(publishResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
            assertThat(revokeResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            releaseFirstReadBack.countDown();
            executor.shutdownNow();
        }

        grantCompany(200L, COMPANY_ID);
        CountDownLatch revokeReached = new CountDownLatch(1);
        CountDownLatch releaseRevoke = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> revokeResult = executor.submit(
                revokeCompanyGrant(revokeReached, releaseRevoke)
            );
            assertThat(revokeReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> publishResult = executor.submit(asUser(
                2L,
                Set.of(WmsRuleContract.PERMISSION_PUBLISH),
                () -> publishService.publish(publishCommand(secondPackage.packageVersionId()))
            ));
            awaitDatabaseLockWait("wms_company_management_grant");
            assertThat(publishResult.isDone()).isFalse();

            releaseRevoke.countDown();
            assertThat(revokeResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(publishResult.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(ServiceException.class, error -> {
                    assertThat(error.getMessage()).startsWith(
                        WmsRuleContract.ERROR_SCOPE_COMPANY_DENIED
                    );
                    assertThat(error.getCode()).isEqualTo(403);
                });
        } finally {
            releaseRevoke.countDown();
            executor.shutdownNow();
        }
    }

    private WmsRulePublishServiceImpl publishService(WmsRuleQueryService query) {
        return new WmsRulePublishServiceImpl(
            definitionMapper,
            ruleVersionMapper,
            packageVersionMapper,
            packageMemberMapper,
            pointerMapper,
            logicalScopeMapper,
            logicalScopeVersionMapper,
            logicalScopeStorageMapper,
            query,
            auditService,
            commandExecutor,
            bindingService,
            scopeGuard,
            accessPolicy
        );
    }

    private WmsRulePackagePublishCommand publishCommand(Long packageVersionId) {
        return publishCommand(packageVersionId, SCOPE_ID);
    }

    private WmsRulePackagePublishCommand publishCommand(Long packageVersionId, long scopeId) {
        WmsRulePackagePublishCommand command = new WmsRulePackagePublishCommand();
        command.setCommandKey(newCommandKey("race-publish"));
        command.setPackageVersionId(packageVersionId);
        command.setScopeType(SCOPE_TYPE);
        command.setScopeId(scopeId);
        command.setStorageIds(List.of(scopeId == SCOPE_ID ? STORAGE_ID_1 : STORAGE_ID_2_1));
        command.setReason("并发发布边界测试");
        return command;
    }

    private WmsRuleDefinitionRetireCommand retireCommand(Long definitionId, int expectedRevision) {
        WmsRuleDefinitionRetireCommand command = new WmsRuleDefinitionRetireCommand();
        command.setCommandKey(newCommandKey("race-retire"));
        command.setRuleDefinitionId(definitionId);
        command.setExpectedRevision(expectedRevision);
        command.setReason("并发退役边界测试");
        return command;
    }

    private Callable<Throwable> asAdmin(ThrowingRunnable action) {
        return asUser(SystemConstants.SUPER_ADMIN_USER_ID, Set.of("*"), action);
    }

    private Callable<Throwable> asUser(long userId, Set<String> permissions,
                                       ThrowingRunnable action) {
        return () -> {
            WmsRuleTestAuth.login(userId, permissions);
            try {
                action.run();
                return null;
            } catch (Throwable error) {
                return error;
            } finally {
                WmsRuleTestAuth.clear();
            }
        };
    }

    private Callable<Integer> revokeCompanyGrant() {
        return revokeCompanyGrant(new CountDownLatch(0), new CountDownLatch(0));
    }

    private Callable<Integer> revokeCompanyGrant(CountDownLatch deleted,
                                                 CountDownLatch release) {
        return () -> new TransactionTemplate(transactionManager).execute(status -> {
            int count = jdbcTemplate.update(
                "delete from wms_company_management_grant where role_id = ?", 200L
            );
            deleted.countDown();
            awaitLatch(release);
            return count;
        });
    }

    private void awaitDefinitionLockWait() throws Exception {
        awaitDatabaseLockWait("wms_rule_definition");
    }

    private void awaitDatabaseLockWait(String tableName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbcTemplate.queryForObject(
                """
                    select count(*)
                    from performance_schema.data_lock_waits waits
                    join performance_schema.threads threads
                      on threads.thread_id = waits.requesting_thread_id
                    where threads.processlist_info like ?
                    """,
                Long.class, "%" + tableName + "%"
            );
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("未观察到等待 " + tableName + " 的并发事务");
    }

    private int definitionRevision(Long definitionId) {
        Integer revision = jdbcTemplate.queryForObject(
            "select revision from wms_rule_definition where rule_definition_id = ?",
            Integer.class, definitionId
        );
        return revision == null ? 0 : revision;
    }

    private String definitionStatus(Long definitionId) {
        return jdbcTemplate.queryForObject(
            "select status from wms_rule_definition where rule_definition_id = ?",
            String.class, definitionId
        );
    }

    private String packageStatus(Long packageVersionId) {
        return jdbcTemplate.queryForObject(
            "select status from wms_rule_package_version where package_version_id = ?",
            String.class, packageVersionId
        );
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private record BlockingPublishQueryService(
        WmsRuleQueryService delegate,
        CountDownLatch readBackReached,
        CountDownLatch releaseReadBack
    ) implements WmsRuleQueryService {

        @Override
        public com.innolux.iws.common.core.domain.PageResult<WmsRuleAttributeVo> queryAttributes(
            PageQuery pageQuery
        ) {
            return delegate.queryAttributes(pageQuery);
        }

        @Override
        public WmsRuleEffectiveVo getEffective(String scopeType, Long scopeId) {
            return delegate.getEffective(scopeType, scopeId);
        }

        @Override
        public WmsRuleEffectiveVo getEffectiveForPublish(String scopeType, Long scopeId) {
            WmsRuleEffectiveVo result = delegate.getEffectiveForPublish(scopeType, scopeId);
            readBackReached.countDown();
            awaitLatch(releaseReadBack);
            return result;
        }
    }

    private static final class BlockingSuccessAuditService extends WmsRuleAuditService {

        private final CountDownLatch auditReached;

        private final CountDownLatch releaseAudit;

        private BlockingSuccessAuditService(WmsObjectAuditMapper auditMapper,
                                            WmsCommandAttemptMapper attemptMapper,
                                            CountDownLatch auditReached,
                                            CountDownLatch releaseAudit) {
            super(auditMapper, attemptMapper);
            this.auditReached = auditReached;
            this.releaseAudit = releaseAudit;
        }

        @Override
        public void recordSuccess(String action, String objectType, Long objectId, String commandKey,
                                  Object input, Object before, Object after, String reason,
                                  LocalDateTime occurredAt, String factType, String factId) {
            auditReached.countDown();
            awaitLatch(releaseAudit);
            super.recordSuccess(action, objectType, objectId, commandKey, input, before, after, reason,
                occurredAt, factType, factId);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("等待并发测试闩锁超时");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发测试闩锁被中断", error);
        }
    }
}
