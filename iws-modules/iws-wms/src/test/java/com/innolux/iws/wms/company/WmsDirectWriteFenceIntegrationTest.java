/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 直接写入结构门禁
 * @Version      : V1.1
 * @Description  : 验证正式登记SQL、公司/供应商写入锚点、冻结等待和异常运行拒绝；
 * @ModifyRecord : V1.0 新建；V1.1 覆盖跨release运行、canonical结构签名和失败留证顺序；
 */

package com.innolux.iws.wms.company;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.dto.WmsCompanyCreateDTO;
import com.innolux.iws.wms.domain.dto.WmsCompanyEnableCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyManagementScopeReplaceCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyUpdateDTO;
import com.innolux.iws.wms.domain.vo.WmsSupplierImportVo;
import com.innolux.iws.wms.service.IWmsCompanyService;
import com.innolux.iws.wms.service.IWmsSupplierService;
import com.innolux.iws.wms.support.schema.WmsDirectWriteSchemaContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 直接写入结构门禁的真实 MySQL 8.0.45 集成测试。
 *
 * @author YONGJIE.PENG
 */
@Tag("local")
@SpringJUnitConfig(WmsCompanyTestContext.class)
class WmsDirectWriteFenceIntegrationTest {

    private static final String FENCE_TOKEN = "TEST-S2-DIRECT-WRITE-FENCE";

    private static final String HISTORICAL_RELEASE_ID = "INV-WMS-HISTORICAL-1";

    private static final String HISTORICAL_FINGERPRINT = "a".repeat(64);

    private static final String REGISTRATION_REMARK =
        "INV-S2-F0 仅登记 WMS_DIRECT_WRITE；不表示整个 P1 已完成";

    @Autowired
    private IWmsCompanyService companyService;

    @Autowired
    private IWmsSupplierService supplierService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WmsCompanyTestInfrastructure.InterceptingDataSource interceptingDataSource;

    @Autowired
    private WmsCompanyTestInfrastructure.FaultInjectingTransactionManager faultTransactionManager;

    @BeforeAll
    static void installSaToken() {
        WmsCompanyTestAuth.install();
    }

    @BeforeEach
    void resetDatabaseAndRegisterSchema() throws Exception {
        faultTransactionManager.reset();
        interceptingDataSource.reset();
        jdbcTemplate.update("delete from wms_upgrade_checkpoint");
        jdbcTemplate.update("delete from wms_upgrade_run");
        jdbcTemplate.update(
            "delete from wms_schema_version where component_code like 'WMS_TEST_%'"
        );
        jdbcTemplate.update("delete from wms_command_attempt");
        jdbcTemplate.update("delete from wms_object_audit");
        jdbcTemplate.update("delete from wms_company_management_grant");
        jdbcTemplate.update("delete from wms_company_management_scope");
        jdbcTemplate.update("delete from wms_company");
        jdbcTemplate.update("delete from wms_supplier");
        jdbcTemplate.update("delete from sys_role_menu");
        jdbcTemplate.update("delete from sys_menu");
        jdbcTemplate.update("delete from sys_user_role");
        jdbcTemplate.update("delete from sys_role");
        jdbcTemplate.update(
            "insert into sys_role (role_id, role_name, status, del_flag) values (100, 'role-100', '0', '0')"
        );
        com.innolux.iws.wms.support.WmsIdentityTestFixture.restoreSuperAdmin(jdbcTemplate);
        reRegisterDirectWrite();
        WmsCompanyTestAuth.loginAsAdmin();
    }

    @AfterEach
    void clearSaToken() {
        WmsCompanyTestAuth.clear();
    }

    @Test
    void registeredReadyStateAllowsAllCompanyAndSupplierWrites() throws Exception {
        companyService.create(createCommand("READY-C", "就绪公司", "ready-company-create"));
        long companyId = requiredCompanyId("READY-C");
        companyService.update(companyUpdate(companyId, "就绪公司-改", 1L, "ready-company-update"));
        companyService.setEnabled(companyId, enableCommand(2L, "ready-company-enable"), true);
        companyService.replaceManagementScope(
            companyId,
            scopeCommand(companyId, 0L, "ready-company-scope", 100L)
        );

        assertThat(supplierService.insert(supplier("READY-S-1", "供应商一"))).isTrue();
        long supplierId = requiredSupplierId("READY-S-1");
        assertThat(supplierService.update(Map.of(
            "supplierId", supplierId,
            "supplierName", "供应商一-改"
        ))).isTrue();
        assertThat(supplierService.deleteByIds(List.of(supplierId))).isTrue();
        assertThat(supplierService.importData(
            List.of(importSupplier("READY-S-2", "供应商二")),
            false
        )).contains("新增 1 条");

        assertThat(jdbcTemplate.queryForObject(
            "select revision from wms_company where company_id = ?", Long.class, companyId
        )).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject(
            "select scope_revision from wms_company_management_scope where company_id = ?",
            Long.class,
            companyId
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "select del_flag from wms_supplier where supplier_id = ?", String.class, supplierId
        )).isEqualTo("1");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wms_supplier where supplier_code = 'READY-S-2' and del_flag = '0'",
            Long.class
        )).isEqualTo(1L);
    }

    @Test
    void unregisteredStateRejectsEightWritesButLeavesQueriesAvailable() throws Exception {
        WmsCompanyTestAuth.loginAsAdmin();
        companyService.create(createCommand("BASE-C", "基座公司", "base-company"));
        long companyId = requiredCompanyId("BASE-C");
        supplierService.insert(supplier("BASE-S", "基座供应商"));
        long supplierId = requiredSupplierId("BASE-S");

        jdbcTemplate.update(
            "delete from wms_schema_version where component_code = ? and schema_version = ?",
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        long auditBefore = count("wms_object_audit");
        long attemptBefore = count("wms_command_attempt");

        assertSchemaRejected(() -> companyService.create(
            createCommand("BLOCKED-C", "阻断公司", "blocked-company-create")
        ));
        assertSchemaRejected(() -> companyService.update(
            companyUpdate(companyId, "阻断修改", 1L, "blocked-company-update")
        ));
        assertSchemaRejected(() -> companyService.setEnabled(
            companyId, enableCommand(1L, "blocked-company-enable"), true
        ));
        assertSchemaRejected(() -> companyService.replaceManagementScope(
            companyId, scopeCommand(companyId, 0L, "blocked-company-scope")
        ));
        assertSchemaRejected(() -> supplierService.insert(supplier("BLOCKED-S-1", "阻断供应商一")));
        assertSchemaRejected(() -> supplierService.update(Map.of(
            "supplierId", supplierId,
            "supplierName", "阻断供应商修改"
        )));
        assertSchemaRejected(() -> supplierService.deleteByIds(List.of(supplierId)));
        assertSchemaRejected(() -> supplierService.importData(
            List.of(importSupplier("BLOCKED-S-2", "阻断供应商二")),
            false
        ));

        assertThat(count("wms_object_audit")).isEqualTo(auditBefore);
        assertThat(count("wms_command_attempt")).isEqualTo(attemptBefore);
        assertThat(companyService.selectPage(new IwsTableQueryBo()).getTotal()).isEqualTo(1L);
        assertThat(supplierService.selectPage(new IwsTableQueryBo()).getTotal()).isEqualTo(1L);
    }

    @Test
    void missingControlTableNonVerifiedAndWrongFingerprintRejectWithoutAttempt() throws Exception {
        jdbcTemplate.execute("rename table wms_schema_version to wms_schema_version_missing_probe");
        try {
            assertSchemaRejected(() -> companyService.create(
                createCommand("MISSING-C", "缺表公司", "missing-control")
            ));
            assertThat(companyService.selectPage(new IwsTableQueryBo()).getTotal()).isZero();
            assertThat(supplierService.selectPage(new IwsTableQueryBo()).getTotal()).isZero();
        } finally {
            jdbcTemplate.execute("rename table wms_schema_version_missing_probe to wms_schema_version");
        }
        assertThat(count("wms_command_attempt")).isZero();

        jdbcTemplate.update(
            "update wms_schema_version set status = 'APPLIED' "
                + "where component_code = ? and schema_version = ?",
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        assertSchemaRejected(() -> companyService.create(
            createCommand("NONVERIFIED-C", "非核验公司", "non-verified-control")
        ));

        reRegisterDirectWrite();
        jdbcTemplate.update(
            "update wms_schema_version set target_fingerprint = repeat('0', 64) "
                + "where component_code = ? and schema_version = ?",
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        assertSchemaRejected(() -> supplierService.insert(
            supplier("WRONG-FP-S", "指纹错误供应商")
        ));
        assertThat(count("wms_command_attempt")).isZero();
        assertThat(companyService.selectPage(new IwsTableQueryBo()).getTotal()).isZero();
        assertThat(supplierService.selectPage(new IwsTableQueryBo()).getTotal()).isZero();
    }

    @Test
    void existingWriterMustCommitBeforeFreezeAndSubsequentWriteIsRejected() throws Exception {
        CountDownLatch writerReachedBusinessRead = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        interceptingDataSource.runAfterNextCompanyCount(() -> {
            writerReachedBusinessRead.countDown();
            await(releaseWriter);
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> writer = executor.submit(threadAsAdmin(() ->
                companyService.create(createCommand("ORDER-C", "先写公司", "write-before-freeze"))
            ));
            assertThat(writerReachedBusinessRead.await(20, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> freeze = executor.submit(() -> {
                try {
                    executeControlScript("begin", FENCE_TOKEN, null, "");
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            Thread.sleep(300L);
            assertThat(freeze.isDone()).isFalse();

            releaseWriter.countDown();
            assertThat(writer.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(freeze.get(20, TimeUnit.SECONDS)).isNull();
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
        }

        assertThat(count("wms_company")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wms_upgrade_checkpoint "
                + "where step_code = 'S2_DIRECT_WRITE_FENCE' and status = 'VERIFIED'",
            Long.class
        )).isEqualTo(1L);
        assertThatThrownBy(() -> companyService.create(
            createCommand("AFTER-FREEZE-C", "冻结后公司", "write-after-freeze")
        )).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DIRECT_WRITE_FROZEN");
        assertThatThrownBy(() -> supplierService.insert(
            supplier("AFTER-FREEZE-S", "冻结后供应商")
        )).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DIRECT_WRITE_FROZEN");
        assertThat(count("wms_company")).isEqualTo(1L);
        assertThat(count("wms_supplier")).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wms_command_attempt where command_key = 'write-after-freeze'",
            Long.class
        )).isZero();
    }

    @Test
    void failureAttemptStartedBeforeFreezeHoldsAnchorAndFreezeWaits() throws Exception {
        companyService.create(createCommand(
            "FAIL-BEFORE-FREEZE-C",
            "失败留证公司",
            "failure-before-freeze-setup"
        ));
        CountDownLatch failureAttemptReached = new CountDownLatch(1);
        CountDownLatch releaseFailureAttempt = new CountDownLatch(1);
        faultTransactionManager.runAfterNextRollback(() ->
            interceptingDataSource.runBeforeNextAttemptLookup(() -> {
                failureAttemptReached.countDown();
                await(releaseFailureAttempt);
            })
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> failure = executor.submit(threadAsAdmin(() ->
                companyService.create(createCommand(
                    "FAIL-BEFORE-FREEZE-C",
                    "重复公司",
                    "failure-before-freeze"
                ))
            ));
            assertThat(failureAttemptReached.await(20, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> freeze = executor.submit(() -> {
                try {
                    executeControlScript("begin", FENCE_TOKEN, null, "");
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            Thread.sleep(300L);
            assertThat(freeze.isDone()).isFalse();

            releaseFailureAttempt.countDown();
            assertThat(failure.get(20, TimeUnit.SECONDS))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("公司编码已存在");
            assertThat(freeze.get(20, TimeUnit.SECONDS)).isNull();
        } finally {
            releaseFailureAttempt.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "select status from wms_command_attempt where command_key = 'failure-before-freeze'",
            String.class
        )).isEqualTo("FAILED");
        assertThat(failureAttemptCount()).isEqualTo(1L);
        assertThat(count("wms_upgrade_checkpoint")).isEqualTo(1L);
    }

    @Test
    void freezeBeforeFailureAttemptPreventsLateAttemptAndPreservesBusinessError() throws Exception {
        companyService.create(createCommand(
            "FREEZE-FIRST-C",
            "冻结先行公司",
            "freeze-first-setup"
        ));
        CountDownLatch businessRolledBack = new CountDownLatch(1);
        CountDownLatch allowFailureAttempt = new CountDownLatch(1);
        faultTransactionManager.runAfterNextRollback(() -> {
            businessRolledBack.countDown();
            await(allowFailureAttempt);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> failure = executor.submit(threadAsAdmin(() ->
                companyService.create(createCommand(
                    "FREEZE-FIRST-C",
                    "重复公司",
                    "freeze-first-late-attempt"
                ))
            ));
            assertThat(businessRolledBack.await(20, TimeUnit.SECONDS)).isTrue();

            executeControlScript("begin", FENCE_TOKEN, null, "");
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wms_upgrade_run where status = 'RUNNING'",
                Long.class
            )).isEqualTo(1L);

            allowFailureAttempt.countDown();
            assertThat(failure.get(20, TimeUnit.SECONDS))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("公司编码已存在");
        } finally {
            allowFailureAttempt.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wms_command_attempt "
                + "where command_key = 'freeze-first-late-attempt'",
            Long.class
        )).isZero();
        assertThat(failureAttemptCount()).isZero();
    }

    @Test
    void singleUnsafeRunInSameOrOtherReleaseFreezesWrites() {
        insertRun("SAME-1", "RUNNING", "CUTOVER_READY", null);
        assertThatThrownBy(() -> companyService.create(
            createCommand("SAME-C", "同发布公司", "same-release-unsafe")
        )).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DIRECT_WRITE_FROZEN");
        assertThat(count("wms_command_attempt")).isZero();

        jdbcTemplate.update("delete from wms_upgrade_run");
        insertRun(
            "OTHER-1",
            HISTORICAL_RELEASE_ID,
            "BLOCKED",
            "DATA_TARGET",
            HISTORICAL_FINGERPRINT,
            null
        );
        assertThatThrownBy(() -> supplierService.insert(
            supplier("OTHER-S", "异发布供应商")
        )).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DIRECT_WRITE_FROZEN");

        assertThat(companyService.selectPage(new IwsTableQueryBo()).getTotal()).isZero();
        assertThat(supplierService.selectPage(new IwsTableQueryBo()).getTotal()).isZero();
        assertThat(count("wms_command_attempt")).isZero();
    }

    @Test
    void multipleUnsafeRunsAcrossReleasesConflictAndMalformedCompletedRunFreezes() {
        insertRun("MULTI-1", "RUNNING", "CUTOVER_READY", null);
        insertRun(
            "MULTI-2",
            HISTORICAL_RELEASE_ID,
            "FAILED",
            "DDL_TARGET",
            HISTORICAL_FINGERPRINT,
            null
        );
        assertThatThrownBy(() -> companyService.create(
            createCommand("MULTI-C", "多运行公司", "multiple-runs")
        )).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DIRECT_WRITE_FENCE_CONFLICT");

        jdbcTemplate.update("delete from wms_upgrade_run");
        insertRun("MALFORMED-1", "COMPLETED", "COMPLETE", null);
        assertThatThrownBy(() -> supplierService.insert(
            supplier("MALFORMED-S", "未完成供应商")
        )).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DIRECT_WRITE_FROZEN");

        assertThat(companyService.selectPage(new IwsTableQueryBo()).getTotal()).isZero();
        assertThat(supplierService.selectPage(new IwsTableQueryBo()).getTotal()).isZero();
    }

    @Test
    void completedRunsUseTheirOwnRegisteredTargetFingerprint() {
        insertCompletionRegistration(
            "WMS_TEST_HISTORICAL",
            "1",
            HISTORICAL_RELEASE_ID,
            HISTORICAL_FINGERPRINT
        );
        insertRun(
            "COMPLETE-CURRENT",
            WmsDirectWriteSchemaContract.RELEASE_ID,
            "COMPLETED",
            "COMPLETE",
            WmsDirectWriteSchemaContract.TARGET_FINGERPRINT,
            LocalDateTime.now()
        );
        insertRun(
            "COMPLETE-HISTORICAL",
            HISTORICAL_RELEASE_ID,
            "COMPLETED",
            "COMPLETE",
            HISTORICAL_FINGERPRINT,
            LocalDateTime.now()
        );

        companyService.create(createCommand("COMPLETE-C", "完成发布公司", "completed-run"));
        assertThat(supplierService.insert(supplier("COMPLETE-S", "完成发布供应商"))).isTrue();
    }

    @Test
    void completedRunCannotBorrowCurrentFingerprintWithoutOwnRegistration() {
        insertRun(
            "FOREIGN-FIXED-FP",
            HISTORICAL_RELEASE_ID,
            "COMPLETED",
            "COMPLETE",
            WmsDirectWriteSchemaContract.TARGET_FINGERPRINT,
            LocalDateTime.now()
        );

        assertThatThrownBy(() -> companyService.create(
            createCommand("BORROW-C", "借用指纹公司", "borrowed-fingerprint")
        )).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DIRECT_WRITE_FROZEN");
        assertThat(count("wms_company")).isZero();
        assertThat(count("wms_command_attempt")).isZero();
    }

    @Test
    void registrationIsIdempotentAndConflictingContentIsNeverOverwritten() throws Exception {
        executeRegistrationScript();
        assertThat(schemaRegistrationCount()).isEqualTo(1L);

        jdbcTemplate.update(
            "update wms_schema_version set remark = 'ALTERED|~REMARK' "
                + "where component_code = ? and schema_version = ?",
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        assertThatThrownBy(this::executeRegistrationScript)
            .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
            "select remark from wms_schema_version where component_code = ? and schema_version = ?",
            String.class,
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        )).isEqualTo("ALTERED|~REMARK");
        assertThat(jdbcTemplate.queryForObject(
            "select release_id from wms_schema_version where component_code = ? and schema_version = ?",
            String.class,
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        )).isEqualTo(WmsDirectWriteSchemaContract.RELEASE_ID);

        jdbcTemplate.update(
            "update wms_schema_version set remark = ? "
                + "where component_code = ? and schema_version = ?",
            REGISTRATION_REMARK,
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        jdbcTemplate.update(
            "update wms_schema_version set release_id = 'ALTERED' "
                + "where component_code = ? and schema_version = ?",
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        assertThatThrownBy(this::executeRegistrationScript)
            .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
            "select release_id from wms_schema_version where component_code = ? and schema_version = ?",
            String.class,
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        )).isEqualTo("ALTERED");
    }

    @Test
    void registrationRejectsUnsafeRunFromAnotherReleaseBeforeWriting() throws Exception {
        jdbcTemplate.update(
            "delete from wms_schema_version where component_code = ? and schema_version = ?",
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        insertRun(
            "REGISTER-OTHER-UNSAFE",
            HISTORICAL_RELEASE_ID,
            "BLOCKED",
            "DATA_TARGET",
            HISTORICAL_FINGERPRINT,
            null
        );

        assertThatThrownBy(this::executeRegistrationScript)
            .isInstanceOf(RuntimeException.class);
        assertThat(schemaRegistrationCount()).isZero();
    }

    @Test
    void canonicalRegistrationDistinguishesSqlNullFromLiteralMarker() throws Exception {
        jdbcTemplate.execute(
            "alter table wms_company alter column remark set default '<NULL>'"
        );
        try {
            assertRegistrationPreflightRejected();
        } finally {
            jdbcTemplate.execute(
                "alter table wms_company alter column remark drop default"
            );
        }
        executeRegistrationScript();
    }

    @Test
    void canonicalRegistrationRejectsSeparatorTextInDefaultsAndComments() throws Exception {
        jdbcTemplate.execute(
            "alter table wms_company alter column remark set default 'a|~b'"
        );
        try {
            assertRegistrationPreflightRejected();
        } finally {
            jdbcTemplate.execute(
                "alter table wms_company alter column remark drop default"
            );
        }
        executeRegistrationScript();

        jdbcTemplate.execute(
            "alter table wms_company comment = 'WMS公司主档|~probe'"
        );
        try {
            assertRegistrationPreflightRejected();
        } finally {
            jdbcTemplate.execute("alter table wms_company comment = 'WMS公司主档'");
        }
        executeRegistrationScript();
    }

    @Test
    void controlScriptUsesAllRunsAndAcceptanceEvidenceFromEachRelease() throws Exception {
        insertRun(
            "CONTROL-OTHER-UNSAFE",
            HISTORICAL_RELEASE_ID,
            "RUNNING",
            "CUTOVER_READY",
            HISTORICAL_FINGERPRINT,
            null
        );
        assertThatThrownBy(() -> executeControlScript("begin", FENCE_TOKEN, null, ""))
            .isInstanceOf(RuntimeException.class);

        jdbcTemplate.update("delete from wms_upgrade_run");
        insertRun("CONTROL-CURRENT-UNSAFE", "RUNNING", "CUTOVER_READY", null);
        insertRun(
            "CONTROL-OTHER-FAILED",
            HISTORICAL_RELEASE_ID,
            "FAILED",
            "DDL_TARGET",
            HISTORICAL_FINGERPRINT,
            null
        );
        assertThatThrownBy(() -> executeControlScript("begin", FENCE_TOKEN, null, ""))
            .isInstanceOf(RuntimeException.class);

        jdbcTemplate.update("delete from wms_upgrade_run");
        insertCompletionRegistration(
            "WMS_TEST_HISTORICAL",
            "1",
            HISTORICAL_RELEASE_ID,
            HISTORICAL_FINGERPRINT
        );
        insertRun(
            "CONTROL-CURRENT-COMPLETE",
            WmsDirectWriteSchemaContract.RELEASE_ID,
            "COMPLETED",
            "COMPLETE",
            WmsDirectWriteSchemaContract.TARGET_FINGERPRINT,
            LocalDateTime.now()
        );
        insertRun(
            "CONTROL-OTHER-COMPLETE",
            HISTORICAL_RELEASE_ID,
            "COMPLETED",
            "COMPLETE",
            HISTORICAL_FINGERPRINT,
            LocalDateTime.now()
        );

        executeControlScript("begin", FENCE_TOKEN, null, "");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wms_upgrade_checkpoint "
                + "where step_code = 'S2_DIRECT_WRITE_FENCE' and status = 'VERIFIED'",
            Long.class
        )).isEqualTo(1L);
    }

    private void assertRegistrationPreflightRejected() {
        assertThatThrownBy(this::executeRegistrationScript)
            .isInstanceOf(RuntimeException.class);
        assertThat(schemaRegistrationCount()).isEqualTo(1L);
    }

    @Test
    void beginReplayIsIdempotentAndFinishKeepsReleaseContractBlocked() throws Exception {
        executeControlScript("begin", FENCE_TOKEN, null, "");
        executeControlScript("begin", FENCE_TOKEN, null, "");
        Long runId = jdbcTemplate.queryForObject(
            "select run_id from wms_upgrade_run where release_id = ?",
            Long.class,
            WmsDirectWriteSchemaContract.RELEASE_ID
        );
        assertThat(count("wms_upgrade_run")).isEqualTo(1L);
        assertThat(count("wms_upgrade_checkpoint")).isEqualTo(1L);

        assertThatThrownBy(() -> executeControlScript(
            "finish", FENCE_TOKEN, runId, "external-verification-complete"
        )).isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
            "select status from wms_upgrade_run where run_id = ?", String.class, runId
        )).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
            "select status from wms_upgrade_checkpoint where run_id = ? and step_code = 'S2_DIRECT_WRITE_FENCE'",
            String.class,
            runId
        )).isEqualTo("VERIFIED");
    }

    @Test
    void twoReadyWritersCanHoldTheSharedAnchorConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> first = executor.submit(concurrentSupplierWriter(
                "CONCURRENT-S-1", ready, start
            ));
            Future<Throwable> second = executor.submit(concurrentSupplierWriter(
                "CONCURRENT-S-2", ready, start
            ));
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(20, TimeUnit.SECONDS)).isNull();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertThat(count("wms_supplier")).isEqualTo(2L);
    }

    private Callable<Throwable> concurrentSupplierWriter(
        String supplierCode,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            WmsCompanyTestAuth.loginAsAdmin();
            try {
                ready.countDown();
                start.await();
                supplierService.insert(supplier(supplierCode, supplierCode));
                return null;
            } catch (Throwable error) {
                return error;
            } finally {
                WmsCompanyTestAuth.clear();
            }
        };
    }

    private Callable<Throwable> threadAsAdmin(ThrowingRunnable action) {
        return () -> {
            WmsCompanyTestAuth.loginAsAdmin();
            try {
                action.run();
                return null;
            } catch (Throwable error) {
                return error;
            } finally {
                WmsCompanyTestAuth.clear();
            }
        };
    }

    private void reRegisterDirectWrite() throws Exception {
        jdbcTemplate.update(
            "delete from wms_schema_version where component_code = ? and schema_version = ?",
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        executeRegistrationScript();
    }

    private void executeRegistrationScript() throws Exception {
        String sql = Files.readString(
            WmsCompanyTestDatabase.scriptPath(
                "script/sql/iws_inventory_direct_write_fence_register.sql"
            ),
            StandardCharsets.UTF_8
        ).replace(
            "SET @target_schema = 'innolux-iws';",
            "SET @target_schema = 'iws_wms_test';"
        );
        executeScript(sql);
    }

    private void executeControlScript(
        String action,
        String token,
        Long runId,
        String evidence
    ) throws Exception {
        String sql = Files.readString(
            WmsCompanyTestDatabase.scriptPath(
                "script/sql/iws_inventory_direct_write_fence_control.sql"
            ),
            StandardCharsets.UTF_8
        )
            .replace(
                "SET @target_schema = 'innolux-iws';",
                "SET @target_schema = 'iws_wms_test';"
            )
            .replace("SET @action = 'begin';", "SET @action = '" + action + "';")
            .replace(
                "SET @run_id = NULL;",
                "SET @run_id = " + (runId == null ? "NULL" : runId) + ";"
            )
            .replace(
                "SET @write_fence_token = '';",
                "SET @write_fence_token = '" + token + "';"
            )
            .replace(
                "SET @unfreeze_evidence = '';",
                "SET @unfreeze_evidence = '" + evidence + "';"
            );
        executeScript(sql);
    }

    private void executeScript(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
            WmsCompanyTestDatabase.jdbcUrl(),
            WmsCompanyTestDatabase.username(),
            WmsCompanyTestDatabase.password()
        )) {
            ScriptUtils.executeSqlScript(
                connection,
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))
            );
        }
    }

    private void insertRun(
        String token,
        String status,
        String phase,
        LocalDateTime completedAt
    ) {
        insertRun(
            token,
            WmsDirectWriteSchemaContract.RELEASE_ID,
            status,
            phase,
            WmsDirectWriteSchemaContract.TARGET_FINGERPRINT,
            completedAt
        );
    }

    private void insertRun(
        String token,
        String releaseId,
        String status,
        String phase,
        String fingerprint,
        LocalDateTime completedAt
    ) {
        jdbcTemplate.update(
            "insert into wms_upgrade_run "
                + "(release_id, source_fingerprint, target_fingerprint, phase, "
                + "write_fence_token, status, attempt_count, started_by, started_at, "
                + "updated_at, completed_at) "
                + "values (?, ?, ?, ?, ?, ?, 0, 'TEST', current_timestamp(3), "
                + "current_timestamp(3), ?)",
            releaseId,
            fingerprint,
            fingerprint,
            phase,
            token,
            status,
            completedAt
        );
    }

    private void insertCompletionRegistration(
        String componentCode,
        String schemaVersion,
        String releaseId,
        String fingerprint
    ) {
        jdbcTemplate.update(
            "insert into wms_schema_version "
                + "(component_code, schema_version, release_id, source_fingerprint, "
                + "target_fingerprint, status, applied_by, applied_at, remark) "
                + "values (?, ?, ?, ?, ?, 'VERIFIED', 'TEST', current_timestamp(3), ?)",
            componentCode,
            schemaVersion,
            releaseId,
            fingerprint,
            fingerprint,
            "测试完成证据"
        );
    }

    private void assertSchemaRejected(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("SCHEMA_STATE_UNSUPPORTED");
    }

    private long requiredCompanyId(String companyCode) {
        return jdbcTemplate.queryForObject(
            "select company_id from wms_company where company_code = ?",
            Long.class,
            companyCode
        );
    }

    private long requiredSupplierId(String supplierCode) {
        return jdbcTemplate.queryForObject(
            "select supplier_id from wms_supplier where supplier_code = ?",
            Long.class,
            supplierCode
        );
    }

    private long schemaRegistrationCount() {
        return jdbcTemplate.queryForObject(
            "select count(*) from wms_schema_version where component_code = ? and schema_version = ?",
            Long.class,
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private long failureAttemptCount() {
        return jdbcTemplate.queryForObject(
            "select count(*) from wms_command_attempt where status = 'FAILED'",
            Long.class
        );
    }

    private static WmsCompanyCreateDTO createCommand(String code, String name, String commandKey) {
        WmsCompanyCreateDTO command = new WmsCompanyCreateDTO();
        command.setCompanyCode(code);
        command.setCompanyName(name);
        command.setRemark("直接写入门禁测试");
        command.setCommandKey(commandKey);
        return command;
    }

    private static WmsCompanyUpdateDTO companyUpdate(
        long companyId,
        String name,
        long revision,
        String commandKey
    ) {
        WmsCompanyUpdateDTO command = new WmsCompanyUpdateDTO();
        command.setCompanyId(companyId);
        command.setCompanyName(name);
        command.setExpectedRevision(revision);
        command.setCommandKey(commandKey);
        command.setReason("直接写入门禁测试");
        return command;
    }

    private static WmsCompanyEnableCommand enableCommand(long revision, String commandKey) {
        WmsCompanyEnableCommand command = new WmsCompanyEnableCommand();
        command.setExpectedRevision(revision);
        command.setCommandKey(commandKey);
        command.setReason("直接写入门禁测试");
        return command;
    }

    private static WmsCompanyManagementScopeReplaceCommand scopeCommand(
        long companyId,
        long revision,
        String commandKey,
        Long... roleIds
    ) {
        WmsCompanyManagementScopeReplaceCommand command =
            new WmsCompanyManagementScopeReplaceCommand();
        command.setCompanyId(companyId);
        command.setExpectedScopeRevision(revision);
        command.setCommandKey(commandKey);
        command.setRoleIds(List.of(roleIds));
        command.setReason("直接写入门禁测试");
        return command;
    }

    private static Map<String, Object> supplier(String code, String name) {
        return Map.of(
            "supplierCode", code,
            "supplierName", name,
            "contactPerson", "测试联系人",
            "status", 1
        );
    }

    private static WmsSupplierImportVo importSupplier(String code, String name) {
        WmsSupplierImportVo row = new WmsSupplierImportVo();
        row.setSupplierCode(code);
        row.setSupplierName(name);
        row.setStatus("1");
        return row;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30L, TimeUnit.SECONDS)) {
                throw new AssertionError("等待测试信号超时");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试信号被中断", error);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }
}
