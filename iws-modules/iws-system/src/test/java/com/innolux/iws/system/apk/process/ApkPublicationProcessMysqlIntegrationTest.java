/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 真实定义初始化、IMMEDIATE 发布、检查点与幂等集成验收；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.ImmediateExecutionVO;
import com.innolux.iws.process.runtime.ImmediateExecutionService;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.enums.ApkReleaseStatus;
import com.innolux.iws.system.apk.mapper.ApkReleaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实平台发布兼容链隔离验收。
 *
 * <p>启用参数：{@code -Diws.apk.test.enabled=true} 与
 * {@code -Diws.apk.test.mysql-datadir=<apk-mysql-test 数据目录>}。缺少任一参数不会连接数据库。</p>
 */
@SpringBootTest(classes = ApkPublicationFixtureApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN", "iws.process.enabled=true",
        "iws.process.immediate.max-concurrency=1", "iws.process.immediate.response-reserve-ms=100"})
@EnabledIfSystemProperty(named = ApkPublicationMysqlFixture.ENABLED_PROPERTY, matches = "true")
@EnabledIfSystemProperty(named = ApkPublicationMysqlFixture.DATADIR_PROPERTY, matches = ".+")
@Tag("dev")
@Timeout(60)
class ApkPublicationProcessMysqlIntegrationTest {

    private static final String INIT_COMMAND_PREFIX = "APK_IT:INIT";

    private static final String INIT_REASON = "APK 发布集成测试初始化";

    @Autowired
    private ApkPublicationProcess publicationProcess;

    @Autowired
    private PlanDefinitionService definitionService;

    @Autowired
    private ImmediateExecutionService immediateExecutionService;

    @Autowired
    private ApkReleaseMapper releaseMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.innolux.iws.process.runtime.ImmediateExecutionGuard executionGuard;

    @BeforeEach
    void prepareIsolationAndSession() throws Exception {
        SaTokenContextMockUtil.setMockContext();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return List.of(ApkProcessContract.PLAN_ADMIN_PERMISSION, "system:apk:list");
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return List.of();
            }
        });
        StpUtil.login(1001L);
        ApkPublicationMysqlFixture.clean(jdbcTemplate);
    }

    @AfterEach
    void clearSession() {
        try {
            StpUtil.logout();
        } catch (RuntimeException ignored) {
            // 测试上下文关闭阶段无需影响最终断言。
        }
        SaTokenContextMockUtil.clearContext();
    }

    @Test
    void initializesPublishedDefinitionsAndPublishesReleaseOnce() throws Exception {
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_definition WHERE plan_code IN (?, ?)",
            ApkProcessContract.PLAN_CODE, ApkProcessContract.SUPERVISOR_PLAN_CODE));

        PlanProvisioningResult first = publicationProcess.ensurePlan(INIT_COMMAND_PREFIX + ":1", INIT_REASON);
        assertTrue(first.created());
        assertTrue(first.published());
        assertEquals(ApkProcessContract.PLAN_CODE, first.planCode());
        assertEquals(ApkProcessContract.PLAN_TYPE, first.planType());
        assertNotNull(first.supervisorVersionId());
        assertNotNull(first.supervisorRevision());

        DefinitionDetailVo business = definitionService.get(first.versionId());
        DefinitionDetailVo supervisor = definitionService.get(first.supervisorVersionId());
        assertEquals("ACTIVE", business.summary().status());
        assertNotNull(business.summary().publishedAt());
        assertEquals(ApkProcessContract.LOCAL_ACTION_KEY, business.events().getFirst().localActionKey());
        assertEquals(ApkProcessContract.LOCAL_EFFECT_KEY, business.events().getFirst().localEffectKey());
        assertEquals("ACTIVE", supervisor.summary().status());
        assertEquals("IMMEDIATE", supervisor.mode());

        PlanProvisioningResult verified = publicationProcess.ensurePlan(INIT_COMMAND_PREFIX + ":2", INIT_REASON);
        assertFalse(verified.created());
        assertEquals(first.versionId(), verified.versionId());
        assertEquals(first.revision(), verified.revision());
        assertEquals(first.supervisorVersionId(), verified.supervisorVersionId());
        assertEquals(first.supervisorRevision(), verified.supervisorRevision());
        assertEquals(2, count("SELECT COUNT(*) FROM iws_process_definition WHERE plan_code IN (?, ?)"
            + " AND status = 'ACTIVE'", ApkProcessContract.PLAN_CODE, ApkProcessContract.SUPERVISOR_PLAN_CODE));

        ApkRelease release = new ApkRelease();
        release.setReleaseId(ApkPublicationMysqlFixture.TEST_RELEASE_ID);
        release.setAppName("APK 发布集成测试");
        release.setPackageName(ApkPublicationMysqlFixture.TEST_PACKAGE);
        release.setVersionName("1.0.0-it");
        release.setVersionCode(ApkPublicationMysqlFixture.TEST_VERSION_CODE);
        release.setSha256("a".repeat(64));
        release.setFileSize(4096L);
        release.setOssId(9001L);
        release.setStatus(ApkReleaseStatus.UPLOADED.name());
        assertEquals(1, releaseMapper.insert(release));

        long scheduleId = jdbcTemplate.queryForObject(
            "SELECT id FROM iws_business_schedule WHERE business_code = ?", Long.class,
            ApkProcessContract.BUSINESS_CODE);
        String commandKey = "APK_PUBLISH:" + ApkPublicationMysqlFixture.TEST_RELEASE_ID + ":"
            + ApkPublicationMysqlFixture.TEST_INPUT_HASH;
        String instanceId = publicationProcess.publish(ApkPublicationMysqlFixture.TEST_RELEASE_ID,
            ApkPublicationMysqlFixture.TEST_INPUT_HASH);
        long planInstanceId = Long.parseLong(instanceId);

        ImmediateExecutionVO execution = immediateExecutionService.find(scheduleId, commandKey);
        assertEquals("SUCCEEDED", execution.resultCode());
        assertEquals("SUCCEEDED", execution.effectResult());
        assertEquals(instanceId, execution.instanceId());
        assertFalse(execution.reconciliationRequired());

        Map<String, Object> instance = jdbcTemplate.queryForMap(
            "SELECT plan_role, plan_status, business_type, business_id, input_hash, supervisor_instance_id"
                + " FROM iws_process_instance WHERE id = ?", planInstanceId);
        assertEquals("BUSINESS", instance.get("plan_role"));
        assertEquals("COMPLETED", instance.get("plan_status"));
        assertEquals(ApkProcessContract.BUSINESS_TYPE, instance.get("business_type"));
        assertEquals(Long.toString(ApkPublicationMysqlFixture.TEST_RELEASE_ID), instance.get("business_id"));
        assertEquals(ApkPublicationMysqlFixture.TEST_INPUT_HASH, instance.get("input_hash"));
        assertNotNull(instance.get("supervisor_instance_id"));

        Map<String, Object> releaseFact = jdbcTemplate.queryForMap(
            "SELECT status, process_instance_id FROM sys_apk_release WHERE release_id = ?",
            ApkPublicationMysqlFixture.TEST_RELEASE_ID);
        assertEquals("PUBLISHED", releaseFact.get("status"));
        assertEquals(planInstanceId, ((Number) releaseFact.get("process_instance_id")).longValue());

        Map<String, Object> event = jdbcTemplate.queryForMap(
            "SELECT id, event_status, effect_result, local_action_key, local_effect_key, input_hash"
                + " FROM iws_process_event WHERE instance_id = ? AND event_code = ?", planInstanceId,
            ApkProcessContract.EVENT_CODE);
        assertEquals("SUCCESS", event.get("event_status"));
        assertEquals("SUCCEEDED", event.get("effect_result"));
        assertEquals(ApkProcessContract.LOCAL_ACTION_KEY, event.get("local_action_key"));
        assertEquals(ApkProcessContract.LOCAL_EFFECT_KEY, event.get("local_effect_key"));

        Map<String, Object> attempt = jdbcTemplate.queryForMap(
            "SELECT checkpoint_status, effect_result, evidence_ref, checkpoint_json"
                + " FROM iws_process_attempt WHERE event_id = ?", event.get("id"));
        assertEquals("SUCCEEDED", attempt.get("checkpoint_status"));
        assertEquals("SUCCEEDED", attempt.get("effect_result"));
        String digest = sha256(ApkPublicationMysqlFixture.TEST_INPUT_HASH).substring(0, 16);
        assertEquals(ApkProcessContract.EVIDENCE_PREFIX + ApkPublicationMysqlFixture.TEST_RELEASE_ID
            + ":" + digest, attempt.get("evidence_ref"));
        Map<?, ?> checkpoint = objectMapper.readValue(String.valueOf(attempt.get("checkpoint_json")), Map.class);
        assertEquals("PUBLISHED", checkpoint.get("status"));
        assertEquals(ApkPublicationMysqlFixture.TEST_RELEASE_ID,
            ((Number) checkpoint.get("releaseId")).longValue());
        assertEquals(planInstanceId, ((Number) checkpoint.get("planInstanceId")).longValue());
        assertEquals(digest, checkpoint.get("inputHashDigest"));

        int instancesBeforeReplay = count("SELECT COUNT(*) FROM iws_process_instance WHERE business_id = ?",
            Long.toString(ApkPublicationMysqlFixture.TEST_RELEASE_ID));
        int attemptsBeforeReplay = count("SELECT COUNT(*) FROM iws_process_attempt WHERE event_id = ?",
            event.get("id"));
        int auditsBeforeReplay = count("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key = ?",
            commandKey);
        String replayedInstanceId = publicationProcess.publish(ApkPublicationMysqlFixture.TEST_RELEASE_ID,
            ApkPublicationMysqlFixture.TEST_INPUT_HASH);
        assertEquals(instanceId, replayedInstanceId);
        assertEquals(instancesBeforeReplay,
            count("SELECT COUNT(*) FROM iws_process_instance WHERE business_id = ?",
                Long.toString(ApkPublicationMysqlFixture.TEST_RELEASE_ID)));
        assertEquals(attemptsBeforeReplay,
            count("SELECT COUNT(*) FROM iws_process_attempt WHERE event_id = ?", event.get("id")));
        assertEquals(auditsBeforeReplay,
            count("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key = ?", commandKey));

        ServiceException conflict = assertThrows(ServiceException.class,
            () -> publicationProcess.publish(ApkPublicationMysqlFixture.TEST_RELEASE_ID,
                ApkPublicationMysqlFixture.TEST_INPUT_HASH + "-different"));
        assertTrue(conflict.getMessage().contains("BUSY"));
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_instance WHERE business_id = ?",
            Long.toString(ApkPublicationMysqlFixture.TEST_RELEASE_ID)));
        assertEquals("PUBLISHED", jdbcTemplate.queryForObject(
            "SELECT status FROM sys_apk_release WHERE release_id = ?", String.class,
            ApkPublicationMysqlFixture.TEST_RELEASE_ID));
        assertNotNull(execution.startedAt());
        assertNotNull(execution.finishedAt());
        assertTrue(Duration.between(execution.startedAt(), execution.finishedAt()).toMillis() >= 0L);
    }

    @Test
    void retriesOnlyPersistedRejectionBeforeAdmission() {
        publicationProcess.ensurePlan(INIT_COMMAND_PREFIX + ":retry", INIT_REASON);
        jdbcTemplate.update("INSERT INTO sys_apk_release (release_id,app_name,package_name,version_name,"
            + "version_code,sha256,file_size,oss_id,status) VALUES (?,?,?,?,?,?,?,?,?)",
            ApkPublicationMysqlFixture.TEST_RELEASE_ID, "重试测试", ApkPublicationMysqlFixture.TEST_PACKAGE,
            "1.0", 1L, "a".repeat(64), 4096L, 9001L, "UPLOADED");
        assertTrue(executionGuard.tryAcquire());
        try {
            ServiceException error = assertThrows(ServiceException.class, () -> publicationProcess.publish(
                ApkPublicationMysqlFixture.TEST_RELEASE_ID, ApkPublicationMysqlFixture.TEST_INPUT_HASH));
            assertTrue(error.getMessage().contains("NOT_READY"));
            assertEquals(0, count("SELECT COUNT(*) FROM iws_process_instance WHERE plan_role='BUSINESS'"));
        } finally {
            executionGuard.release();
        }
        String first = publicationProcess.publish(ApkPublicationMysqlFixture.TEST_RELEASE_ID,
            ApkPublicationMysqlFixture.TEST_INPUT_HASH);
        assertEquals(first, publicationProcess.publish(ApkPublicationMysqlFixture.TEST_RELEASE_ID,
            ApkPublicationMysqlFixture.TEST_INPUT_HASH));
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_instance WHERE plan_role='BUSINESS'"));
        assertEquals("PUBLISHED", jdbcTemplate.queryForObject("SELECT status FROM sys_apk_release WHERE release_id=?",
            String.class, ApkPublicationMysqlFixture.TEST_RELEASE_ID));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String sha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
