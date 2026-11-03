/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务完成屏障
 * @Version      : V1.0
 * @Description  : 验证预期任务集合、完成屏障、缺失/失败阻断和原键回放；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCompleteCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskTransitionCommand;
import com.innolux.iws.wms.task.service.WmsBusinessTaskBarrierService;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
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

/** 业务任务完成屏障真实 MySQL 集成测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationReservationTestContext.class)
class WmsBusinessTaskBarrierIntegrationTest {

    private static final String BUSINESS_TYPE = "BARRIER_FIXTURE";

    private static final long BUSINESS_ID = 7_701L;

    private static final String BARRIER_KEY = "BARRIER-001";

    private static final String INPUT_HASH = "a".repeat(64);

    @Autowired
    private WmsBusinessTaskBarrierService barrierService;

    @Autowired
    private WmsBusinessTaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearFixture() {
        jdbcTemplate.update(
            "delete from wms_business_task_barrier where business_type = ?",
            BUSINESS_TYPE
        );
        jdbcTemplate.update(
            "delete from wms_business_task where business_type = ?",
            BUSINESS_TYPE
        );
    }

    @Test
    void barrierCompletesOnlyAfterEveryExpectedTaskCompleted() {
        WmsBusinessTaskBarrierSnapshot barrier = barrierService.createOrReplay(
            new WmsBusinessTaskBarrierCreateCommand(
                BUSINESS_TYPE,
                BUSINESS_ID,
                BARRIER_KEY,
                List.of("TASK-002", "TASK-001"),
                1L,
                2L
            )
        );
        assertThat(barrier.expectedTaskKeys()).containsExactly("TASK-001", "TASK-002");
        assertThat(barrier.status()).isEqualTo("OPEN");

        WmsBusinessTaskSnapshot first = createTask("TASK-001", 7_701_001L);
        WmsBusinessTaskSnapshot second = createTask("TASK-002", 7_701_002L);
        first = taskService.transition(new WmsBusinessTaskTransitionCommand(
            first.taskId(), first.revision(), "RUNNING", 1L, null));
        second = taskService.transition(new WmsBusinessTaskTransitionCommand(
            second.taskId(), second.revision(), "RUNNING", 1L, null));
        first = taskService.transition(new WmsBusinessTaskTransitionCommand(
            first.taskId(), first.revision(), "COMPLETED", 1L, null));

        assertBarrierBlocked("TASK-002=RUNNING");
        second = taskService.transition(new WmsBusinessTaskTransitionCommand(
            second.taskId(), second.revision(), "COMPLETED", 1L, null));

        WmsBusinessTaskBarrierSnapshot completed = barrierService.complete(
            new WmsBusinessTaskBarrierCompleteCommand(
                BUSINESS_TYPE,
                BUSINESS_ID,
                BARRIER_KEY,
                barrier.revision(),
                1L
            )
        );
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.revision()).isEqualTo(barrier.revision() + 1);
        assertThat(barrierService.complete(new WmsBusinessTaskBarrierCompleteCommand(
            BUSINESS_TYPE,
            BUSINESS_ID,
            BARRIER_KEY,
            barrier.revision(),
            1L
        )).status()).isEqualTo("COMPLETED");
    }

    @Test
    void missingOrFailedTaskAndDifferentExpectedSetRemainBlocked() {
        WmsBusinessTaskBarrierSnapshot barrier = barrierService.createOrReplay(
            new WmsBusinessTaskBarrierCreateCommand(
                BUSINESS_TYPE,
                BUSINESS_ID,
                BARRIER_KEY,
                List.of("TASK-001", "TASK-002"),
                1L,
                2L
            )
        );
        WmsBusinessTaskSnapshot task = createTask("TASK-001", 7_702_001L);
        task = taskService.transition(new WmsBusinessTaskTransitionCommand(
            task.taskId(), task.revision(), "RUNNING", 1L, null));
        taskService.transition(new WmsBusinessTaskTransitionCommand(
            task.taskId(), task.revision(), "FAILED", 1L, null));

        assertBarrierBlocked("TASK-001=FAILED", "TASK-002=MISSING");
        assertThatThrownBy(() -> barrierService.createOrReplay(
            new WmsBusinessTaskBarrierCreateCommand(
                BUSINESS_TYPE,
                BUSINESS_ID,
                BARRIER_KEY,
                List.of("TASK-001", "TASK-003"),
                1L,
                2L
            )
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith(
                WmsBusinessTaskBarrierService.ERROR_BARRIER_CONFLICT);
            assertThat(error.getCode()).isEqualTo(409);
        });
        assertThat(barrierService.get(BUSINESS_TYPE, BUSINESS_ID, BARRIER_KEY).status())
            .isEqualTo("OPEN");
    }

    private WmsBusinessTaskSnapshot createTask(String taskKey, long instanceId) {
        return taskService.createOrReplay(new WmsBusinessTaskCreateCommand(
            BUSINESS_TYPE,
            BUSINESS_ID,
            null,
            BUSINESS_ID,
            1,
            "CHECK",
            taskKey,
            "MATERIAL_CHANGE",
            instanceId,
            INPUT_HASH,
            "{\"taskKey\":\"" + taskKey + "\"}",
            1L,
            2L
        ));
    }

    private void assertBarrierBlocked(String... details) {
        assertThatThrownBy(() -> barrierService.complete(
            new WmsBusinessTaskBarrierCompleteCommand(
                BUSINESS_TYPE,
                BUSINESS_ID,
                BARRIER_KEY,
                1,
                1L
            )
        )).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getMessage()).startsWith(
                WmsBusinessTaskBarrierService.ERROR_BARRIER_BLOCKED);
            assertThat(error.getMessage()).contains(details);
            assertThat(error.getCode()).isEqualTo(409);
        });
    }
}
