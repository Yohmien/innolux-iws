/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 统一身份中心应用注册与票据配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.config;

import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一身份中心配置。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "iam")
public class IamProperties {

    /**
     * 中心浏览器访问基址，用于生成前端入口。
     */
    @NotBlank
    private String publicBaseUrl;

    /**
     * 一次性票据有效期。
     */
    private Duration ticketTtl = Duration.ofSeconds(60);

    /**
     * 中心根会话有效期。
     */
    private Duration rootSessionTtl = Duration.ofHours(8);

    /**
     * 允许请求服务端票据核销接口的时钟偏差。
     */
    private Duration requestClockSkew = Duration.ofMinutes(5);

    /**
     * 受信前端入口；键为 frontendId，不复制后端系统清单。
     */
    @NotEmpty
    private Map<
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String,
        @Valid Frontend
    > frontends = new LinkedHashMap<>();

    /**
     * 目标应用注册表；键为 SSO applicationId，与 sys_client 登录渠道相互独立。
     */
    @NotEmpty
    private Map<
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String,
        @Valid Application
    > applications = new LinkedHashMap<>();

    /**
     * 终端构建时登记的受控页面。中心只允许映射到这些页面键。
     */
    private List<@Valid ViewDefinition> views = new ArrayList<>();

    /**
     * 目标应用配置。
     */
    @Data
    public static class Application {

        /**
         * 应用类型，例如 IWS、SNAIL_JOB、SNAIL_AI、ADMIN_MONITOR。
         */
        @NotBlank
        private String type;

        /**
         * 允许使用该应用的终端类型。
         */
        @NotEmpty
        private Set<@Pattern(regexp = "WEB|PDA") String> terminals =
            new LinkedHashSet<>(Set.of(IamProtocolConstants.TERMINAL_WEB));

        /**
         * 是否允许门户展示和票据签发。
         */
        private boolean enabled = true;

        /**
         * 展示名称。
         */
        @NotBlank
        private String name;

        /**
         * 展示说明。
         */
        private String description;

        /**
         * 浏览器或终端可访问的后端 API 公共地址或同源代理前缀。
         */
        @NotBlank
        private String apiBaseUrl;

        /**
         * 允许连接此后端的受信前端入口。
         */
        @NotEmpty
        private Set<@NotBlank String> allowedFrontendIds = new LinkedHashSet<>();

        /**
         * 原角色菜单权限码；为空时仅要求账号有效。
         */
        @NotBlank
        private String accessPermission;

        /**
         * 目标应用调用核销接口时使用的环境密钥引用。
         */
        @NotBlank
        private String clientSecret;

        /**
         * 应用组内允许登记的节点；空集合时不接受服务端调用。
         */
        @NotEmpty
        private Set<@NotBlank String> allowedInstanceIds = new LinkedHashSet<>();

        /**
         * 独立应用使用的会话命名空间。
         */
        @NotBlank
        private String sessionNamespace;

    }

    /**
     * 受信前端入口配置。
     */
    @Data
    public static class Frontend {

        /**
         * 是否允许发起统一登录。
         */
        private boolean enabled = true;

        /**
         * WEB 或 PDA。
         */
        @NotBlank
        @Pattern(regexp = "WEB|PDA")
        private String terminal;

        /**
         * 前端客户端模式；BROWSER 或 APP。
         */
        @NotBlank
        @Pattern(regexp = "BROWSER|APP")
        private String clientMode = "BROWSER";

        /**
         * BROWSER 模式的公开基址；APP 模式不需要配置。
         */
        private String publicBaseUrl;

        /**
         * 受控前端入口地址。
         */
        @NotBlank
        private String launchUri;

        /**
         * 精确票据回调地址。
         */
        @NotBlank
        private String redirectUri;

        /**
         * 精确退出回调地址。
         */
        @NotBlank
        private String logoutUri;
    }

    /**
     * 构建时页面注册项。
     */
    @Data
    public static class ViewDefinition {

        /**
         * 页面所属应用类型。
         */
        @NotBlank
        private String applicationType;

        /**
         * WEB 或 PDA。
         */
        @Pattern(regexp = "WEB|PDA")
        private String terminal;

        /**
         * 前端构建时登记的稳定页面键。
         */
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
        private String viewKey;

        /**
         * 管理页面展示名称。
         */
        @NotBlank
        private String name;

        /**
         * 允许菜单映射声明的受控能力。
         */
        private List<@NotBlank String> requiredCapabilities = new ArrayList<>();
    }
}
