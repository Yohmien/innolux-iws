/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 在 AI 责任链同步发起 gRPC 调用时传递当前 HTTP 流取消键；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

/**
 * AI 流取消键线程上下文。
 */
public final class IamAiStreamCancellationContext {

    private static final ThreadLocal<IamAiStreamCancellationRegistry.Scope> CURRENT =
        new ThreadLocal<>();

    private IamAiStreamCancellationContext() {
    }

    /**
     * 绑定当前同步订阅线程的流键。
     *
     * @param scope 流作用域
     * @return 可恢复原值的关闭器
     */
    public static Scope open(IamAiStreamCancellationRegistry.Scope scope) {
        IamAiStreamCancellationRegistry.Scope previous = CURRENT.get();
        CURRENT.set(scope);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * 获取当前流键。
     *
     * @return 流键，不在受管流订阅线程中时为空
     */
    public static IamAiStreamCancellationRegistry.Scope current() {
        return CURRENT.get();
    }

    /**
     * 线程上下文恢复器。
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
