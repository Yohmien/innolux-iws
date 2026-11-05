/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.0
 * @Description  : Admin 监控访问统一身份中心的应用组与节点配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.monitor.admin.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Admin 监控统一身份客户端配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "iam.client")
public class IamMonitorProperties {

    /**
     * 浏览器访问中心的公开基址。
     */
    @NotBlank
    private String publicBaseUrl;

    /**
     * 服务端访问中心的内部基址。
     */
    @NotBlank
    private String centerBaseUrl;

    /**
     * SSO 应用组标识。
     */
    @NotBlank
    private String applicationId;

    /**
     * 当前应用类型。
     */
    @NotBlank
    private String applicationType;

    /**
     * Admin 监控浏览器入口的受信前端标识。
     */
    @NotBlank
    private String frontendId;

    /**
     * 当前服务节点标识。
     */
    @NotBlank
    private String instanceId;

    /**
     * 应用组共享的服务端密钥。
     */
    @NotBlank
    private String clientSecret;

    /**
     * 根会话再次核验的间隔秒数。
     */
    @Min(1)
    private long validationIntervalSeconds = 15;

    /**
     * 连接中心超时毫秒数。
     */
    @Min(1)
    private long connectTimeoutMs = 2000;

    /**
     * 中心响应超时毫秒数。
     */
    @Min(1)
    private long responseTimeoutMs = 5000;

    /**
     * 中心短时不可达时允许继续使用已验证会话的秒数。
     */
    @Min(1)
    private long validationLeaseSeconds = 30;

    /**
     * 中心撤销待确认文件；为空时使用系统临时目录。
     */
    private String pendingRevokeFile = "";
}
