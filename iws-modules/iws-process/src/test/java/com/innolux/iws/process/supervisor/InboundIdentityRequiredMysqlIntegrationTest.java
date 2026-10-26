/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 缺少可信入站身份适配时拒绝受理的真实 MySQL 验收；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.supervisor;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.DefaultPlanScopeResolver;
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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 未绑定可信入站身份适配器时的拒绝行为。 */
@Tag("dev")
@SpringBootTest(classes = {SupervisorFixtureApplication.class, PlanInstanceServiceImpl.class,
    PlanMaterializer.class, DefaultPlanScopeResolver.class, PlanInstanceActionPolicy.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN", "iws.process.enabled=true"})
@EnabledIfSystemProperty(named = "iws.process.test.mysql-datadir", matches = ".+")
class InboundIdentityRequiredMysqlIntegrationTest {

    @Autowired PlanInstanceService planInstanceService;

    @Autowired JdbcTemplate jdbcTemplate;

    @Autowired ProcessInstanceMapper instanceMapper;

    @Autowired ProcessEventDefinitionMapper eventDefinitionMapper;

    @Autowired ProcessEventMapper eventMapper;

    @BeforeEach
    void reset() throws Exception {
        InboundMysqlTestSupport.reset(jdbcTemplate);
        SupervisorFixtureApplication.allowAllObjectScopes();
        InboundMysqlTestSupport.installSaToken();
        InboundMysqlTestSupport.login(1001L);
        InboundMysqlTestSupport.seedInbound(instanceMapper, eventDefinitionMapper, eventMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        InboundMysqlTestSupport.logoutAndClear();
    }

    @Test
    void receiveTriggerRejectsMissingInboundIdentityPolicyWithoutSideEffects() {
        assertThatThrownBy(() -> planInstanceService.receiveTrigger(InboundMysqlTestSupport.command(
            "SRC-NO-POLICY", InboundMysqlTestSupport.EFFECT_KEY, "payload-no-policy")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("入站身份未绑定可信来源合同，拒绝受理");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM iws_process_trigger_receipt", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM iws_process_request_detail", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_process_action_audit"
            + " WHERE action_code='TRIGGER' AND instance_id=?", Long.class,
            InboundMysqlTestSupport.INSTANCE_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT payload_ready FROM iws_process_event WHERE id=?",
            Boolean.class, InboundMysqlTestSupport.EVENT_ID)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT input_hash FROM iws_process_event WHERE id=?",
            String.class, InboundMysqlTestSupport.EVENT_ID)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT event_version FROM iws_process_event WHERE id=?",
            Long.class, InboundMysqlTestSupport.EVENT_ID)).isEqualTo(1L);
    }
}
