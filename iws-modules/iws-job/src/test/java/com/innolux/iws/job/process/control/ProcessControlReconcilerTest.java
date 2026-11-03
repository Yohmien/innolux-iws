package com.innolux.iws.job.process.control;

import com.innolux.iws.process.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

/** 持久意图可在物理任务尚不存在时被消费，单项失败不阻断其他范围。 */
@Tag("dev")
class ProcessControlReconcilerTest {
    @Test
    void discoversFirstSupervisorAndWorkerWithoutRunningJob() {
        ProcessControlIntentPort port = mock(ProcessControlIntentPort.class);
        SchedulerPlatformPort platform = mock(SchedulerPlatformPort.class);
        ControlIntent supervisor = intent("SUPERVISOR", "1", "DISPATCH", 3);
        ControlIntent worker = intent("BUSINESS", "2", "WORKER", 7);
        when(port.listPendingIntents(2)).thenReturn(List.of(supervisor, worker));
        when(platform.apply(any())).thenReturn(new PlatformApplyResult(true, 0, "RUNNING", "10", "已应用"));
        new ProcessControlReconciler(port, platform, 2).reconcile();
        verify(platform).apply(new PlatformControlIntent("SUPERVISOR", "1", "ENSURE_RUNNING", 3, null));
        verify(platform).apply(new PlatformControlIntent("BUSINESS", "2", "ENSURE_RUNNING", 7, null));
        verify(port).recordResult(new PlatformScopeResultCommand("BUSINESS", "2", "WORKER", "ENSURE_RUNNING", 7,
            true, "RUNNING", "10", "已应用"));
    }

    @Test
    void transportFailureLeavesIntentForNextPassAndContinuesOtherScopes() {
        ProcessControlIntentPort port = mock(ProcessControlIntentPort.class);
        SchedulerPlatformPort platform = mock(SchedulerPlatformPort.class);
        when(port.listPendingIntents(2)).thenReturn(List.of(intent("SUPERVISOR", "1", "DISPATCH", 3),
            intent("BUSINESS", "2", "WORKER", 7)));
        when(platform.apply(any())).thenThrow(new IllegalStateException("平台不可达"))
            .thenReturn(new PlatformApplyResult(false, 0, "UNKNOWN", null, "未确认"));
        new ProcessControlReconciler(port, platform, 2).reconcile();
        verify(port, times(1)).recordResult(any());
        verify(port).recordResult(new PlatformScopeResultCommand("BUSINESS", "2", "WORKER", "ENSURE_RUNNING", 7,
            false, "UNKNOWN", null, "未确认"));
    }

    @Test
    void resultUsesOriginalRevisionWhenNewWakeWinsRace() {
        ProcessControlIntentPort port = mock(ProcessControlIntentPort.class);
        SchedulerPlatformPort platform = mock(SchedulerPlatformPort.class);
        when(port.listPendingIntents(1)).thenReturn(List.of(intent("SUPERVISOR", "1", "DISPATCH", 3)));
        when(platform.apply(any())).thenReturn(new PlatformApplyResult(true, 0, "RUNNING", "10", "已应用"));
        when(port.recordResult(any())).thenReturn(new ControlIntentAck(false, true, 4, "ABSENT", true));
        new ProcessControlReconciler(port, platform, 1).reconcile();
        verify(port).recordResult(new PlatformScopeResultCommand("SUPERVISOR", "1", "DISPATCH", "ENSURE_RUNNING", 3,
            true, "RUNNING", "10", "已应用"));
        verify(port).listPendingIntents(1);
        verifyNoMoreInteractions(port);
    }

    private ControlIntent intent(String type, String key, String duty, long revision) {
        return new ControlIntent(type, key, duty, null, "ENSURE_RUNNING", "RUNNING", "ABSENT",
            revision, 1, true, null, null, null);
    }
}
