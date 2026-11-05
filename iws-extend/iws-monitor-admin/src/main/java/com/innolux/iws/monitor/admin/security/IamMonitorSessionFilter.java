/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.0
 * @Description  : Admin 监控人员会话的根会话、应用准入短租约校验；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.monitor.admin.security;

import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.monitor.admin.config.IamMonitorProperties;
import com.innolux.iws.monitor.admin.service.IamMonitorCenterClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Admin 监控统一身份短租约校验。
 */
@Component
@RequiredArgsConstructor
public class IamMonitorSessionFilter extends OncePerRequestFilter {

    private final IamMonitorProperties properties;
    private final IamMonitorCenterClient centerClient;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/admin/sso/")
            || path.startsWith("/admin/assets/")
            || "/admin/logout".equals(path)
            || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof MonitorPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            reject(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        Object validatedValue = session.getAttribute("iamMonitorValidatedAt");
        long validatedAt = validatedValue instanceof Number number ? number.longValue() : 0L;
        boolean readRequest = "GET".equalsIgnoreCase(request.getMethod())
            || "HEAD".equalsIgnoreCase(request.getMethod())
            || "OPTIONS".equalsIgnoreCase(request.getMethod());
        if (readRequest && validatedAt > 0
            && now - validatedAt < properties.getValidationIntervalSeconds() * 1000) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            IamSessionStatusResponse status = centerClient.status(
                principal.rootSid(),
                principal.frontendId(),
                principal.sessionId(),
                principal.sessionGeneration(),
                principal.terminal()
            );
            if (!status.active() || !principal.userId().equals(status.userId())) {
                reject(request, response);
                return;
            }
            session.setAttribute("iamMonitorValidatedAt", now);
        } catch (RuntimeException e) {
            long leaseMs = properties.getValidationLeaseSeconds() * 1000;
            if (!readRequest || validatedAt <= 0 || now - validatedAt > leaseMs) {
                reject(request, response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)) {
            String returnPath = request.getRequestURI();
            response.sendRedirect("/admin/sso/start?returnPath="
                + URLEncoder.encode(returnPath, StandardCharsets.UTF_8));
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":401,\"msg\":\"统一身份会话已失效，请重新登录\"}");
    }
}
