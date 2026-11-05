/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 透传原生 ManagedChannel，并登记本次 AI 流创建的 gRPC ClientCall；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI gRPC 调用追踪通道。
 */
public final class IamAiTrackingManagedChannel extends ManagedChannel {

    private final ManagedChannel delegate;
    private final IamAiStreamCancellationRegistry.Scope cancellationScope;

    /**
     * 创建透传通道。
     *
     * @param delegate 原生通道
     * @param cancellationScope 当前 AI 流作用域
     */
    public IamAiTrackingManagedChannel(
        ManagedChannel delegate,
        IamAiStreamCancellationRegistry.Scope cancellationScope
    ) {
        this.delegate = delegate;
        this.cancellationScope = cancellationScope;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions
    ) {
        return new TrackingClientCall<>(
            delegate.newCall(method, callOptions),
            cancellationScope
        );
    }

    @Override
    public String authority() {
        return delegate.authority();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public ManagedChannel shutdown() {
        delegate.shutdown();
        return this;
    }

    @Override
    public ManagedChannel shutdownNow() {
        delegate.shutdownNow();
        return this;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    /**
     * 可撤销 ClientCall 包装。
     */
    private static final class TrackingClientCall<ReqT, RespT>
        extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

        private final IamAiStreamCancellationRegistry.Scope cancellationScope;
        private final AtomicBoolean registered = new AtomicBoolean();

        private TrackingClientCall(
            ClientCall<ReqT, RespT> delegate,
            IamAiStreamCancellationRegistry.Scope cancellationScope
        ) {
            super(delegate);
            this.cancellationScope = cancellationScope;
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            ClientCall.Listener<RespT> releaseListener =
                new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        release();
                        super.onClose(status, trailers);
                    }
                };
            try {
                cancellationScope.register(this);
                registered.set(true);
                super.start(releaseListener, headers);
            }
            catch (RuntimeException e) {
                release();
                throw e;
            }
        }

        @Override
        public void cancel(String message, Throwable cause) {
            release();
            super.cancel(message, cause);
        }

        private void release() {
            if (registered.compareAndSet(true, false)) {
                cancellationScope.release(this);
            }
        }
    }
}
