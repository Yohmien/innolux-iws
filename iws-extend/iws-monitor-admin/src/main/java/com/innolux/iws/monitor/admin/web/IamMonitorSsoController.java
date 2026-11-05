/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.0
 * @Description  : Admin 监控统一身份启动、回调与本地 Spring Security 会话建立；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.monitor.admin.web;

import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.monitor.admin.config.IamMonitorProperties;
import com.innolux.iws.monitor.admin.security.MonitorPrincipal;
import com.innolux.iws.monitor.admin.service.IamMonitorCenterClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Admin 监控统一身份入口。
 */
@Controller
@RequiredArgsConstructor
public class IamMonitorSsoController {

    private static final String STATE_SESSION_KEY = "iamMonitorState";
    private static final String RETURN_PATH_SESSION_KEY = "iamMonitorReturnPath";

    private final IamMonitorProperties properties;
    private final IamMonitorCenterClient centerClient;

    /**
     * 创建短时 state 并跳转中心登录。
     *
     * @param returnPath 监控内返回路径
     * @param request    HTTP 请求
     * @param response   HTTP 响应
     * @throws Exception 跳转异常
     */
    @GetMapping("/admin/sso/start")
    public void start(
        @RequestParam(defaultValue = "/admin/") String returnPath,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws Exception {
        String safeReturnPath = normalizeMonitorPath(returnPath);
        String state = UUID.randomUUID().toString().replace("-", "");
        String transactionId = UUID.randomUUID().toString().replace("-", "");
        HttpSession session = request.getSession(true);
        synchronized (session) {
            session.setAttribute(STATE_SESSION_KEY, state);
            session.setAttribute(RETURN_PATH_SESSION_KEY, safeReturnPath);
        }

        String launchPath = "/sso/launch?applicationId="
            + encode(properties.getApplicationId())
            + "&frontendId=" + encode(properties.getFrontendId())
            + "&state=" + encode(state)
            + "&returnPath=" + encode(safeReturnPath)
            + "&terminal=WEB"
            + "&transactionId=" + encode(transactionId);
        String centerLogin = properties.getPublicBaseUrl()
            + "/login?redirect=" + encode(launchPath);
        response.sendRedirect(centerLogin);
    }

    /**
     * 核销票据并建立独立 Spring Security 会话。
     *
     * @param ticket  一次性票据
     * @param state   随机状态
     * @param applicationId 目标应用
     * @param frontendId 受信前端
     * @param terminal 终端
     * @param request HTTP 请求
     * @return 成功时跳转监控原页面，失败时返回 401
     */
    @GetMapping("/admin/sso/callback")
    @ResponseBody
    public ResponseEntity<?> callback(
        @RequestParam String ticket,
        @RequestParam String state,
        @RequestParam String applicationId,
        @RequestParam String frontendId,
        @RequestParam String terminal,
        HttpServletRequest request
    ) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return error("统一身份回调缺少浏览器会话");
        }
        if (!properties.getApplicationId().equals(applicationId)
            || !properties.getFrontendId().equals(frontendId)
            || !"WEB".equalsIgnoreCase(terminal)) {
            return error("统一身份回调目标或受信前端不匹配");
        }
        synchronized (session) {
            Object expectedState = session.getAttribute(STATE_SESSION_KEY);
            if (expectedState == null || !expectedState.toString().equals(state)) {
                return error("统一身份 state 校验失败");
            }
            Object returnPath = session.getAttribute(RETURN_PATH_SESSION_KEY);
            session.removeAttribute(STATE_SESSION_KEY);
            session.removeAttribute(RETURN_PATH_SESSION_KEY);
            try {
                IamTicketExchangeResponse inspected = centerClient.inspect(ticket, frontendId, state);
                MonitorPrincipal preparedPrincipal = new MonitorPrincipal(
                    inspected.userId(),
                    inspected.username(),
                    inspected.rootSid(),
                    inspected.applicationId(),
                    inspected.frontendId(),
                    inspected.sessionId(),
                    inspected.sessionGeneration(),
                    inspected.terminal(),
                    inspected.instanceId(),
                    inspected.expiresAt()
                );
                IamTicketExchangeResponse identity = centerClient.exchange(
                    ticket,
                    frontendId,
                    state,
                    UUID.randomUUID().toString().replace("-", "")
                );
                if (!preparedPrincipal.sessionId().equals(identity.sessionId())
                    || preparedPrincipal.sessionGeneration() != identity.sessionGeneration()) {
                    throw new IllegalStateException("统一身份应用会话提交结果不匹配");
                }
                request.changeSessionId();
                UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                        preparedPrincipal, null, List.of(new SimpleGrantedAuthority("ROLE_IAM_MONITOR"))
                    );
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    context
                );
                session.setAttribute("iamMonitorValidatedAt", System.currentTimeMillis());
                return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", normalizeMonitorPath(
                        returnPath == null ? "/admin/" : returnPath.toString()
                    ))
                    .build();
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
                return error(e.getMessage() == null ? "统一身份回调失败" : e.getMessage());
            }
        }
    }

    private ResponseEntity<String> error(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.TEXT_HTML)
            .body("<!doctype html><meta charset=\"utf-8\">"
                + "<link rel=\"icon\" type=\"image/x-icon\" "
                + "href=\"/admin/assets/img/iws-auth-icon-transparent-20261104.ico\">"
                + "<title>统一身份回调失败</title>"
                + "<main style=\"font:16px sans-serif;padding:40px\"><h1>统一身份回调失败</h1><p>"
                + escape(message)
                + "</p><p><a href=\"/admin/sso/start\">重新进入统一认证</a></p></main>");
    }

    private String normalizeMonitorPath(String value) {
        String path = value == null || value.isBlank() ? "/admin/" : value.trim();
        if (!path.startsWith("/admin") || path.startsWith("//")
            || path.contains("\r") || path.contains("\n")) {
            return "/admin/";
        }
        return path;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
