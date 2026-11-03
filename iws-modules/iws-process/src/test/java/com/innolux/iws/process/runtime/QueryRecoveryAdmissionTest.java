/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : QUERY 恢复只读准入测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 命令和读模型共用的 QUERY 恢复准入必须保持只读且覆盖全部恢复前置条件。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryRecoveryAdmissionTest {

    @Mock
    private RuntimeDao runtimeDao;

    @Mock
    private ProcessAttemptMapper attemptMapper;

    @Mock
    private ProcessScheduleControlMapper controlMapper;

    private QueryRecoveryAdmission service;

    private ProcessEvent event;

    private ProcessAttempt attempt;

    private ProcessEvent query;

    private ProcessScheduleControl originalCall;

    private PlanDefinition plan;

    @BeforeEach
    void setUp() {
        service = new QueryRecoveryAdmission(runtimeDao, attemptMapper, controlMapper);
        event = new ProcessEvent();
        event.setId(7L);
        event.setInstanceId(1L);
        event.setEventCode("T5");
        event.setDefinitionVersionId(3L);
        event.setEventStatus(EventStatus.RETRY_WAIT);
        event.setEventVersion(5L);
        event.setSent(Boolean.TRUE);
        event.setRoundNo(0L);
        event.setLastAttemptId(9L);
        attempt = new ProcessAttempt();
        attempt.setId(9L);
        attempt.setEventId(7L);
        attempt.setCheckpointStatus(CheckpointStatus.UNKNOWN);
        attempt.setEffectResult(EffectResult.UNKNOWN);
        query = new ProcessEvent();
        query.setId(8L);
        query.setInstanceId(1L);
        query.setEventCode("Q5");
        query.setEventVersion(2L);
        query.setEventStatus(EventStatus.WAIT_PREDECESSOR);
        query.setTokenId(12L);
        originalCall = new ProcessScheduleControl();
        originalCall.setPermitState(PermitState.INTENT);
        plan = new PlanDefinition();
        plan.setMaxExecutionMs(60000L);
        givenReadyDefinition();
    }

    @Test
    void allowsUniqueQueryWithHeldPlanSlotAndActiveCall() {
        QueryRecoveryAdmission.Admission admission = service.evaluate(event, attempt);

        assertThat(admission.allowed()).isTrue();
        assertThat(admission.queryEvent()).isSameAs(query);
        assertThat(admission.budgetMs()).isEqualTo(60000L);
        assertThat(admission.reason()).isNull();
    }

    @Test
    void canRecoverByEventIdLoadsLastAttempt() {
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(attempt);

        assertThat(service.canRecover(7L)).isTrue();
    }

    @Test
    void rejectsEventWhoseResultIsNoLongerUnknown() {
        attempt.setCheckpointStatus(CheckpointStatus.FAILED);
        attempt.setEffectResult(EffectResult.FAILED);
        event.setSent(Boolean.FALSE);

        assertDenied("不是 UNKNOWN");
    }

    @Test
    void rejectsActiveLease() {
        event.setLeaseUntil(LocalDateTime.now().plusMinutes(1));

        assertDenied("活动租约");
    }

    @Test
    void rejectsMissingOrNonUniqueRecoveryQuery() {
        when(runtimeDao.graph(3L)).thenReturn(new DefinitionGraph(List.of(), List.of(), List.of()));

        assertDenied("唯一已发布对账 QUERY");
    }

    @Test
    void rejectsActivatedQueryPlaceholder() {
        query.setEventStatus(EventStatus.PENDING);

        assertDenied("不是未激活状态");
    }

    @Test
    void rejectsMissingPlanSlot() {
        when(controlMapper.selectCount(any())).thenReturn(0L);

        assertDenied("PLAN_SLOT");
    }

    @Test
    void rejectsReleasedOriginalCall() {
        originalCall.setPermitState(PermitState.RELEASED);

        assertDenied("原 CALL");
    }

    @Test
    void rejectsMissingExecutionBudget() {
        plan.setMaxExecutionMs(null);

        assertDenied("maxExecutionMs");
    }

    private void assertDenied(String reason) {
        QueryRecoveryAdmission.Admission admission = service.evaluate(event, attempt);

        assertThat(admission.allowed()).isFalse();
        assertThat(admission.queryEvent()).isNull();
        assertThat(admission.budgetMs()).isNull();
        assertThat(admission.reason()).contains(reason);
    }

    private void givenReadyDefinition() {
        ProcessEventDefinition queryDefinition = new ProcessEventDefinition();
        queryDefinition.setEventCode("Q5");
        queryDefinition.setRecoveryForEventCode("T5");
        queryDefinition.setEventDirection(EventDirection.OUTBOUND);
        queryDefinition.setPurpose(CallPurpose.QUERY);
        when(runtimeDao.graph(3L)).thenReturn(new DefinitionGraph(List.of(queryDefinition), List.of(), List.of()));
        when(runtimeDao.findEvent(1L, "Q5", 0L)).thenReturn(query);
        when(controlMapper.selectCount(any())).thenReturn(1L);
        when(controlMapper.selectOne(any())).thenReturn(originalCall);
        when(runtimeDao.definition(3L)).thenReturn(plan);
    }
}
