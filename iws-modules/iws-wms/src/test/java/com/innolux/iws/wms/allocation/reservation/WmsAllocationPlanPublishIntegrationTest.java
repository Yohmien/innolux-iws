/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划
 * @Version      : V1.0
 * @Description  : 通过真实计划定义服务创建、校验并发布两张调度级监督计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.wms.allocation.process.WmsAllocationPlanDefinitionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** 分配计划正式持久化测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationPlanPublishTestContext.class)
class WmsAllocationPlanPublishIntegrationTest {

    @Autowired
    private PlanDefinitionService definitionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDefinitions() {
        jdbcTemplate.update("delete from iws_process_action_audit");
        jdbcTemplate.update("delete from iws_process_edge");
        jdbcTemplate.update("delete from iws_process_system_policy");
        jdbcTemplate.update("delete from iws_process_event_definition");
        jdbcTemplate.update("delete from iws_process_state");
        jdbcTemplate.update("delete from iws_process_definition");
    }

    @Test
    void createsValidatesAndPublishesLockAndReleaseDefinitions() {
        long lockVersionId = publish(definitionService.createDraft(
            WmsAllocationPlanDefinitionFactory.lockDraft(
                "WMS_TEST_ALLOCATION_LOCK",
                "WMS测试分配锁位",
                "INBOUND"
            ),
            "create-lock",
            "隔离发布测试"
        ));
        long lockSupervisorVersionId = publish(definitionService.createDraft(
            WmsAllocationPlanDefinitionFactory.supervisorLockDraft(
                "WMS_TEST_ALLOCATION_LOCK_SUPERVISOR",
                "WMS测试分配锁位监督",
                "INBOUND"
            ),
            "create-lock-supervisor",
            "隔离发布测试"
        ));
        long releaseVersionId = publish(definitionService.createDraft(
            WmsAllocationPlanDefinitionFactory.releaseDraft(
                "WMS_TEST_ALLOCATION_RELEASE",
                "WMS测试分配释放",
                "INBOUND"
            ),
            "create-release",
            "隔离发布测试"
        ));
        long releaseSupervisorVersionId = publish(definitionService.createDraft(
            WmsAllocationPlanDefinitionFactory.supervisorReleaseDraft(
                "WMS_TEST_ALLOCATION_RELEASE_SUPERVISOR",
                "WMS测试分配释放监督",
                "INBOUND"
            ),
            "create-release-supervisor",
            "隔离发布测试"
        ));

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM iws_process_definition
            WHERE business_schedule_code = 'INBOUND'
              AND plan_type IN ('ALLOCATION_LOCK', 'ALLOCATION_RELEASE')
              AND status = 'ACTIVE'
            """, Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM iws_process_definition
            WHERE business_schedule_code = 'INBOUND'
              AND plan_type IN ('ALLOCATION_LOCK', 'ALLOCATION_RELEASE')
              AND plan_role = 'SUPERVISOR'
              AND mode = 'QUEUE'
              AND status = 'ACTIVE'
            """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM iws_process_event_definition
            WHERE definition_version_id IN (?, ?, ?, ?)
              AND event_direction = 'INTERNAL'
              AND local_action_version = '1'
            """, Integer.class, lockVersionId, lockSupervisorVersionId,
            releaseVersionId, releaseSupervisorVersionId)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM iws_process_event_definition
            WHERE definition_version_id IN (?, ?)
              AND local_action_key IN ('SUPERVISOR_CANDIDATE_DECISION', 'SUPERVISOR_ADMIT_QUEUE')
            """, Integer.class, lockSupervisorVersionId, releaseSupervisorVersionId))
            .isEqualTo(4);
    }

    private long publish(DefinitionDetailVo draft) {
        long versionId = Long.parseLong(draft.summary().versionId());
        DefinitionValidationVo validation = definitionService.validate(versionId);
        assertThat(validation.errors()).isEmpty();
        assertThat(validation.valid()).isTrue();
        DefinitionValidationVo published = definitionService.publish(
            versionId,
            Long.parseLong(draft.summary().revision()),
            "publish-" + draft.summary().planCode(),
            "隔离发布测试"
        );
        assertThat(published.valid()).isTrue();
        return versionId;
    }
}
