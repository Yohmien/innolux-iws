/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.1
 * @Description  : 计划事件命令与尝试分页测试；
 * @ModifyRecord : V1.0 新建；V1.1 增加 QUERY 恢复准入复用测试；
 */

package com.innolux.iws.process.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.AttemptVO;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.EventCommand;
import com.innolux.iws.process.api.RetryEventCommand;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.QueryRecoveryAdmission;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.service.impl.PlanEventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 尝试查询必须沿用对象授权并返回受控分页。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PlanEventServiceImplTest {

    @Mock
    private RuntimeDao runtimeDao;

    @Mock
    private ProcessEventMapper eventMapper;

    @Mock
    private ProcessAttemptMapper attemptMapper;

    @Mock
    private ProcessTokenMapper tokenMapper;

    @Mock
    private EventEligibilityService eligibilityService;

    @Mock
    private PlanActivationService activationService;

    @Mock
    private ProcessAuditService auditService;

    @Mock
    private CommandReceiptService commandReceiptService;

    @Mock
    private ProcessObjectAuthorizer objectAuthorizer;

    @Mock
    private QueryRecoveryAdmission queryRecoveryAdmission;

    private PlanEventServiceImpl service;

    @BeforeEach
    void setUp() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "test"), ProcessEvent.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "test"), ProcessToken.class);
        service = new PlanEventServiceImpl(runtimeDao, eventMapper, attemptMapper, tokenMapper,
            eligibilityService, activationService, auditService, commandReceiptService, objectAuthorizer,
            queryRecoveryAdmission);
        ProcessAttempt attempt = successAttempt();
        lenient().when(attemptMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<ProcessAttempt> page = invocation.getArgument(0);
            page.setRecords(List.of(attempt));
            page.setTotal(41L);
            return page;
        });
        lenient().when(auditService.newAudit(any(), any(), any())).thenReturn(new ProcessActionAudit());
    }

    @Test
    void attemptsKeepRowsAndTotalAndBoundPageSize() {
        PageResult<AttemptVO> result = service.attempts(7L, 3, 999);

        assertThat(result.getTotal()).isEqualTo(41L);
        assertThat(result.getRows()).hasSize(1);
        AttemptVO row = result.getRows().iterator().next();
        assertThat(row.attemptNo()).isEqualTo(2);
        assertThat(row.checkpointStatus()).isEqualTo(CheckpointStatus.SUCCEEDED.name());
        assertThat(row.effectResult()).isEqualTo(EffectResult.SUCCEEDED.name());
        assertThat(row.evidenceRef()).isEqualTo("receipt:9");
        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(attemptMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(3L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(200L);
        verify(objectAuthorizer).authorizeEvent(7L, "QUERY");
        verify(runtimeDao).event(7L);
    }

    @Test
    void attemptsUseDocumentedDefaultsWhenPageIsMissing() {
        service.attempts(7L, null, null);

        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(attemptMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20L);
    }

    @Test
    void retryAcceptsReusableSuccessCheckpoint() {
        ProcessEvent event = event(EventStatus.MANUAL_PENDING, EffectResult.SUCCEEDED, 9L);
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(successAttempt());
        when(runtimeDao.token(11L)).thenReturn(token());
        when(eventMapper.update(isNull(), any())).thenReturn(1);
        var definition = new com.innolux.iws.process.domain.PlanDefinition();
        definition.setMaxExecutionMs(60000L);
        when(runtimeDao.definition(event.getDefinitionVersionId())).thenReturn(definition);

        CommandResult result = service.retry(new RetryEventCommand(7L, 5L, "retry-key", "复用检查点", false));

        assertThat(result.code()).isEqualTo("SUCCEEDED");
        verify(eventMapper).update(isNull(), any());
        verify(activationService).updatePlanStatus(1L);
    }

    @Test
    void retryRejectsSuccessWithoutReusableEvidence() {
        ProcessEvent event = event(EventStatus.MANUAL_PENDING, EffectResult.SUCCEEDED, 9L);
        ProcessAttempt attempt = successAttempt();
        attempt.setEvidenceRef(null);
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(attempt);

        assertThatThrownBy(() -> service.retry(
            new RetryEventCommand(7L, 5L, "retry-key", "缺少证据", false)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("成功效果缺少可复用检查点");
        verify(eventMapper, never()).update(isNull(), any());
    }

    @Test
    void finalizeRejectsSuccessfulCheckpoint() {
        ProcessEvent event = event(EventStatus.MANUAL_PENDING, EffectResult.SUCCEEDED, 9L);
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(successAttempt());

        assertThatThrownBy(() -> service.finalizeEvent(
            new EventCommand(7L, 5L, "finalize-key", "错误终止", null)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("已存在成功检查点");
        verify(eventMapper, never()).update(isNull(), any());
    }

    @Test
    void finalizeStillAllowsKnownFailure() {
        ProcessEvent event = event(EventStatus.MANUAL_PENDING, EffectResult.FAILED, 9L);
        ProcessAttempt attempt = successAttempt();
        attempt.setCheckpointStatus(CheckpointStatus.FAILED);
        attempt.setEffectResult(EffectResult.FAILED);
        attempt.setEvidenceRef(null);
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(attempt);
        when(eventMapper.update(isNull(), any())).thenReturn(1);

        CommandResult result = service.finalizeEvent(
            new EventCommand(7L, 5L, "finalize-key", "确认失败", null));

        assertThat(result.code()).isEqualTo("SUCCEEDED");
        verify(activationService).updatePlanStatus(1L);
    }

    @Test
    void expiredPendingCheckpointCanReceiveNewRecoveryBudget() {
        ProcessEvent event = event(EventStatus.PENDING, EffectResult.SUCCEEDED, 9L);
        event.setExecutionPurpose(com.innolux.iws.process.enums.ExecutionPurpose.RECOVERY_ONLY);
        event.setRecoveryDeadline(java.time.LocalDateTime.now().minusSeconds(1));
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(successAttempt());
        when(runtimeDao.token(11L)).thenReturn(token());
        when(eventMapper.update(isNull(), any())).thenReturn(1);
        var definition = new com.innolux.iws.process.domain.PlanDefinition();
        definition.setMaxExecutionMs(60000L);
        when(runtimeDao.definition(3L)).thenReturn(definition);

        assertThat(service.retry(new RetryEventCommand(7L, 5L, "renew-budget", "重新授权只读收口", true)).code())
            .isEqualTo("SUCCEEDED");
        verify(eventMapper).update(isNull(), org.mockito.ArgumentMatchers.argThat(wrapper ->
            wrapper.getSqlSegment().contains("event_status IN")
                && wrapper.getSqlSet().contains("recovery_deadline")));
    }

    @Test
    void retryRecoveryOnlyUsesSharedAdmissionAndActivatesQuery() {
        ProcessEvent event = event(EventStatus.RETRY_WAIT, EffectResult.UNKNOWN, 9L);
        ProcessAttempt attempt = unknownAttempt();
        ProcessEvent query = queryEvent();
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(attempt);
        when(queryRecoveryAdmission.evaluate(event, attempt))
            .thenReturn(new QueryRecoveryAdmission.Admission(true, query, 60000L, null));
        when(runtimeDao.token(12L)).thenReturn(queryToken());
        when(eventMapper.update(isNull(), any())).thenReturn(1);

        CommandResult result = service.retry(
            new RetryEventCommand(7L, 5L, "recover-key", "显式恢复", true));

        assertThat(result.code()).isEqualTo("SUCCEEDED");
        assertThat(result.instanceId()).isEqualTo("8");
        verify(queryRecoveryAdmission).evaluate(event, attempt);
        verify(runtimeDao).token(12L);
        verify(activationService, never()).updatePlanStatus(1L);
    }

    @Test
    void retryRecoveryOnlyRejectsDeniedAdmission() {
        ProcessEvent event = event(EventStatus.RETRY_WAIT, EffectResult.UNKNOWN, 9L);
        ProcessAttempt attempt = unknownAttempt();
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(attempt);
        when(queryRecoveryAdmission.evaluate(event, attempt))
            .thenReturn(new QueryRecoveryAdmission.Admission(false, null, null,
                "原 CALL 不存在或已释放，拒绝 QUERY 恢复"));

        assertThatThrownBy(() -> service.retry(
            new RetryEventCommand(7L, 5L, "recover-key", "显式恢复", true)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("原 CALL");
        verify(eventMapper, never()).update(isNull(), any());
    }

    @Test
    void retryStillRejectsUnknownWithoutRecoveryOnly() {
        ProcessEvent event = event(EventStatus.RETRY_WAIT, EffectResult.UNKNOWN, 9L);
        ProcessAttempt attempt = unknownAttempt();
        when(runtimeDao.event(7L)).thenReturn(event);
        when(attemptMapper.selectById(9L)).thenReturn(attempt);

        assertThatThrownBy(() -> service.retry(
            new RetryEventCommand(7L, 5L, "retry-key", "盲目重试", false)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("禁止盲目重试");
        verify(queryRecoveryAdmission, never()).evaluate(any(), any());
        verify(eventMapper, never()).update(isNull(), any());
    }

    private static ProcessEvent event(EventStatus status, EffectResult effect, Long lastAttemptId) {
        ProcessEvent event = new ProcessEvent();
        event.setId(7L);
        event.setInstanceId(1L);
        event.setEventVersion(5L);
        event.setEventCode("T5");
        event.setDefinitionVersionId(3L);
        event.setEventStatus(status);
        event.setEffectResult(effect);
        event.setSent(Boolean.TRUE);
        event.setLastAttemptId(lastAttemptId);
        event.setTokenId(11L);
        return event;
    }

    private static ProcessAttempt successAttempt() {
        ProcessAttempt attempt = new ProcessAttempt();
        attempt.setId(9L);
        attempt.setAttemptNo(2);
        attempt.setCheckpointStatus(CheckpointStatus.SUCCEEDED);
        attempt.setEffectResult(EffectResult.SUCCEEDED);
        attempt.setEvidenceRef("receipt:9");
        return attempt;
    }

    private static ProcessAttempt unknownAttempt() {
        ProcessAttempt attempt = new ProcessAttempt();
        attempt.setId(9L);
        attempt.setAttemptNo(2);
        attempt.setCheckpointStatus(CheckpointStatus.UNKNOWN);
        attempt.setEffectResult(EffectResult.UNKNOWN);
        return attempt;
    }

    private static ProcessEvent queryEvent() {
        ProcessEvent query = new ProcessEvent();
        query.setId(8L);
        query.setInstanceId(1L);
        query.setEventVersion(2L);
        query.setEventCode("Q5");
        query.setEventStatus(EventStatus.WAIT_PREDECESSOR);
        query.setTokenId(12L);
        return query;
    }

    private static ProcessToken token() {
        ProcessToken token = new ProcessToken();
        token.setId(11L);
        token.setTokenVersion(1L);
        return token;
    }

    private static ProcessToken queryToken() {
        ProcessToken token = new ProcessToken();
        token.setId(12L);
        token.setTokenVersion(1L);
        return token;
    }
}
