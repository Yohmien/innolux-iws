/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理计划目录
 * @Version      : V1.0
 * @Description  : 验证规则、空间和逻辑合并 provision 的持久发布与重复启动幂等；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergePlanContract;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergePlanProvisioner;
import com.innolux.iws.wms.rule.process.WmsRulePlanContract;
import com.innolux.iws.wms.rule.process.WmsRulePlanProvisioner;
import com.innolux.iws.wms.space.change.process.WmsSpacePlanContract;
import com.innolux.iws.wms.space.change.process.WmsSpacePlanProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** WMS 计划定义 provision 集成测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationPlanPublishTestContext.class)
class WmsInventoryPlanProvisionerIntegrationTest {

    private static final List<String> RULE_CODES = List.of(
        WmsRulePlanContract.PLAN_CODE,
        WmsRulePlanContract.SUPERVISOR_PLAN_CODE
    );

    private static final List<String> SPACE_CODES = List.of(
        WmsSpacePlanContract.CHANGE_PLAN_CODE,
        WmsSpacePlanContract.CHANGE_SUPERVISOR_PLAN_CODE,
        WmsSpacePlanContract.RETIRE_PLAN_CODE,
        WmsSpacePlanContract.RETIRE_SUPERVISOR_PLAN_CODE
    );

    private static final List<String> LOGICAL_MERGE_CODES = List.of(
        WmsLogicalMergePlanContract.BATCH_PLAN_CODE,
        WmsLogicalMergePlanContract.BATCH_SUPERVISOR_PLAN_CODE,
        WmsLogicalMergePlanContract.CLOSE_PLAN_CODE,
        WmsLogicalMergePlanContract.CLOSE_SUPERVISOR_PLAN_CODE
    );

    @Autowired
    private PlanDefinitionService definitions;

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
    void provisionsEveryRequiredDefinitionOnceAndReplaysOnRestart() {
        ObjectProvider<PlanDefinitionService> provider = provider();
        WmsRulePlanProvisioner rules = new WmsRulePlanProvisioner(provider);
        WmsSpacePlanProvisioner spaces = new WmsSpacePlanProvisioner(provider);
        WmsLogicalMergePlanProvisioner merges =
            new WmsLogicalMergePlanProvisioner(provider);

        rules.run(null);
        spaces.run(null);
        merges.run(null);
        rules.run(null);
        spaces.run(null);
        merges.run(null);

        List<String> expected = java.util.stream.Stream.of(
                RULE_CODES, SPACE_CODES, LOGICAL_MERGE_CODES)
            .flatMap(List::stream)
            .toList();
        assertThat(activeCount(expected)).isEqualTo(expected.size());
        assertThat(duplicateActiveDefinitions()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*)
              from iws_process_definition
             where plan_code = ?
               and plan_role = 'BUSINESS'
               and business_type = ?
               and status = 'ACTIVE'
            """, Integer.class,
            WmsRulePlanContract.PLAN_CODE,
            WmsRulePlanContract.BUSINESS_TYPE)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*)
              from iws_process_definition
             where plan_code = ?
               and plan_role = 'BUSINESS'
               and business_type = ?
               and status = 'ACTIVE'
            """, Integer.class,
            WmsSpacePlanContract.CHANGE_PLAN_CODE,
            com.innolux.iws.wms.space.change.service.WmsSpaceChangeService.BUSINESS_TYPE))
            .isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*)
              from iws_process_definition
             where plan_code = ?
               and plan_role = 'BUSINESS'
               and business_type = ?
               and status = 'ACTIVE'
            """, Integer.class,
            WmsLogicalMergePlanContract.BATCH_PLAN_CODE,
            WmsLogicalMergePlanContract.BUSINESS_TYPE)).isOne();
    }

    private ObjectProvider<PlanDefinitionService> provider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PlanDefinitionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(definitions);
        return provider;
    }

    private int activeCount(List<String> codes) {
        String placeholders = String.join(",", codes.stream().map(code -> "?").toList());
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from iws_process_definition "
                + "where status = 'ACTIVE' and plan_code in (" + placeholders + ")",
            Integer.class,
            codes.toArray()
        );
        return count == null ? 0 : count;
    }

    private List<String> duplicateActiveDefinitions() {
        return jdbcTemplate.query("""
            select plan_code
              from iws_process_definition
             where status = 'ACTIVE'
             group by plan_code, plan_role
            having count(*) > 1
            """, (rs, rowNum) -> rs.getString("plan_code"));
    }
}
