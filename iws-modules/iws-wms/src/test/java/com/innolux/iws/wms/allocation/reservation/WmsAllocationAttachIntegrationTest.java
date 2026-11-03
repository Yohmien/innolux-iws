/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划
 * @Version      : V1.0
 * @Description  : 验证正式目录、三运行门禁、计划发布与 QUEUE attach 幂等；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.AttachResult;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.process.service.PlanInstanceService;
import com.innolux.iws.wms.allocation.process.WmsAllocationPlanDefinitionFactory;
import com.innolux.iws.wms.catalog.WmsInventoryCatalogContract;
import com.innolux.iws.wms.rule.process.WmsRulePlanContract;
import com.innolux.iws.wms.rule.process.WmsRulePlanDefinitionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分配计划正式挂载隔离测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationPlanPublishTestContext.class)
class WmsAllocationAttachIntegrationTest {

    private static final long SCHEDULE_ID = 8_001L;

    private static final String BUSINESS_TYPE = "INBOUND_FIXTURE";

    @Autowired
    private PlanDefinitionService definitionService;

    @Autowired
    private PlanInstanceService instanceService;

    @Autowired
    private ProcessProperties processProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCatalogAndPublishPlans() {
        cleanProcessAndCatalog();
        jdbcTemplate.update("""
            INSERT INTO iws_business_schedule
                (id, business_code, business_name, enabled, fairness_window_size,
                 revision, del_flag, create_time, update_time)
            VALUES (?, 'INBOUND', '测试入库', 1, 32, 0, '0', CURRENT_TIMESTAMP(3),
                    CURRENT_TIMESTAMP(3))
            """, SCHEDULE_ID);
        jdbcTemplate.update("""
            INSERT INTO iws_business_schedule_plan_type
                (id, schedule_id, plan_type, plan_type_name, priority, enabled,
                 create_time, update_time)
            VALUES
                (8101, ?, 'ALLOCATION_LOCK', '分配锁位', 10, 1,
                 CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
                (8102, ?, 'ALLOCATION_RELEASE', '分配释放', 20, 1,
                 CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """, SCHEDULE_ID, SCHEDULE_ID);
        jdbcTemplate.update("""
            INSERT INTO wms_business_type
                (business_type_code, business_type_name, status, contract_version,
                 object_policy_key, revision, create_time)
            VALUES (?, '入库测试业务', 'ACTIVE', ?, 'WMS_INBOUND_TEST', 1,
                    CURRENT_TIMESTAMP(3))
            """, BUSINESS_TYPE, WmsInventoryCatalogContract.CONTRACT_VERSION);
        jdbcTemplate.update("""
            INSERT INTO wms_business_plan_mount
                (mount_id, business_type_code, plan_type_code, schedule_code,
                 contract_version, enabled, revision, create_time)
            VALUES
                (8201, ?, 'ALLOCATION_LOCK', 'INBOUND', ?, 1, 1,
                 CURRENT_TIMESTAMP(3)),
                (8202, ?, 'ALLOCATION_RELEASE', 'INBOUND', ?, 1, 1,
                 CURRENT_TIMESTAMP(3))
            """, BUSINESS_TYPE, WmsInventoryCatalogContract.CONTRACT_VERSION,
            BUSINESS_TYPE, WmsInventoryCatalogContract.CONTRACT_VERSION);

        publish(WmsAllocationPlanDefinitionFactory.lockDraft(
            "WMS_TEST_ALLOCATION_LOCK",
            "WMS测试分配锁位",
            "INBOUND"
        ));
        publish(WmsAllocationPlanDefinitionFactory.supervisorLockDraft(
            "WMS_TEST_ALLOCATION_LOCK_SUPERVISOR",
            "WMS测试分配锁位监督",
            "INBOUND"
        ));
        publish(WmsAllocationPlanDefinitionFactory.releaseDraft(
            "WMS_TEST_ALLOCATION_RELEASE",
            "WMS测试分配释放",
            "INBOUND"
        ));
        publish(WmsAllocationPlanDefinitionFactory.supervisorReleaseDraft(
            "WMS_TEST_ALLOCATION_RELEASE_SUPERVISOR",
            "WMS测试分配释放监督",
            "INBOUND"
        ));
        processProperties.getScheduler().setEnabled(true);
    }

    @Test
    void rejectsUnavailableQueueRuntimeBeforeCreatingAnyInstance() {
        processProperties.getScheduler().setEnabled(false);

        assertThatThrownBy(() -> instanceService.attach(command("a".repeat(64))))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("QUEUE_RUNTIME_UNAVAILABLE");
        assertThat(instanceCount()).isZero();
    }

    @Test
    void attachesOneBusinessInstanceAndReplaysTheOriginalTaskKey() {
        AttachTaskCommand command = command("b".repeat(64));

        AttachResult first = instanceService.attach(command);
        AttachResult replay = instanceService.attach(command);

        assertThat(first.queueStatus()).isEqualTo("WAITING_PLAN_SLOT");
        assertThat(replay.queueStatus()).isNull();
        assertThat(replay.instanceId()).isEqualTo(first.instanceId());
        assertThat(instanceCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM iws_process_action_audit
            WHERE action_code = 'ATTACH'
              AND instance_id = ?
            """, Integer.class, Long.parseLong(first.instanceId()))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM iws_process_instance
            WHERE plan_role = 'BUSINESS'
              AND business_task_key = 'INBOUND_FIXTURE:TASK-1'
            """, Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> instanceService.attach(command("c".repeat(64))))
            .hasMessageContaining("输入摘要不同");
        assertThat(instanceCount()).isEqualTo(2);
    }

    @Test
    void rejectsDisabledCatalogMountBeforeCreatingAnyInstance() {
        jdbcTemplate.update("""
            UPDATE wms_business_plan_mount
            SET enabled = 0
            WHERE business_type_code = ?
              AND plan_type_code = 'ALLOCATION_LOCK'
            """, BUSINESS_TYPE);

        assertThatThrownBy(() -> instanceService.attach(command("d".repeat(64))))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("MOUNT_NOT_ALLOWED");
        assertThat(instanceCount()).isZero();
    }

    @Test
    void publishesRuleValidationAndActivationPlanWithPlatformCapabilities() {
        publish(WmsRulePlanDefinitionFactory.publishDraft(
            "WMS_TEST_RULESET_PUBLISH",
            "INVENTORY_CONFIG"
        ));

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM iws_process_definition
            WHERE plan_code = 'WMS_TEST_RULESET_PUBLISH'
              AND status = 'ACTIVE'
            """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM iws_process_event_definition e
            JOIN iws_process_definition d
              ON d.id = e.definition_version_id
            WHERE d.plan_code = 'WMS_TEST_RULESET_PUBLISH'
              AND e.event_code IN (?, ?)
            """, Integer.class,
            WmsRulePlanContract.EVENT_VALIDATE,
            WmsRulePlanContract.EVENT_ACTIVATE)).isEqualTo(2);
    }

    private AttachTaskCommand command(String inputHash) {
        return new AttachTaskCommand(
            SCHEDULE_ID,
            WmsAllocationPlanDefinitionFactory.PLAN_TYPE_LOCK,
            BUSINESS_TYPE,
            "BIZ-0001",
            "TASK-1",
            false,
            inputHash,
            List.of(),
            "ATTACH-TASK-1"
        );
    }

    private void publish(com.innolux.iws.process.api.DefinitionDraftBo draft) {
        DefinitionDetailVo created = definitionService.createDraft(
            draft,
            "create-" + draft.planCode(),
            "隔离挂载测试"
        );
        long versionId = Long.parseLong(created.summary().versionId());
        DefinitionValidationVo validation = definitionService.validate(versionId);
        assertThat(validation.errors()).isEmpty();
        assertThat(validation.valid()).isTrue();
        DefinitionValidationVo published = definitionService.publish(
            versionId,
            Long.parseLong(created.summary().revision()),
            "publish-" + draft.planCode(),
            "隔离挂载测试"
        );
        assertThat(published.valid()).isTrue();
    }

    private int instanceCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM iws_process_instance",
            Integer.class
        );
    }

    private void cleanProcessAndCatalog() {
        jdbcTemplate.update("delete from iws_process_action_audit");
        jdbcTemplate.update("delete from iws_process_request_detail");
        jdbcTemplate.update("delete from iws_process_trigger_receipt");
        jdbcTemplate.update("delete from iws_process_attempt");
        jdbcTemplate.update("delete from iws_process_event");
        jdbcTemplate.update("delete from iws_process_token");
        jdbcTemplate.update("delete from iws_process_system_context");
        jdbcTemplate.update("delete from iws_process_schedule_control");
        jdbcTemplate.update("delete from iws_process_instance");
        jdbcTemplate.update("delete from iws_process_edge");
        jdbcTemplate.update("delete from iws_process_system_policy");
        jdbcTemplate.update("delete from iws_process_event_definition");
        jdbcTemplate.update("delete from iws_process_state");
        jdbcTemplate.update("delete from iws_process_definition");
        jdbcTemplate.update("delete from wms_business_plan_mount");
        jdbcTemplate.update("delete from wms_business_type");
        jdbcTemplate.update("delete from iws_business_schedule_plan_type");
        jdbcTemplate.update("delete from iws_business_schedule");
    }
}
