/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.0
 * @Description  : 启用监控中心撤销待确认任务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.monitor.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 监控统一身份撤销重试调度配置。
 */
@Configuration
@EnableScheduling
public class IamMonitorPendingRevocationConfig {
}
