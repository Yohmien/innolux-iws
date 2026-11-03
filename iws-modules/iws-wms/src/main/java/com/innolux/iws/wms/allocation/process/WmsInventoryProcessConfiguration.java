/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理计划能力注册
 * @Version      : V1.0
 * @Description  : 向计划事件平台注册 WMS_INTERNAL@1 与 P5 分配动作；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.process;

import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.runtime.EventTypeDescriptor;
import com.innolux.iws.process.runtime.EventTypeProvider;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeAction;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeCheckAction;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeStateValidator;
import com.innolux.iws.wms.load.change.service.WmsLoadChangeService;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeApplyBatchAction;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeBatchStateValidator;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeCaptureBatchAction;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeCaptureParentAction;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeCheckBatchAction;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeCloseBatchAction;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeCloseParentAction;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeCloseStateValidator;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeOrchestrationService;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import com.innolux.iws.wms.rule.process.WmsRuleActivateAction;
import com.innolux.iws.wms.rule.process.WmsRulePublishStateValidator;
import com.innolux.iws.wms.rule.process.WmsRuleValidateAction;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import com.innolux.iws.wms.space.change.process.WmsSpaceApplyAction;
import com.innolux.iws.wms.space.change.process.WmsSpaceChangeStateValidator;
import com.innolux.iws.wms.space.change.process.WmsSpaceRetireAction;
import com.innolux.iws.wms.space.change.process.WmsSpaceRetireCheckAction;
import com.innolux.iws.wms.space.change.process.WmsSpaceValidateAction;
import com.innolux.iws.wms.space.change.process.WmsSpaceVerifyAction;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

/** WMS 在库管理计划事件生产能力。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class WmsInventoryProcessConfiguration {

    public static final String EVENT_TYPE_KEY = "WMS_INTERNAL";

    public static final String EVENT_TYPE_VERSION = "1";

    @Bean
    EventTypeProvider wmsInventoryEventTypeProvider() {
        return () -> List.of(new EventTypeDescriptor(
            EVENT_TYPE_KEY,
            EVENT_TYPE_VERSION,
            "WMS内部业务动作",
            Set.of(EventDirection.INTERNAL)
        ));
    }

    @Bean
    WmsAllocationEvaluateAction wmsAllocationEvaluateAction(
        WmsAllocationReservationService reservationService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsAllocationEvaluateAction(
            reservationService, instanceMapper, jsonMapper, inputReader);
    }

    @Bean
    WmsAllocationReserveAction wmsAllocationReserveAction(
        WmsAllocationReservationService reservationService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsAllocationReserveAction(
            reservationService, instanceMapper, jsonMapper, inputReader);
    }

    @Bean
    WmsAllocationVerifyAction wmsAllocationVerifyAction(
        WmsAllocationReservationService reservationService,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsAllocationVerifyAction(reservationService, jsonMapper, inputReader);
    }

    @Bean
    WmsAllocationCheckReleaseAction wmsAllocationCheckReleaseAction(
        WmsAllocationReleaseService releaseService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsAllocationCheckReleaseAction(
            releaseService, instanceMapper, jsonMapper, inputReader);
    }

    @Bean
    WmsAllocationReleaseAction wmsAllocationReleaseAction(
        WmsAllocationReleaseService releaseService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsAllocationReleaseAction(
            releaseService, instanceMapper, jsonMapper, inputReader);
    }

    @Bean
    WmsAllocationVerifyReleaseAction wmsAllocationVerifyReleaseAction(
        WmsAllocationReleaseService releaseService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsAllocationVerifyReleaseAction(
            releaseService, instanceMapper, jsonMapper, inputReader);
    }

    @Bean
    WmsAllocationLockStateValidator wmsAllocationLockStateValidator(JdbcTemplate jdbcTemplate) {
        return new WmsAllocationLockStateValidator(jdbcTemplate);
    }

    @Bean
    WmsAllocationReleaseStateValidator wmsAllocationReleaseStateValidator(JdbcTemplate jdbcTemplate) {
        return new WmsAllocationReleaseStateValidator(jdbcTemplate);
    }

    @Bean
    WmsAllocationSupervisorStateValidator wmsAllocationSupervisorStateValidator(
        JdbcTemplate jdbcTemplate
    ) {
        return new WmsAllocationSupervisorStateValidator(jdbcTemplate);
    }

    @Bean
    WmsLoadChangeCheckAction wmsLoadChangeCheckAction(
        WmsLoadChangeService loadChangeService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsLoadChangeCheckAction(
            loadChangeService, instanceMapper, jsonMapper, inputReader);
    }

    @Bean
    WmsLoadChangeAction wmsLoadChangeAction(
        WmsLoadChangeService loadChangeService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsLoadChangeAction(
            loadChangeService, instanceMapper, jsonMapper, inputReader);
    }

    @Bean
    WmsLoadChangeStateValidator wmsLoadChangeStateValidator(JdbcTemplate jdbcTemplate) {
        return new WmsLoadChangeStateValidator(jdbcTemplate);
    }

    @Bean
    WmsFrozenActionInputReader wmsFrozenActionInputReader(JdbcTemplate jdbcTemplate) {
        return new WmsFrozenActionInputReader(jdbcTemplate);
    }

    @Bean
    WmsRuleValidateAction wmsRuleValidateAction(
        WmsRulePublishService publishService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsRuleValidateAction(
            publishService,
            instanceMapper,
            jsonMapper,
            inputReader
        );
    }

    @Bean
    WmsRuleActivateAction wmsRuleActivateAction(
        WmsRulePublishService publishService,
        ProcessInstanceMapper instanceMapper,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsRuleActivateAction(
            publishService,
            instanceMapper,
            jsonMapper,
            inputReader
        );
    }

    @Bean
    WmsRulePublishStateValidator wmsRulePublishStateValidator(JdbcTemplate jdbcTemplate) {
        return new WmsRulePublishStateValidator(jdbcTemplate);
    }

    @Bean
    WmsSpaceValidateAction wmsSpaceValidateAction(
        WmsSpaceChangeService changeService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsSpaceValidateAction(
            changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsSpaceApplyAction wmsSpaceApplyAction(
        WmsSpaceChangeService changeService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsSpaceApplyAction(
            changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsSpaceVerifyAction wmsSpaceVerifyAction(
        WmsSpaceChangeService changeService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsSpaceVerifyAction(
            changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsSpaceRetireCheckAction wmsSpaceRetireCheckAction(
        WmsSpaceChangeService changeService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsSpaceRetireCheckAction(
            changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsSpaceRetireAction wmsSpaceRetireAction(
        WmsSpaceChangeService changeService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsSpaceRetireAction(
            changeService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsSpaceChangeStateValidator wmsSpaceChangeStateValidator(JdbcTemplate jdbcTemplate) {
        return new WmsSpaceChangeStateValidator(jdbcTemplate);
    }

    @Bean
    WmsLogicalMergeCaptureParentAction wmsLogicalMergeCaptureParentAction(
        WmsLogicalMergeOrchestrationService orchestrationService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsLogicalMergeCaptureParentAction(
            orchestrationService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsLogicalMergeCloseParentAction wmsLogicalMergeCloseParentAction(
        WmsLogicalMergeOrchestrationService orchestrationService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsLogicalMergeCloseParentAction(
            orchestrationService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsLogicalMergeCaptureBatchAction wmsLogicalMergeCaptureBatchAction(
        WmsLogicalMergeOrchestrationService orchestrationService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsLogicalMergeCaptureBatchAction(
            orchestrationService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsLogicalMergeCheckBatchAction wmsLogicalMergeCheckBatchAction(
        WmsLogicalMergeOrchestrationService orchestrationService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsLogicalMergeCheckBatchAction(
            orchestrationService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsLogicalMergeApplyBatchAction wmsLogicalMergeApplyBatchAction(
        WmsLogicalMergeOrchestrationService orchestrationService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsLogicalMergeApplyBatchAction(
            orchestrationService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsLogicalMergeCloseBatchAction wmsLogicalMergeCloseBatchAction(
        WmsLogicalMergeOrchestrationService orchestrationService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsFrozenActionInputReader inputReader
    ) {
        return new WmsLogicalMergeCloseBatchAction(
            orchestrationService, instanceMapper, jdbcTemplate, jsonMapper, inputReader);
    }

    @Bean
    WmsLogicalMergeBatchStateValidator wmsLogicalMergeBatchStateValidator(
        JdbcTemplate jdbcTemplate
    ) {
        return new WmsLogicalMergeBatchStateValidator(jdbcTemplate);
    }

    @Bean
    WmsLogicalMergeCloseStateValidator wmsLogicalMergeCloseStateValidator(
        JdbcTemplate jdbcTemplate
    ) {
        return new WmsLogicalMergeCloseStateValidator(jdbcTemplate);
    }
}
