/*
 * @Date         : 2026-10-26
 * @Author       : Codex
 * @Job          : IWS 日志按源码模块分流
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 将主服务日志映射到稳定的源码模块日志文件；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.sift.Discriminator;

import java.util.Set;

/**
 * 将 IWS 自身日志映射到稳定的源码模块日志文件。
 *
 * @author Codex
 */
public final class IwsModuleDiscriminator implements Discriminator<ILoggingEvent> {

    private static final String PACKAGE_PREFIX = "com.innolux.iws.";
    private static final String DEFAULT_MODULE = "application";
    private static final String SQL_LOG_LOGGER = "SQL_FULL";
    private static final Set<String> MODULES = Set.of(
        "ai",
        "common",
        "demo",
        "endpoint",
        "gen",
        "job",
        "process",
        "system",
        "wms",
        "web",
        "workflow"
    );
    private volatile boolean started;

    @Override
    public String getDiscriminatingValue(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        if (SQL_LOG_LOGGER.equals(loggerName)) {
            return "common";
        }
        if (loggerName == null || !loggerName.startsWith(PACKAGE_PREFIX)) {
            return DEFAULT_MODULE;
        }

        String remaining = loggerName.substring(PACKAGE_PREFIX.length());
        int separator = remaining.indexOf('.');
        String module = separator < 0 ? remaining : remaining.substring(0, separator);
        return MODULES.contains(module) ? module : DEFAULT_MODULE;
    }

    @Override
    public String getKey() {
        return "moduleName";
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }
}
