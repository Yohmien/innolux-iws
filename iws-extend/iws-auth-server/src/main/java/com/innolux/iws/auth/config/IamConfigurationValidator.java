/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 校验认证中心启动所需会话密钥和应用协议配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 认证中心必需配置校验。
 */
@Component
@RequiredArgsConstructor
public class IamConfigurationValidator implements InitializingBean {

    private final IamProperties properties;
    private final Environment environment;

    /**
     * 在 Bean 装配期拒绝空签名密钥，避免以空密钥启动。
     */
    @Override
    public void afterPropertiesSet() {
        String jwtSecret = environment.getProperty("sa-token.jwt-secret-key");
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("认证中心 sa-token.jwt-secret-key 未配置");
        }
        properties.getFrontends().forEach((frontendId, frontend) -> {
            if (!frontend.isEnabled()) {
                return;
            }
            if ("APP".equals(frontend.getClientMode())) {
                if (frontend.getPublicBaseUrl() != null && !frontend.getPublicBaseUrl().isBlank()) {
                    throw new IllegalStateException(
                        "认证中心 APP 前端不需要公开基址: frontendId=" + frontendId
                    );
                }
                return;
            }
            if (!isPublicApiBaseUrl(frontend.getPublicBaseUrl())) {
                throw new IllegalStateException(
                    "认证中心前端公开基址无效: frontendId=" + frontendId
                );
            }
        });
        properties.getApplications().forEach((applicationId, application) -> {
            if (application.isEnabled() && application.getClientSecret().isBlank()) {
                throw new IllegalStateException(
                    "认证中心应用协议密钥未配置: applicationId=" + applicationId
                );
            }
            if (application.isEnabled() && !isPublicApiBaseUrl(application.getApiBaseUrl())) {
                throw new IllegalStateException(
                    "认证中心应用 API 公共地址无效: applicationId=" + applicationId
                );
            }
            application.getAllowedFrontendIds().forEach(frontendId -> {
                IamProperties.Frontend frontend = properties.getFrontends().get(frontendId);
                if (frontend == null || !frontend.isEnabled()) {
                    throw new IllegalStateException(
                        "认证中心应用绑定了未登记或已停用的前端: applicationId="
                            + applicationId + ", frontendId=" + frontendId
                    );
                }
                if (!application.getTerminals().contains(frontend.getTerminal())) {
                    throw new IllegalStateException(
                        "认证中心前后端终端不匹配: applicationId="
                            + applicationId + ", frontendId=" + frontendId
                    );
                }
            });
        });
        validateViews();
    }

    /**
     * 只接受同源绝对路径或 HTTP(S) 公共地址，拒绝空值与内部协议。
     *
     * @param value API 地址
     * @return 是否合法
     */
    private boolean isPublicApiBaseUrl(String value) {
        return value != null
            && (value.startsWith("/") || value.matches("^https?://[^\\s]+$"));
    }

    /**
     * 拒绝引用未知应用类型、终端不支持或重复定义的页面白名单。
     */
    private void validateViews() {
        Map<String, Set<String>> terminalsByType = new HashMap<>();
        properties.getApplications().forEach((applicationId, application) -> {
            if (!application.isEnabled()) {
                return;
            }
            application.getAllowedFrontendIds().forEach(frontendId -> {
                IamProperties.Frontend frontend = properties.getFrontends().get(frontendId);
                if (frontend != null && frontend.isEnabled()) {
                    terminalsByType
                        .computeIfAbsent(application.getType(), ignored -> new HashSet<>())
                        .add(frontend.getTerminal());
                }
            });
        });
        Set<String> viewKeys = new HashSet<>();
        properties.getViews().forEach(view -> {
            Set<String> terminals = terminalsByType.get(view.getApplicationType());
            if (terminals == null) {
                throw new IllegalStateException(
                    "认证中心页面白名单引用了未知应用类型: " + view.getApplicationType()
                );
            }
            if (!terminals.contains(view.getTerminal())) {
                throw new IllegalStateException(
                    "认证中心页面白名单终端未被应用开放: applicationType="
                        + view.getApplicationType() + ", terminal=" + view.getTerminal()
                );
            }
            String identity = view.getApplicationType() + ":" + view.getTerminal() + ":" + view.getViewKey();
            if (!viewKeys.add(identity)) {
                throw new IllegalStateException("认证中心页面白名单重复: " + identity);
            }
        });
    }
}
