/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 发布 JobSpec 到 SnailJob 字段映射测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.platform;

import com.aizuda.snailjob.client.job.core.enums.AllocationAlgorithmEnum;
import com.aizuda.snailjob.client.job.core.enums.TriggerTypeEnum;
import com.innolux.iws.process.api.JobSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SnailJob 字段只来自冻结 JobSpec。
 */
@Tag("dev")
class SnailJobJobDefinitionTest {

    @Test
    void mapsCronAndConvertsBudgetSecondsByCeiling() {
        SnailJobJobDefinition definition = SnailJobJobDefinition.from(spec(
            "businessScheduleExecutor", "计划监督－入库－队列－越南", "CRON", "0 */1 * * * ?",
            null, 2501L, "{\"supervisorInstanceId\":42,\"batchLimit\":20,\"maxDurationMs\":2000}",
            "ROUND"));

        assertThat(definition.triggerType()).isEqualTo(TriggerTypeEnum.CRON);
        assertThat(definition.triggerInterval()).isEqualTo("0 */1 * * * ?");
        assertThat(definition.executorTimeoutSeconds()).isEqualTo(3);
        assertThat(definition.maxRetryTimes()).isZero();
        assertThat(definition.routeKey()).isEqualTo(AllocationAlgorithmEnum.ROUND);
        assertThat(definition.paramsJson()).contains("batchLimit");
    }

    @Test
    void mapsFixedRateMillisecondsToPlatformSeconds() {
        SnailJobJobDefinition definition = SnailJobJobDefinition.from(spec(
            "planEventWorkerExecutor", "计划事件－出库－事件执行－队列", "FIXED_RATE", null,
            15001L, 60000L, "{\"scheduleId\":9,\"batchLimit\":5,\"maxDurationMs\":5000}", null));

        assertThat(definition.triggerType()).isEqualTo(TriggerTypeEnum.SCHEDULED_TIME);
        assertThat(definition.triggerInterval()).isEqualTo("16");
        assertThat(definition.executorTimeoutSeconds()).isEqualTo(60);
        assertThat(definition.routeKey()).isNull();
    }

    @Test
    void rejectsMissingScopeOrBudgetKeys() {
        assertThatThrownBy(() -> SnailJobJobDefinition.from(spec(
            "planEventWorkerExecutor", "计划事件－出库－事件执行－队列", "CRON", "0 */1 * * * ?",
            null, 60000L, "{\"supervisorInstanceId\":9,\"batchLimit\":5,\"maxDurationMs\":5000}", null)))
            .hasMessageContaining("缺少任务参数 scheduleId");
        assertThatThrownBy(() -> SnailJobJobDefinition.from(spec(
            "planEventWorkerExecutor", "计划事件－出库－事件执行－队列", "CRON", "0 */1 * * * ?",
            null, 60000L, "{\"scheduleId\":9,\"maxDurationMs\":5000}", null)))
            .hasMessageContaining("缺少任务参数 batchLimit");
        assertThatThrownBy(() -> SnailJobJobDefinition.from(spec(
            "planEventWorkerExecutor", "计划事件－出库－事件执行－队列", "CRON", "0 */1 * * * ?",
            null, 60000L, "{\"scheduleId\":9,\"batchLimit\":0,\"maxDurationMs\":5000}", null)))
            .hasMessageContaining("batchLimit 必须为正整数");
    }

    @Test
    void rejectsTechnicalOrNonChineseJobName() {
        assertThatThrownBy(() -> SnailJobJobDefinition.from(spec(
            "planEventWorkerExecutor", "worker-biz-1", "CRON", "0 */1 * * * ?",
            null, 60000L, "{\"scheduleId\":9,\"batchLimit\":5,\"maxDurationMs\":5000}", null)))
            .hasMessageContaining("独立中文运维名称");
    }

    @Test
    void rejectsUnsupportedOrIncompletePublishedConfiguration() {
        assertThatThrownBy(() -> SnailJobJobDefinition.from(spec(
            "otherExecutor", "计划事件－出库－事件执行－队列", "CRON", "0 */1 * * * ?",
            null, 60000L, "{\"scheduleId\":9,\"batchLimit\":5,\"maxDurationMs\":5000}", null)))
            .hasMessageContaining("不支持的执行器");
        assertThatThrownBy(() -> SnailJobJobDefinition.from(spec(
            "planEventWorkerExecutor", "计划事件－出库－事件执行－队列", "CRON", null,
            null, 60000L, "{\"scheduleId\":9,\"batchLimit\":5,\"maxDurationMs\":5000}", null)))
            .hasMessageContaining("cronExpression 不能为空");
        assertThatThrownBy(() -> SnailJobJobDefinition.from(spec(
            "planEventWorkerExecutor", "计划事件－出库－事件执行－队列", "ONE_SHOT", null,
            null, 60000L, "{\"scheduleId\":9,\"batchLimit\":5,\"maxDurationMs\":5000}", null)))
            .hasMessageContaining("ONE_SHOT 缺少平台触发时间");
    }

    private JobSpec spec(String executor, String jobName, String scheduleType, String cron,
                         Long intervalMs, Long timeoutMs, String params, String routeKey) {
        return new JobSpec(executor, jobName, "iws:process:" + executor + ":42", scheduleType,
            cron, intervalMs, timeoutMs, null, params, routeKey);
    }
}
