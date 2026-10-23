/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划有界执行资源与响应预算；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 即时请求的固定并发门禁与响应收口预算。
 *
 * <p>配置缺失或非正数时 fail-closed，不从业务定义或代码猜测容量。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediateExecutionGuard {

    private static final String CONCURRENCY_KEY = "iws.process.immediate.max-concurrency";

    private static final String RESPONSE_RESERVE_KEY = "iws.process.immediate.response-reserve-ms";

    private final int maxConcurrency;

    private final long responseReserveMs;

    private final Semaphore permits;

    private final ExecutorService executor;

    /**
     * 读取部署参数。
     *
     * @param environment 环境属性
     */
    public ImmediateExecutionGuard(Environment environment) {
        this.maxConcurrency = positiveInt(environment.getProperty(CONCURRENCY_KEY));
        this.responseReserveMs = positiveLong(environment.getProperty(RESPONSE_RESERVE_KEY));
        this.permits = new Semaphore(Math.max(0, maxConcurrency));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 是否已配置可用执行资源和响应预算。
     *
     * @return true 表示可接受新请求
     */
    public boolean ready() {
        return maxConcurrency > 0 && responseReserveMs > 0;
    }

    /**
     * 立即尝试取得一个执行许可。
     *
     * @return true 表示取得
     */
    public boolean tryAcquire() {
        return ready() && permits.tryAcquire();
    }

    /**
     * 释放执行许可。
     */
    public void release() {
        if (maxConcurrency > 0) {
            permits.release();
        }
    }

    /**
     * 有界驱动线程池。
     *
     * @return 线程池
     */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * 响应收口余量。
     *
     * @return 毫秒
     */
    public long responseReserveMs() {
        return responseReserveMs;
    }

    /**
     * 停止线程池。
     */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private static int positiveInt(String value) {
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long positiveLong(String value) {
        try {
            return value == null ? 0L : Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
