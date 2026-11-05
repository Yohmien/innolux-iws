/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 将 AI HTTP 流的撤销传播到本次请求发起的 gRPC ClientCall；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import io.grpc.ClientCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 流取消登记表。
 *
 * <p>每个 HTTP 流持有独立作用域，撤销和注册竞态由作用域自身状态裁决；
 * 不依赖可被删除后重新创建的全局键，避免迟到调用复活。</p>
 */
@Component
public class IamAiStreamCancellationRegistry {

    /**
     * 创建一条 AI 流的取消作用域。
     *
     * @return 独立流作用域
     */
    public Scope open() {
        return new Scope();
    }

    /**
     * 撤销作用域内全部 gRPC 调用；之后注册的调用会立即取消。
     *
     * @param scope 流作用域
     */
    public void cancel(Scope scope) {
        scope.cancel();
    }

    /**
     * 正常关闭作用域；若仍有迟到调用则失败关闭。
     *
     * @param scope 流作用域
     */
    public void close(Scope scope) {
        scope.close();
    }

    /**
     * 单条 AI 流作用域。
     */
    public static final class Scope {

        private final Set<ClientCall<?, ?>> calls = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 登记本作用域发起的 gRPC 调用。
         *
         * @param call gRPC 调用
         */
        public void register(ClientCall<?, ?> call) {
            if (cancelled.get() || closed.get()) {
                call.cancel("AI stream is no longer active", null);
                return;
            }
            calls.add(call);
            if ((cancelled.get() || closed.get()) && calls.remove(call)) {
                call.cancel("AI stream is no longer active", null);
            }
        }

        /**
         * 调用结束后释放引用。
         *
         * @param call gRPC 调用
         */
        public void release(ClientCall<?, ?> call) {
            calls.remove(call);
        }

        /**
         * 返回当前仍未释放的调用数。
         *
         * @return 调用数
         */
        public int activeCallCount() {
            return calls.size();
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            closed.set(true);
            cancelOutstanding();
        }

        private void close() {
            if (cancelled.get() || !closed.compareAndSet(false, true)) {
                return;
            }
            cancelOutstanding();
        }

        private void cancelOutstanding() {
            for (ClientCall<?, ?> call : new ArrayList<>(calls)) {
                if (calls.remove(call)) {
                    call.cancel("AI stream is no longer active", null);
                }
            }
        }
    }
}
