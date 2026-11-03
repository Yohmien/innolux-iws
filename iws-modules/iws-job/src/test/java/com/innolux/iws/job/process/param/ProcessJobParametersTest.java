/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件任务参数校验测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.param;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务参数只接受发布配置中的正整数值。
 */
@Tag("dev")
class ProcessJobParametersTest {

    @Test
    void parsesNumericStringAndIntegerValuesWithoutDefaults() {
        Map<String, Object> params = Map.of(
            "supervisorInstanceId", "42",
            "batchLimit", 7,
            "maxDurationMs", 2500L);

        assertThat(ProcessJobParameters.requiredPositiveLong(params, "supervisorInstanceId")).isEqualTo(42L);
        assertThat(ProcessJobParameters.requiredPositiveInt(params, "batchLimit")).isEqualTo(7);
        assertThat(ProcessJobParameters.requiredPositiveLong(params, "maxDurationMs")).isEqualTo(2500L);
    }

    @Test
    void parsesJsonObject() {
        Map<String, Object> params = ProcessJobParameters.asMap(
            "{\"scheduleId\":\"9\",\"batchLimit\":3,\"maxDurationMs\":1000}");

        assertThat(params).containsEntry("scheduleId", "9");
        assertThat(ProcessJobParameters.requiredPositiveLong(params, "scheduleId")).isEqualTo(9L);
    }

    @Test
    void rejectsMissingZeroNegativeAndFractionalValues() {
        assertThatThrownBy(() -> ProcessJobParameters.requiredPositiveLong(Map.of(), "scheduleId"))
            .hasMessageContaining("缺少任务参数 scheduleId");
        assertThatThrownBy(() -> ProcessJobParameters.requiredPositiveLong(Map.of("scheduleId", 0), "scheduleId"))
            .hasMessageContaining("必须为正整数");
        assertThatThrownBy(() -> ProcessJobParameters.requiredPositiveLong(Map.of("scheduleId", -1), "scheduleId"))
            .hasMessageContaining("必须为正整数");
        assertThatThrownBy(() -> ProcessJobParameters.requiredPositiveLong(Map.of("scheduleId", 1.5), "scheduleId"))
            .hasMessageContaining("必须为正整数");
    }

    @Test
    void rejectsBatchLimitOutsideIntegerRange() {
        assertThatThrownBy(() -> ProcessJobParameters.requiredPositiveInt(
            Map.of("batchLimit", (long) Integer.MAX_VALUE + 1L), "batchLimit"))
            .hasMessageContaining("超出整型范围");
    }
}
