/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 将配置中的回环地址转换为浏览器实际访问主机；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.utils;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 统一身份浏览器跳转地址工具。
 */
public final class IamBrowserUrls {

    private IamBrowserUrls() {
    }

    /**
     * 仅将回环主机替换为当前请求主机，保留原方案、端口、路径和参数。
     *
     * @param configuredUrl 已配置的浏览器地址
     * @param requestHost   当前 HTTP 请求主机
     * @return 浏览器可访问地址
     */
    public static String resolveLoopbackHost(String configuredUrl, String requestHost) {
        if (configuredUrl == null || configuredUrl.isBlank()
            || requestHost == null || requestHost.isBlank()) {
            return configuredUrl;
        }
        URI uri;
        try {
            uri = URI.create(configuredUrl);
        } catch (IllegalArgumentException ignored) {
            return configuredUrl;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
            && !"https".equalsIgnoreCase(uri.getScheme())) {
            return configuredUrl;
        }
        if (!isLoopback(uri.getHost()) || isLoopback(requestHost)) {
            return configuredUrl;
        }
        return UriComponentsBuilder.fromUri(uri)
            .host(requestHost)
            .build()
            .toUriString();
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "::1".equals(host)
            || "[::1]".equals(host);
    }
}
