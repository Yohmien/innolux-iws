/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.1
 * @Description  : Admin 监控人员 SSO 会话与机器监控凭据分流；
 * @ModifyRecord : V1.0 新建；V1.1 接入统一身份并恢复浏览器 CSRF；
 */

package com.innolux.iws.monitor.admin.config;

import com.innolux.iws.monitor.admin.security.IamMonitorSessionFilter;
import com.innolux.iws.monitor.admin.security.MonitorPrincipal;
import com.innolux.iws.monitor.admin.service.IamMonitorPendingRevocationService;
import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.common.auth.utils.IamBrowserUrls;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Admin 监控安全配置。
 */
@EnableWebSecurity
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final AdminServerProperties adminServerProperties;
    private final IamMonitorProperties iamProperties;
    private final IamMonitorPendingRevocationService pendingRevocationService;
    private final IamMonitorSessionFilter sessionFilter;

    @Value("${spring.security.user.name:}")
    private String machineUsername;

    @Value("${spring.security.user.password:}")
    private String machinePassword;

    /**
     * 机器注册和 Actuator 独立过滤链；Basic 凭据不能进入人员控制台。
     *
     * @param httpSecurity Spring Security 配置
     * @return 机器过滤链
     * @throws Exception 构建异常
     */
    @Bean
    @Order(1)
    public SecurityFilterChain machineFilterChain(HttpSecurity httpSecurity) throws Exception {
        String adminContextPath = adminServerProperties.getContextPath();
        RequestMatcher machinePaths = request ->
            request.getRequestURI().equals(adminContextPath + "/instances")
                || request.getRequestURI().startsWith(adminContextPath + "/instances/")
                || request.getRequestURI().startsWith("/actuator");
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        return httpSecurity
            .securityMatcher(machinePaths)
            .authorizeHttpRequests(authorize ->
                authorize.requestMatchers("/actuator/**").hasRole("MONITOR_MACHINE")
                    .anyRequest().hasAnyRole("MONITOR_MACHINE", "IAM_MONITOR"))
            .httpBasic(basic -> {
            })
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(this::requiresBrowserCsrf))
            .addFilterBefore(sessionFilter, CsrfFilter.class)
            .build();
    }

    /**
     * 人员控制台统一身份过滤链。
     *
     * @param httpSecurity Spring Security 配置
     * @return 浏览器过滤链
     * @throws Exception 构建异常
     */
    @Bean
    @Order(2)
    public SecurityFilterChain browserFilterChain(HttpSecurity httpSecurity) throws Exception {
        String adminContextPath = adminServerProperties.getContextPath();
        PathPatternRequestMatcher.Builder mvc = PathPatternRequestMatcher.withDefaults();
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        return httpSecurity
            .headers(header ->
                header.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
            .authorizeHttpRequests(authorize ->
                authorize.requestMatchers(
                        mvc.matcher(adminContextPath + "/assets/**"),
                        mvc.matcher(adminContextPath + "/sso/**"),
                        mvc.matcher(adminContextPath + "/error")
                    ).permitAll()
                    .anyRequest().hasRole("IAM_MONITOR"))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(logout ->
                logout.logoutUrl(adminContextPath + "/logout")
                    .addLogoutHandler(centerLogoutHandler())
                    .logoutSuccessHandler((request, response, authentication) ->
                        response.sendRedirect(IamBrowserUrls.resolveLoopbackHost(
                            iamProperties.getPublicBaseUrl(),
                            request.getServerName()
                        ) + "/login"
                            + (Boolean.TRUE.equals(request.getAttribute("iamCenterRevokePending"))
                                ? "?logoutPending=1"
                                : Boolean.TRUE.equals(request.getAttribute("iamCenterRevokeStale"))
                                    ? "?logoutStale=1"
                                : Boolean.TRUE.equals(request.getAttribute("iamCenterRevokePersistenceFailed"))
                                    ? "?logoutPersistFailed=1"
                                    : ""))))
            .exceptionHandling(exception ->
                exception.authenticationEntryPoint((request, response, authException) -> {
                    String returnPath = request.getRequestURI();
                    String startUrl = adminContextPath + "/sso/start?returnPath="
                        + URLEncoder.encode(returnPath, StandardCharsets.UTF_8);
                    response.sendRedirect(startUrl);
                }))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(this::requiresBrowserCsrf))
            .addFilterBefore(sessionFilter, CsrfFilter.class)
            .build();
    }

    /**
     * 机器用户仅用于实例注册和 Actuator Basic 认证。
     *
     * @return 机器用户服务
     */
    @Bean
    public UserDetailsService monitorMachineUsers() {
        if (machineUsername.isBlank() || machinePassword.isBlank()) {
            throw new IllegalStateException("监控机器认证用户名或密码未配置");
        }
        return new InMemoryUserDetailsManager(
            User.withUsername(machineUsername)
                .password("{noop}" + machinePassword)
                .roles("MONITOR_MACHINE")
                .build()
        );
    }

    /**
     * 人员退出时撤销中心根会话，从而覆盖当前根下全部应用。
     *
     * @return 退出处理器
     */
    @Bean
    public LogoutHandler centerLogoutHandler() {
        return (request, response, authentication) -> {
            Authentication current = authentication;
            if (current == null) {
                current = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            }
            if (current != null && current.getPrincipal() instanceof MonitorPrincipal principal) {
                IamMonitorPendingRevocationService.PendingRevocationResult result =
                    pendingRevocationService.revokeOrQueue(new IamSessionStatusRequest(
                        principal.rootSid(),
                        principal.applicationId(),
                        principal.frontendId(),
                        principal.sessionId(),
                        principal.sessionGeneration(),
                        principal.terminal()
                    ));
                request.setAttribute("iamCenterRevokePending", result.pending());
                request.setAttribute("iamCenterRevokeStale", result.stale());
                request.setAttribute("iamCenterRevokePersistenceFailed", result.persistenceFailed());
            }
        };
    }

    private boolean requiresBrowserCsrf(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
            || "OPTIONS".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method)) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        return authorization == null
            || !authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }
}
