package com.innolux.iws.job.process.platform;

import com.aizuda.snailjob.client.job.core.enums.AllocationAlgorithmEnum;
import com.innolux.iws.process.api.JobSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("local")
class SnailJobJobDefinitionTest {

    @Test
    void defaultRouteKeyIsPresentForSnailJobValidation() {
        SnailJobJobDefinition definition = SnailJobJobDefinition.from(spec("process_worker_1"));

        assertThat(definition.routeKey()).isEqualTo(AllocationAlgorithmEnum.ROUND);
    }

    @Test
    void invalidBizIdFailsBeforeOpenApiCall() {
        assertThatThrownBy(() -> SnailJobJobDefinition.from(spec("process:worker:1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bizId");
    }

    private static JobSpec spec(String bizId) {
        String params = "{\"scheduleId\":1,\"batchLimit\":1,\"maxDurationMs\":1000}";
        return new JobSpec("planEventWorkerExecutor", "计划事件队列－空间变更", bizId,
            "FIXED_RATE", null, 1000L, 1000L, 0, params, "DEFAULT");
    }
}
