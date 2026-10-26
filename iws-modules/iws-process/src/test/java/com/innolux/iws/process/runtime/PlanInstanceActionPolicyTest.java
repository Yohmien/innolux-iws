/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 实例摘要动作元数据与实际命令准入一致性测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 实例动作描述不得与命令准入分叉。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PlanInstanceActionPolicyTest {

    @Mock
    private RuntimeDao runtimeDao;

    @Mock
    private ProcessSystemContextMapper contextMapper;

    @Mock
    private ProcessScheduleControlMapper controlMapper;

    private PlanInstanceActionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PlanInstanceActionPolicy(runtimeDao, contextMapper, controlMapper);
        lenient().when(controlMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void createdInstanceAdvertisesOnlyUnadmittedActions() {
        ProcessInstance instance = instance(PlanStatus.CREATED, Boolean.FALSE, null);
        ProcessSystemContext context = context(PostingMode.MANUAL_CONFIRM, Boolean.FALSE);

        PlanInstanceActionPolicy.Admission admission = policy.describe(instance, List.of(), List.of(context));

        assertThat(admission.allowed(PlanInstanceActionPolicy.CONFIRM_POSTING)).isTrue();
        assertThat(admission.allowed(PlanInstanceActionPolicy.PAUSE)).isTrue();
        assertThat(admission.allowed(PlanInstanceActionPolicy.CANCEL)).isTrue();
        assertThat(admission.allowed(PlanInstanceActionPolicy.HOLD_START)).isTrue();
        assertThat(admission.allowed(PlanInstanceActionPolicy.PRIORITY)).isTrue();
        assertThat(admission.allowed(PlanInstanceActionPolicy.RESUME)).isFalse();
        assertThat(admission.allowed(PlanInstanceActionPolicy.RELEASE_START)).isFalse();
        assertThat(admission.allowedActionCodes()).containsExactly(PlanInstanceActionPolicy.CONFIRM_POSTING,
            PlanInstanceActionPolicy.PAUSE, PlanInstanceActionPolicy.CANCEL, PlanInstanceActionPolicy.HOLD_START,
            PlanInstanceActionPolicy.PRIORITY);
        assertThat(admission.reasons()).containsEntry(PlanInstanceActionPolicy.RESUME,
            "只有 PAUSED 计划可 resume");
        assertThat(admission.reasons()).containsEntry(PlanInstanceActionPolicy.RELEASE_START,
            "只有未准入挂起计划可 release-start");
    }

    @Test
    void pausedInstanceCanResumeOnlyWithHeldSlot() {
        ProcessInstance instance = instance(PlanStatus.PAUSED, Boolean.FALSE, null);
        when(controlMapper.selectCount(any())).thenReturn(1L, 0L);

        PlanInstanceActionPolicy.Admission held = policy.describe(instance, List.of(), List.of());
        PlanInstanceActionPolicy.Admission waiting = policy.describe(instance, List.of(), List.of());

        assertThat(held.allowed(PlanInstanceActionPolicy.RESUME)).isTrue();
        assertThat(held.reasons()).containsEntry(PlanInstanceActionPolicy.CONFIRM_POSTING,
            "实例未冻结系统模式");
        assertThat(waiting.allowed(PlanInstanceActionPolicy.RESUME)).isFalse();
        assertThat(waiting.reasons()).containsEntry(PlanInstanceActionPolicy.RESUME,
            "计划未取得 PLAN_SLOT，必须由监督准入后再恢复");
    }

    @Test
    void pausedSupervisorBlocksChildResume() {
        ProcessInstance instance = instance(PlanStatus.PAUSED, Boolean.FALSE, 99L);
        when(controlMapper.selectCount(any())).thenReturn(1L);
        ProcessScheduleControl control = new ProcessScheduleControl();
        control.setControlState(ControlState.MANUAL_PAUSED);
        when(controlMapper.selectOne(any())).thenReturn(control);

        PlanInstanceActionPolicy.Admission admission = policy.describe(instance, List.of(), List.of());

        assertThat(admission.allowed(PlanInstanceActionPolicy.RESUME)).isFalse();
        assertThat(admission.reasons()).containsEntry(PlanInstanceActionPolicy.RESUME,
            "监督已暂停，不能恢复子计划");
    }

    @Test
    void processingEventBlocksPriorityChange() {
        ProcessEvent processing = new ProcessEvent();
        processing.setEventStatus(EventStatus.PROCESSING);

        PlanInstanceActionPolicy.Admission admission = policy.describe(
            instance(PlanStatus.RUNNING, Boolean.FALSE, null), List.of(processing), List.of());

        assertThat(admission.allowed(PlanInstanceActionPolicy.PRIORITY)).isFalse();
        assertThat(admission.reasons()).containsEntry(PlanInstanceActionPolicy.PRIORITY,
            "正在执行的实例不能调整优先级");
    }

    @Test
    void terminalInstanceKeepsCancelAdmissionButRejectsPause() {
        PlanInstanceActionPolicy.Admission admission = policy.describe(
            instance(PlanStatus.COMPLETED, Boolean.FALSE, null), List.of(), List.of());

        assertThat(admission.allowed(PlanInstanceActionPolicy.PAUSE)).isFalse();
        assertThat(admission.allowed(PlanInstanceActionPolicy.CANCEL)).isTrue();
        assertThat(admission.reasons()).containsEntry(PlanInstanceActionPolicy.PAUSE, "终态计划不能暂停");
    }

    @Test
    void confirmedManualSystemsAreNotConfirmableAgain() {
        ProcessSystemContext context = context(PostingMode.MANUAL_CONFIRM, Boolean.TRUE);

        PlanInstanceActionPolicy.Admission admission = policy.describe(
            instance(PlanStatus.RUNNING, Boolean.FALSE, null), List.of(), List.of(context));

        assertThat(admission.allowed(PlanInstanceActionPolicy.CONFIRM_POSTING)).isFalse();
        assertThat(admission.reasons()).containsEntry(PlanInstanceActionPolicy.CONFIRM_POSTING,
            "人工确认系统均已确认");
    }

    @Test
    void confirmRejectsUnknownOrNonManualSystemWithCommandMessage() {
        assertThat(policy.confirmSystemRejection(null, "MID_WMS"))
            .isEqualTo("实例未冻结系统模式: MID_WMS");
        assertThat(policy.confirmSystemRejection(context(PostingMode.AUTOMATIC, Boolean.FALSE), "MID_WMS"))
            .isEqualTo("系统不是人工确认模式，整批拒绝: MID_WMS");
    }

    private static ProcessInstance instance(PlanStatus status, Boolean holdStart, Long supervisorInstanceId) {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(1L);
        instance.setPlanStatus(status);
        instance.setHoldStart(holdStart);
        instance.setSupervisorInstanceId(supervisorInstanceId);
        return instance;
    }

    private static ProcessSystemContext context(PostingMode mode, Boolean confirmed) {
        ProcessSystemContext context = new ProcessSystemContext();
        context.setSystemCode("MID_WMS");
        context.setPostingMode(mode);
        context.setFirstConfirmed(confirmed);
        return context;
    }
}
