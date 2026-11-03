/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 可信入站策略、请求记录、canonical 回执与并发受理真实 MySQL 验收；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.supervisor;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.ReceiveTriggerCommand;
import com.innolux.iws.process.api.TriggerReceiptResult;
import com.innolux.iws.process.domain.ProcessTriggerReceipt;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.ProcessingStatus;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.runtime.DefaultPlanScopeResolver;
import com.innolux.iws.process.runtime.InboundIdentityPolicy;
import com.innolux.iws.process.runtime.PlanInstanceActionPolicy;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.service.PlanInstanceService;
import com.innolux.iws.process.service.impl.PlanInstanceServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 入站触发真实 MySQL 集成验收。 */
@Tag("dev")
@SpringBootTest(classes = {SupervisorFixtureApplication.class, PlanInstanceServiceImpl.class,
    PlanMaterializer.class, DefaultPlanScopeResolver.class, PlanInstanceActionPolicy.class,
    InboundReceiveTriggerMysqlIntegrationTest.InboundPolicyConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN", "iws.process.enabled=true"})
@EnabledIfSystemProperty(named = "iws.process.test.mysql-datadir", matches = ".+")
class InboundReceiveTriggerMysqlIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 8;

    private static final InboundTestPolicy POLICY = new InboundTestPolicy();

    @Autowired PlanInstanceService planInstanceService;

    @Autowired JdbcTemplate jdbcTemplate;

    @Autowired ProcessInstanceMapper instanceMapper;

    @Autowired ProcessEventDefinitionMapper eventDefinitionMapper;

    @Autowired ProcessEventMapper eventMapper;

    @Autowired ProcessTriggerReceiptMapper receiptMapper;

    @BeforeEach
    void reset() throws Exception {
        InboundMysqlTestSupport.reset(jdbcTemplate);
        SupervisorFixtureApplication.allowAllObjectScopes();
        InboundMysqlTestSupport.installSaToken();
        InboundMysqlTestSupport.login(1001L);
        POLICY.enabled.set(true);
        POLICY.rendezvous.set(null);
        POLICY.logins.clear();
        InboundMysqlTestSupport.seedInbound(instanceMapper, eventDefinitionMapper, eventMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        InboundMysqlTestSupport.logoutAndClear();
        POLICY.rendezvous.set(null);
    }

    @Test
    void receiveTriggerPersistsAppliedReceiptAndInboundRequestRecord() {
        ReceiveTriggerCommand command = InboundMysqlTestSupport.command("SRC-INBOUND-1",
            InboundMysqlTestSupport.EFFECT_KEY, "payload-sha-1");

        TriggerReceiptResult result = planInstanceService.receiveTrigger(command);

        assertThat(result.accepted()).isTrue();
        assertThat(result.duplicate()).isFalse();
        assertThat(result.processingStatus()).isEqualTo("APPLIED");
        assertThat(result.eventId()).isEqualTo(String.valueOf(InboundMysqlTestSupport.EVENT_ID));

        ProcessTriggerReceipt receipt = receiptMapper.selectById(Long.valueOf(result.receiptId()));
        assertThat(receipt).isNotNull();
        assertThat(receipt.getCanonical()).isTrue();
        assertThat(receipt.getProcessingStatus()).isEqualTo(ProcessingStatus.APPLIED);
        assertThat(receipt.getPurpose()).isEqualTo(CallPurpose.TRIGGER_EVENT);
        assertThat(receipt.getCallerSystemCode()).isEqualTo(InboundMysqlTestSupport.CALLER_SYSTEM);
        assertThat(receipt.getModuleCode()).isEqualTo(InboundMysqlTestSupport.MODULE_CODE);
        assertThat(receipt.getSourceRequestId()).isEqualTo("SRC-INBOUND-1");
        assertThat(receipt.getEffectKey()).isEqualTo(InboundMysqlTestSupport.EFFECT_KEY);
        assertThat(receipt.getPayloadHash()).isEqualTo("payload-sha-1");
        assertThat(receipt.getInstanceId()).isEqualTo(InboundMysqlTestSupport.INSTANCE_ID);
        assertThat(receipt.getEventId()).isEqualTo(InboundMysqlTestSupport.EVENT_ID);

        assertThat(POLICY.logins).contains("1001");
        assertThat(requestRows()).hasSize(1);
        RequestRow request = requestRows().getFirst();
        assertThat(request.requestId()).isNotBlank();
        assertThat(request.direction()).isEqualTo("INBOUND");
        assertThat(request.purpose()).isEqualTo("TRIGGER_EVENT");
        assertThat(request.sourceRequestId()).isEqualTo("SRC-INBOUND-1");
        assertThat(request.effectKey()).isEqualTo(InboundMysqlTestSupport.EFFECT_KEY);
        assertThat(request.receiptId()).isEqualTo(Long.parseLong(result.receiptId()));
        assertThat(request.eventId()).isEqualTo(InboundMysqlTestSupport.EVENT_ID);
        assertThat(request.resultCode()).isEqualTo("RECEIVED");
        assertThat(request.sent()).isFalse();
        assertThat(request.requestSummary()).contains("payload-sha-1");

        EventRow event = eventRow();
        assertThat(event.status()).isEqualTo("PENDING");
        assertThat(event.payloadReady()).isTrue();
        assertThat(event.inputHash()).isEqualTo("payload-sha-1");
        assertThat(event.version()).isEqualTo(3L);
        assertThat(requestCount()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);

        TriggerReceiptResult replay = planInstanceService.receiveTrigger(command);
        assertThat(replay.accepted()).isTrue();
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.receiptId()).isEqualTo(result.receiptId());
        assertThat(replay.eventId()).isEqualTo(result.eventId());
        assertThat(receiptCount()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void receiveTriggerRejectsDeniedInboundPolicyWithoutSideEffects() {
        POLICY.enabled.set(false);

        assertThatThrownBy(() -> planInstanceService.receiveTrigger(InboundMysqlTestSupport.command(
            "SRC-DENIED", InboundMysqlTestSupport.EFFECT_KEY, "payload-denied")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("入站身份未绑定可信来源合同，拒绝受理");

        assertThat(POLICY.logins).contains("1001");
        assertThat(receiptCount()).isZero();
        assertThat(requestCount()).isZero();
        assertThat(auditCount()).isZero();
        EventRow event = eventRow();
        assertThat(event.payloadReady()).isFalse();
        assertThat(event.inputHash()).isNull();
        assertThat(event.version()).isEqualTo(1L);
    }

    @Test
    void receiveTriggerRejectsDifferentPayloadForSameCanonicalEffect() {
        planInstanceService.receiveTrigger(InboundMysqlTestSupport.command("SRC-CANONICAL",
            InboundMysqlTestSupport.EFFECT_KEY, "payload-a"));

        assertThatThrownBy(() -> planInstanceService.receiveTrigger(InboundMysqlTestSupport.command(
            "SRC-CONFLICT", InboundMysqlTestSupport.EFFECT_KEY, "payload-b")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("同一入站事件已有不同效果键或载荷摘要的 canonical 回执");

        assertThat(receiptCount()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM iws_process_trigger_receipt WHERE source_request_id='SRC-CONFLICT'",
            Long.class)).isZero();
        EventRow event = eventRow();
        assertThat(event.inputHash()).isEqualTo("payload-a");
        assertThat(event.version()).isEqualTo(3L);
    }

    @Test
    void concurrentReceiveTriggerCreatesOnlyOneCanonicalReceipt() throws Exception {
        POLICY.rendezvous.set(new CyclicBarrier(CONCURRENT_REQUESTS));
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TriggerReceiptResult>> futures = new ArrayList<>();
        for (int index = 0; index < CONCURRENT_REQUESTS; index++) {
            final int requestIndex = index;
            futures.add(executor.submit(() -> {
                InboundMysqlTestSupport.login(2000L + requestIndex);
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发入站启动超时");
                    }
                    return planInstanceService.receiveTrigger(InboundMysqlTestSupport.command(
                        "SRC-CONCURRENT-" + requestIndex, InboundMysqlTestSupport.EFFECT_KEY,
                        "payload-shared"));
                } finally {
                    InboundMysqlTestSupport.logoutAndClear();
                }
            }));
        }
        start.countDown();

        List<TriggerReceiptResult> results = new ArrayList<>();
        try {
            for (Future<TriggerReceiptResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(POLICY.logins).hasSize(CONCURRENT_REQUESTS);
        assertThat(results).hasSize(CONCURRENT_REQUESTS).allMatch(TriggerReceiptResult::accepted);
        assertThat(results.stream().filter(item -> "APPLIED".equals(item.processingStatus())).count())
            .isEqualTo(1);
        assertThat(results.stream().filter(item -> "DUPLICATE".equals(item.processingStatus())).count())
            .isEqualTo(CONCURRENT_REQUESTS - 1);
        assertThat(canonicalCount()).isEqualTo(1);
        assertThat(receiptCount()).isEqualTo(CONCURRENT_REQUESTS);
        assertThat(requestCount()).isEqualTo(CONCURRENT_REQUESTS);
        assertThat(requestRows()).allMatch(item -> "INBOUND".equals(item.direction())
            && "RECEIVED".equals(item.resultCode()));
        assertThat(eventRow().status()).isEqualTo("PENDING");
    }

    private EventRow eventRow() {
        return jdbcTemplate.queryForObject("SELECT event_status, payload_ready, input_hash, event_version"
            + " FROM iws_process_event WHERE id=?", (rs, rowNum) -> new EventRow(rs.getString(1),
            rs.getBoolean(2), rs.getString(3), rs.getLong(4)), InboundMysqlTestSupport.EVENT_ID);
    }

    private List<RequestRow> requestRows() {
        return jdbcTemplate.query("SELECT request_id, direction, purpose, source_request_id, effect_key,"
                + " receipt_id, event_id, result_code, sent, request_summary"
                + " FROM iws_process_request_detail ORDER BY id",
            (rs, rowNum) -> new RequestRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                rs.getBoolean(9), rs.getString(10)));
    }

    private long receiptCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_process_trigger_receipt", Long.class);
    }

    private long canonicalCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM iws_process_trigger_receipt WHERE canonical=1", Long.class);
    }

    private long requestCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_process_request_detail", Long.class);
    }

    private long auditCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_process_action_audit"
            + " WHERE action_code='TRIGGER' AND instance_id=?", Long.class, InboundMysqlTestSupport.INSTANCE_ID);
    }

    /** 入站身份测试策略；必须通过真实 Sa-Token 登录后才接收声明来源。 */
    static final class InboundTestPolicy implements InboundIdentityPolicy {

        private final AtomicBoolean enabled = new AtomicBoolean(true);

        private final AtomicReference<CyclicBarrier> rendezvous = new AtomicReference<>();

        private final Set<String> logins = ConcurrentHashMap.newKeySet();

        @Override
        public boolean accepts(ReceiveTriggerCommand command) {
            if (!StpUtil.isLogin()) {
                return false;
            }
            logins.add(String.valueOf(StpUtil.getLoginId()));
            if (!enabled.get()) {
                return false;
            }
            if (!InboundMysqlTestSupport.CALLER_SYSTEM.equals(command.callerSystemCode())) {
                return false;
            }
            CyclicBarrier barrier = rendezvous.get();
            if (barrier != null) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("并发入站策略会合失败", exception);
                }
            }
            return true;
        }
    }

    /** 仅在可信策略用例中提供入站身份适配器。 */
    @Configuration(proxyBeanMethods = false)
    static class InboundPolicyConfiguration {

        @Bean
        InboundIdentityPolicy inboundIdentityPolicy() {
            return POLICY;
        }
    }

    private record EventRow(String status, boolean payloadReady, String inputHash, long version) {
    }

    private record RequestRow(String requestId, String direction, String purpose, String sourceRequestId,
                              String effectKey, long receiptId, long eventId, String resultCode,
                              boolean sent, String requestSummary) {
    }
}
