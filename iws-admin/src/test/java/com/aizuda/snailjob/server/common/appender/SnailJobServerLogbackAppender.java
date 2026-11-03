/*
 * @Date         : 2026-10-26
 * @Author       : Codex
 * @Job          : SnailJob 日志配置隔离测试替身
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 仅用于在主应用测试中加载调度中心 logback 配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.aizuda.snailjob.server.common.appender;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * 调度中心日志 appender 的测试替身。
 *
 * @author Codex
 */
public class SnailJobServerLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent eventObject) {
        // 文件路由验收不依赖调度中心的远端日志接收器。
    }
}
