/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : IWS 目标应用连接统一身份中心的配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * IWS 统一身份客户端配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "iam.client")
public class IamClientProperties {

    /**
     * 浏览器可访问的中心基址。
     */
    @NotBlank
    private String publicBaseUrl;

    /**
     * 服务端访问中心的内部基址。
     */
    @NotBlank
    private String centerBaseUrl;

    /**
     * SSO 应用组标识；同一应用副本共享此值。
     */
    @NotBlank
    private String applicationId;

    /**
     * 当前应用类型；由中心受控登记，IWS-A/B 同为 IWS。
     */
    @NotBlank
    private String applicationType;

    /**
     * 当前服务节点标识，仅用于运维定位，不参与人员身份判断。
     */
    @NotBlank
    private String instanceId;

    /**
     * 目标应用服务间共享密钥。
     */
    @NotBlank
    private String clientSecret;

    /**
     * 当前应用在中心登记且由原权限链校验的接入权限码。
     */
    @NotBlank
    private String accessPermission;

    /**
     * 当前应用独立使用的本地会话命名空间。
     */
    @NotBlank
    private String sessionNamespace;

    /**
     * 允许通过统一身份建立的 sys_client 登录渠道。
     */
    @NotEmpty
    private List<@NotBlank String> allowedLoginClientIds = new ArrayList<>();

    /**
     * 允许从当前 IWS 发起一次性令牌桥接的嵌入应用。
     */
    @NotEmpty
    private List<@NotBlank String> bridgeApplications = new ArrayList<>(
        List.of("snail-ai", "snail-job", "admin-monitor")
    );

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
     * 同源浏览器刷新时可静默恢复本地会话的最长时间。
     */
    private Duration sessionResumeTtl = Duration.ofHours(8);

    /**
     * 中心不可达时，已校验根会话允许继续访问的短租约秒数。
     */
    @Min(1)
    private long rootValidationLeaseSeconds = 30;

    /**
     * 根会话再次向中心校验的间隔秒数。
     */
    @Min(1)
    private long rootValidationIntervalSeconds = 15;
}
