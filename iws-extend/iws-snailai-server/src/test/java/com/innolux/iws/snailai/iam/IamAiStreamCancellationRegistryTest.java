/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 验证 AI 流取消作用域的迟到注册、正常完成和并发释放边界；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import io.grpc.ClientCall;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AI 流取消作用域单元测试。
 */
@Tag("dev")
class IamAiStreamCancellationRegistryTest {

    private final IamAiStreamCancellationRegistry registry =
        new IamAiStreamCancellationRegistry();

    @Test
    void lateRegisterAfterCancelIsImmediatelyCancelled() {
        IamAiStreamCancellationRegistry.Scope scope = registry.open();
        registry.cancel(scope);

        ClientCall<Object, Object> lateCall = call();
        scope.register(lateCall);

        verify(lateCall).cancel(anyString(), isNull());
        assertEquals(0, scope.activeCallCount());
    }

    @Test
    void normalRegistrationIsNotCancelledAndCompletionLeavesNoResidue() {
        IamAiStreamCancellationRegistry.Scope scope = registry.open();
        ClientCall<Object, Object> call = call();
        scope.register(call);
        verify(call, never()).cancel(anyString(), any());

        scope.release(call);
        registry.close(scope);

        verify(call, never()).cancel(anyString(), any());
        assertEquals(0, scope.activeCallCount());

        ClientCall<Object, Object> lateCall = call();
        scope.register(lateCall);
        verify(lateCall).cancel(anyString(), isNull());
    }

    @Test
    void concurrentReleaseAndCancelNeverLeaveActiveCalls() throws Exception {
        IamAiStreamCancellationRegistry.Scope scope = registry.open();
        List<ClientCall<Object, Object>> calls = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            calls.add(call());
        }

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < calls.size(); index++) {
                int current = index;
                futures.add(executor.submit(() -> scope.register(calls.get(current))));
                if (index % 2 == 0) {
                    futures.add(executor.submit(() -> scope.release(calls.get(current))));
                }
            }
            futures.add(executor.submit(() -> registry.cancel(scope)));
            for (Future<?> future : futures) {
                future.get();
            }
        }
        finally {
            executor.shutdownNow();
        }

        assertEquals(0, scope.activeCallCount());
        for (ClientCall<Object, Object> call : calls) {
            verify(call, atMost(1)).cancel(anyString(), any());
        }
    }

    @SuppressWarnings("unchecked")
    private ClientCall<Object, Object> call() {
        return mock(ClientCall.class);
    }
}
