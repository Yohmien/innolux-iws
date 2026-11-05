/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : Job 人员请求的中心会话复核与本地密码登录关闭；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.server.web.interceptor.AuthenticationInterceptor;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Job 统一身份请求守卫。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class IamJobSessionFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();
    private static final List<String> GROUP_SCOPED_PATHS = List.of(
        "/job",
        "/group",
        "/workflow",
        "/retry",
        "/retry-task",
        "/retry-dead-letter",
        "/scene-config",
        "/notify-config",
        "/notify-recipient",
        "/dashboard"
    );

    private final IamJobCenterClient centerClient;
    private final IamJobLocalSessionService localSessionService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        return path.startsWith("/iam/sso/")
            || path.startsWith("/api/")
            || path.startsWith("/assets/")
            || path.startsWith("/iconify/")
            || path.equals("/error")
            || path.equals("/actuator")
            || path.startsWith("/actuator/")
            || path.equals("/")
            || path.equals("/index.html")
            || path.equals("/favicon.ico")
            || path.equals("/iws-auth-icon-transparent-20261104.png")
            || path.equals("/iws-auth-icon-transparent-20261104.ico");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String path = applicationPath(request);
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/auth/login".equals(path)) {
            writeError(response, 403, "Job 人员登录已统一到 IWS 认证中心");
            return;
        }
        if (isGroupScopedPath(path) && hasSuspiciousPathEncoding(request)) {
            writeError(response, 400, "Job 请求路径包含不允许的编码分隔符");
            return;
        }

        String token = request.getHeader(AuthenticationInterceptor.AUTHENTICATION);
        if (token == null || token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        final IamJobLocalSessionService.IamTokenClaims claims;
        try {
            claims = localSessionService.verify(token);
        } catch (RuntimeException e) {
            writeError(response, 401, "Job 本地会话无效，请重新通过统一身份登录");
            return;
        }

        try {
            IamSessionStatusResponse status = centerClient.status(
                claims.rootSid(),
                claims.frontendId(),
                claims.sessionId(),
                claims.sessionGeneration(),
                claims.terminal()
            );
            if (!status.active() || !claims.subjectUserId().equals(status.userId())) {
                writeError(response, 401, "统一身份会话已失效，请重新登录");
                return;
            }
            if (!localSessionService.matchesMappedUser(status.username(), claims.localUserId())) {
                writeError(response, 401, "统一身份与 Job 本地账号映射不匹配");
                return;
            }
        } catch (RuntimeException e) {
            writeError(response, 503, e.getMessage() == null ? "统一身份中心不可达" : e.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 获取去除容器 context-path 后的应用内路径。
     *
     * @param request HTTP 请求
     * @return 应用内路径
     */
    public static String applicationPath(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        return path == null || path.isBlank() ? "/" : path;
    }

    private boolean isGroupScopedPath(String path) {
        return GROUP_SCOPED_PATHS.stream()
            .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private boolean hasSuspiciousPathEncoding(HttpServletRequest request) {
        String path = request.getRequestURI().toLowerCase(Locale.ROOT);
        for (int i = 0; i < 3; i++) {
            if (path.contains(";")
                || path.contains("\\")
                || path.contains("/../")
                || path.contains("/./")) {
                return true;
            }
            String decoded;
            try {
                decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return true;
            }
            if (decoded.equals(path)) {
                return false;
            }
            path = decoded;
        }
        return false;
    }

    /**
     * 输出统一 JSON 错误。
     *
     * @param response HTTP 响应
     * @param status HTTP 状态
     * @param message 错误信息
     * @throws IOException 写出失败
     */
    public static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
            "{\"code\":" + status + ",\"msg\":\"" + message.replace("\"", "\\\"") + "\"}"
        );
    }
}
