/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 受信 worker 调用上下文；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 仅 job/worker 接线设置的可信调用标记，避免把无 Sa-Token 会话误当授权通过。
 */
public final class ProcessTrustedInvocation {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ProcessTrustedInvocation() {
    }

    /**
     * 在可信上下文内执行。
     *
     * @param runnable 任务
     */
    public static void run(Runnable runnable) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            runnable.run();
        } finally {
            ACTIVE.set(previous);
        }
    }

    /**
     * 在可信上下文内执行并返回结果。
     *
     * @param supplier 任务
     * @param <T> 结果类型
     * @return 结果
     */
    public static <T> T call(java.util.function.Supplier<T> supplier) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            return supplier.get();
        } finally {
            ACTIVE.set(previous);
        }
    }

    /**
     * 当前线程是否处于可信上下文。
     *
     * @return 是否可信
     */
    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }
}
