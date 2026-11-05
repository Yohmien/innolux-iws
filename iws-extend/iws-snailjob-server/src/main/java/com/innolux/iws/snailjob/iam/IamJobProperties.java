/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : Job 统一身份应用组、节点和显式本地用户映射配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Job 统一身份客户端配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "iam.client")
public class IamJobProperties {

    /**
     * 浏览器访问中心的公开基址。
     */
    private String publicBaseUrl;

    /**
     * 服务端访问中心的内部基址。
     */
    private String centerBaseUrl;

    /**
     * SSO 应用组标识。
     */
    private String applicationId;

    /**
     * 当前应用类型。
     */
    private String applicationType;

    /**
     * Job 控制台的受信前端标识。
     */
    private String frontendId;

    /**
     * 当前服务节点标识。
     */
    private String instanceId;

    /**
     * 应用组共享的服务端密钥。
     */
    private String clientSecret;

    /**
     * 中心权威账号到既有 Job 本地账号的显式映射。
     */
    private Map<String, String> accounts = new LinkedHashMap<>();

    /**
     * 连接中心超时毫秒数。
     */
    private long connectTimeoutMs = 2000;

    /**
     * 中心响应超时毫秒数。
     */
    private long responseTimeoutMs = 5000;

    /**
     * 长连接撤权检查间隔秒数。
     */
    private long validationIntervalSeconds = 15;
}
