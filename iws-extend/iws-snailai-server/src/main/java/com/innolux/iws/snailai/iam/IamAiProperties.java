/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : AI 统一身份应用组、节点和显式本地用户映射配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 统一身份客户端配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "iam.client")
public class IamAiProperties {

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
     * AI 控制台的受信前端标识。
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
     * 中心权威账号到既有 AI 本地账号的显式映射。
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
     * 流式响应撤权检查间隔秒数。
     */
    private long validationIntervalSeconds = 15;
}
