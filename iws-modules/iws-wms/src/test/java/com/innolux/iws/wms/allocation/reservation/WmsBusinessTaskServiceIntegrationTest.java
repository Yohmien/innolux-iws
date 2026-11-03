/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务持久化
 * @Version      : V1.0
 * @Description  : 验证业务任务创建回放、唯一键、修订迁移与并发；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskTransitionCommand;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 业务任务真实 MySQL 集成测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationReservationTestContext.class)
class WmsBusinessTaskServiceIntegrationTest {

    @Autowired
    private WmsBusinessTaskService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTasks() {
        jdbcTemplate.update("delete from wms_business_task");
    }

    @Test
    void createStoresFrozenJsonAndReplaysTheSameTask() {
        WmsBusinessTaskCreateCommand command = command(
            91_001L, "TASK-REPLAY", "{\"value\":10}");

        WmsBusinessTaskSnapshot first = service.createOrReplay(command);
        WmsBusinessTaskSnapshot replay = service.createOrReplay(command);

        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.revision()).isOne();
        assertThat(first.inputJson()).isEqualTo(command.inputJson());
        assertThat(replay.taskId()).isEqualTo(first.taskId());
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_business_task"
        )).isOne();
    }

    @Test
    void sameBusinessTaskKeyWithDifferentInputIsRejected() {
        service.createOrReplay(command(91_002L, "TASK-CONFLICT", "{\"value\":10}"));
        WmsBusinessTaskCreateCommand conflict = new WmsBusinessTaskCreateCommand(
            "INBOUND_FIXTURE",
            9_900L,
            101L,
            9_901L,
            0,
            "EXECUTE",
            "TASK-CONFLICT",
            "ALLOCATION_LOCK",
            91_099L,
            sha256("DIFFERENT"),
            "{\"value\":11}",
            9_001L,
            9_101L
        );

        assertThatThrownBy(() -> service.createOrReplay(conflict))
            .isInstanceOf(com.innolux.iws.common.core.exception.ServiceException.class)
            .hasMessageContaining("已被不同身份或冻结输入占用");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_business_task"
        )).isOne();
    }

    @Test
    void onePlanInstanceCannotBindTwoBusinessTasks() {
        service.createOrReplay(command(91_003L, "TASK-INSTANCE-A", "{\"value\":1}"));
        WmsBusinessTaskCreateCommand conflict = new WmsBusinessTaskCreateCommand(
            "INBOUND_FIXTURE",
            9_900L,
            101L,
            9_901L,
            0,
            "EXECUTE",
            "TASK-INSTANCE-B",
            "ALLOCATION_LOCK",
            91_003L,
            sha256("INSTANCE-B"),
            "{\"value\":2}",
            9_001L,
            9_101L
        );

        assertThatThrownBy(() -> service.createOrReplay(conflict))
            .isInstanceOf(com.innolux.iws.common.core.exception.ServiceException.class);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_business_task where plan_instance_id = 91003"
        )).isOne();
    }

    @Test
    void transitionUsesRevisionAndRejectsLeavingTerminalState() {
        WmsBusinessTaskSnapshot created = service.createOrReplay(
            command(91_004L, "TASK-STATE", "{\"value\":3}"));
        WmsBusinessTaskSnapshot running = service.transition(
            new WmsBusinessTaskTransitionCommand(
                created.taskId(), created.revision(), "RUNNING", 9_001L, 9_101L));

        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.revision()).isEqualTo(2);
        assertThatThrownBy(() -> service.transition(
            new WmsBusinessTaskTransitionCommand(
                created.taskId(), created.revision(), "COMPLETED", 9_001L, 9_101L)))
            .hasMessageContaining("修订号冲突");

        WmsBusinessTaskSnapshot completed = service.transition(
            new WmsBusinessTaskTransitionCommand(
                running.taskId(), running.revision(), "COMPLETED", 9_001L, 9_101L));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.revision()).isEqualTo(3);
        assertThatThrownBy(() -> service.transition(
            new WmsBusinessTaskTransitionCommand(
                completed.taskId(), completed.revision(), "RUNNING", 9_001L, 9_101L)))
            .hasMessageContaining("终态");
    }

    @Test
    void concurrentCreateConvergesToOneTaskRow() throws Exception {
        WmsBusinessTaskCreateCommand command = command(
            91_005L, "TASK-CONCURRENT", "{\"value\":5}");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<WmsBusinessTaskSnapshot>> futures = List.of(
                executor.submit(() -> createConcurrently(command, ready, start)),
                executor.submit(() -> createConcurrently(command, ready, start))
            );
            assertThat(ready.await(10L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            WmsBusinessTaskSnapshot left = futures.get(0).get(20L, TimeUnit.SECONDS);
            WmsBusinessTaskSnapshot right = futures.get(1).get(20L, TimeUnit.SECONDS);
            assertThat(left.taskId()).isEqualTo(right.taskId());
        } finally {
            executor.shutdownNow();
        }
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_business_task"
        )).isOne();
    }

    private WmsBusinessTaskSnapshot createConcurrently(
        WmsBusinessTaskCreateCommand command,
        CountDownLatch ready,
        CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return service.createOrReplay(command);
    }

    private WmsBusinessTaskCreateCommand command(
        long planInstanceId,
        String taskKey,
        String inputJson
    ) {
        return new WmsBusinessTaskCreateCommand(
            "INBOUND_FIXTURE",
            9_900L,
            101L,
            9_901L,
            0,
            "EXECUTE",
            taskKey,
            "ALLOCATION_LOCK",
            planInstanceId,
            sha256(taskKey + ":" + inputJson),
            inputJson,
            9_001L,
            9_101L
        );
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
