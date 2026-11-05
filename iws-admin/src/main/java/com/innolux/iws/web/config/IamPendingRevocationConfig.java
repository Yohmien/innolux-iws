/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 启用 IWS 中心撤销待确认任务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 统一身份撤销重试调度配置。
 */
@Configuration
@EnableScheduling
public class IamPendingRevocationConfig {
}
