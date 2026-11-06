/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : Job 统一身份启动、回调、Token 桥接和统一退出；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.server.web.interceptor.AuthenticationInterceptor;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.utils.IamBrowserUrls;
import com.innolux.iws.common.auth.utils.IamSsoPages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Job 统一身份接入控制器。
 */
@Controller
@RequiredArgsConstructor
public class IamJobSsoController {

    private static final String STATE_SESSION_KEY = "iamJobState";
    private static final String RETURN_PATH_SESSION_KEY = "iamJobReturnPath";
    private static final String BOOTSTRAP_TOKEN_SESSION_KEY = "iamJobBootstrapToken";
    private static final String BOOTSTRAP_RETURN_PATH_SESSION_KEY = "iamJobBootstrapReturnPath";
    private static final String BOOTSTRAP_NAMESPACE_SESSION_KEY = "iamJobBootstrapNamespace";

    private final IamJobProperties properties;
    private final IamJobCenterClient centerClient;
    private final IamJobLocalSessionService localSessionService;

    /**
     * 创建短时事务并跳转中心统一登录。
     *
     * @param returnPath Job 内返回路径
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @throws Exception 跳转失败
     */
    @GetMapping("/iam/sso/start")
    public void start(
        @RequestParam(defaultValue = "/") String returnPath,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws Exception {
        String safeReturnPath = normalizeReturnPath(returnPath);
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
        String centerBase = IamBrowserUrls.resolveLoopbackHost(
            properties.getPublicBaseUrl(),
            request.getServerName()
        );
        response.sendRedirect(centerBase + "/login?redirect=" + encode(launchPath));
    }

    /**
     * 核销中心票据并在浏览器会话暂存一次性本地 Token。
     *
     * @param ticket 中心票据
     * @param state 浏览器随机状态
     * @param applicationId 目标应用
     * @param frontendId 受信前端
     * @param terminal 终端
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @throws Exception 写出失败
     */
    @GetMapping("/iam/sso/callback")
    public void callback(
        @RequestParam String ticket,
        @RequestParam String state,
        @RequestParam String applicationId,
        @RequestParam String frontendId,
        @RequestParam String terminal,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws Exception {
        HttpSession session = request.getSession(false);
        if (session == null) {
            callbackError(request, response, "统一身份回调缺少浏览器会话");
            return;
        }
        if (!properties.getApplicationId().equals(applicationId)
            || !properties.getFrontendId().equals(frontendId)
            || !"WEB".equalsIgnoreCase(terminal)) {
            callbackError(request, response, "统一身份回调目标或受信前端不匹配");
            return;
        }
        synchronized (session) {
            Object expectedState = session.getAttribute(STATE_SESSION_KEY);
            if (expectedState == null || !expectedState.toString().equals(state)) {
                callbackError(request, response, "统一身份 state 校验失败");
                return;
            }
            session.removeAttribute(STATE_SESSION_KEY);
            session.removeAttribute(RETURN_PATH_SESSION_KEY);
            try {
                IamTicketExchangeResponse inspected = centerClient.inspect(ticket, frontendId, state);
                IamJobLocalSessionService.PreparedSession prepared =
                    localSessionService.prepare(inspected);
                IamTicketExchangeResponse identity = centerClient.exchange(
                    ticket,
                    frontendId,
                    state,
                    UUID.randomUUID().toString().replace("-", "")
                );
                IamJobLocalSessionService.IssuedSession issued =
                    localSessionService.issue(identity, prepared);
                request.changeSessionId();
                session.setAttribute(BOOTSTRAP_TOKEN_SESSION_KEY, issued.accessToken());
                session.setAttribute(BOOTSTRAP_RETURN_PATH_SESSION_KEY, issued.returnPath());
                session.setAttribute(BOOTSTRAP_NAMESPACE_SESSION_KEY, issued.namespaceId());
                response.sendRedirect(request.getContextPath() + "/iam/sso/complete");
            } catch (RuntimeException e) {
                callbackError(request, response, e.getMessage() == null ? "统一身份回调失败" : e.getMessage());
            }
        }
    }

    /**
     * 消费外层 IWS 申请的一次性票据并建立 Job 本地会话。
     *
     * @param ticket 中心票据
     * @param state 中心绑定状态
     * @param applicationId 目标应用
     * @param frontendId 受信前端
     * @param terminal 终端
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @throws Exception 写出失败
     */
    @GetMapping("/iam/sso/bridge")
    public void bridge(
        @RequestParam String ticket,
        @RequestParam String state,
        @RequestParam String applicationId,
        @RequestParam String frontendId,
        @RequestParam String terminal,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws Exception {
        response.setHeader("Cache-Control", "no-store");
        if (!properties.getApplicationId().equals(applicationId)
            || !properties.getFrontendId().equals(frontendId)
            || !"WEB".equalsIgnoreCase(terminal)) {
            callbackError(request, response, "嵌入应用桥接目标或受信前端不匹配");
            return;
        }
        try {
            IamTicketExchangeResponse inspected = centerClient.inspect(ticket, frontendId, state);
            IamJobLocalSessionService.PreparedSession prepared =
                localSessionService.prepare(inspected);
            IamTicketExchangeResponse identity = centerClient.exchange(
                ticket,
                frontendId,
                state,
                UUID.randomUUID().toString().replace("-", "")
            );
            IamJobLocalSessionService.IssuedSession issued =
                localSessionService.issue(identity, prepared);
            String returnPath = browserPath(
                request.getContextPath(),
                normalizeReturnPath(issued.returnPath())
            );
            response.setStatus(200);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(IamSsoPages.decorate(IamSsoPages.bootstrapPage(
                "正在进入 IWS+ 调度中心",
                "正在建立 Job 本地会话",
                "token",
                issued.accessToken(),
                returnPath,
                "namespaceId",
                issued.namespaceId()
            )));
        } catch (RuntimeException e) {
            callbackError(
                request,
                response,
                e.getMessage() == null ? "嵌入应用桥接失败" : e.getMessage()
            );
        }
    }

    /**
     * 输出一次性 Token 桥接页，Token 不进入 URL 或服务端日志。
     *
     * @param request HTTP 请求
     * @return 桥接页
     */
    @GetMapping(value = "/iam/sso/complete", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> complete(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String html = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <link rel="icon" type="image/png" sizes="1254x1254" href="%s/iws-auth-icon-transparent-20261104.png?v=20261104-1">
              <link rel="alternate icon" type="image/x-icon" sizes="16x16 32x32 48x48 64x64 128x128 256x256" href="%s/iws-auth-icon-transparent-20261104.ico?v=20261104-1">
              <title>正在进入 IWS+ 调度中心</title>
            </head>
            <main style="font:16px sans-serif;padding:40px;color:#1f2937">
              <h1 style="font-size:20px">正在建立 Job 本地会话</h1>
              <p id="status">请稍候...</p>
              <p><a id="retry" href="%s/iam/sso/start">重新通过统一身份登录</a></p>
            </main>
            <script>
            (async function () {
              const status = document.getElementById('status');
              try {
                const response = await fetch('%s/iam/sso/bootstrap', {
                  credentials: 'same-origin',
                  cache: 'no-store',
                  headers: {Accept: 'application/json'}
                });
                const body = await response.json();
                if (!response.ok || !body.accessToken) {
                  throw new Error(body.message || '统一身份会话桥接失败');
                }
                localStorage.setItem('token', JSON.stringify(body.accessToken));
                localStorage.setItem('namespaceId', JSON.stringify(body.namespaceId));
                window.location.replace(body.returnPath || '%s/');
              } catch (error) {
                status.textContent = error.message || '统一身份会话桥接失败';
              }
            })();
            </script>
            </html>
            """.formatted(contextPath, contextPath, contextPath, contextPath, contextPath);
        return noStore(IamSsoPages.decorate(html));
    }

    /**
     * 一次性取出本地 Token 并从浏览器会话删除。
     *
     * @param request HTTP 请求
     * @return Token 和返回路径
     */
    @GetMapping(value = "/iam/sso/bootstrap", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> bootstrap(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("message", "统一身份桥接会话不存在"));
        }
        Object token = session.getAttribute(BOOTSTRAP_TOKEN_SESSION_KEY);
        Object returnPath = session.getAttribute(BOOTSTRAP_RETURN_PATH_SESSION_KEY);
        Object namespaceId = session.getAttribute(BOOTSTRAP_NAMESPACE_SESSION_KEY);
        session.removeAttribute(BOOTSTRAP_TOKEN_SESSION_KEY);
        session.removeAttribute(BOOTSTRAP_RETURN_PATH_SESSION_KEY);
        session.removeAttribute(BOOTSTRAP_NAMESPACE_SESSION_KEY);
        if (token == null) {
            return ResponseEntity.status(401).body(Map.of("message", "统一身份桥接票据不存在或已使用"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", token.toString());
        body.put("namespaceId", namespaceId == null ? "" : namespaceId.toString());
        body.put(
            "returnPath",
            browserPath(
                request.getContextPath(),
                normalizeReturnPath(returnPath == null ? "/" : returnPath.toString())
            )
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .body(body);
    }

    /**
     * 提供入口守卫，阻止原生密码登录页继续作为人员入口。
     *
     * @param request HTTP 请求
     * @return JavaScript
     */
    @GetMapping(value = "/iam/sso/guard.js", produces = "application/javascript")
    @ResponseBody
    public ResponseEntity<String> guard(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String centerLogin = IamBrowserUrls.resolveLoopbackHost(
            properties.getPublicBaseUrl(),
            request.getServerName()
        ) + "/login";
        String script = """
            (function () {
              const context = '@@IWS_CONTEXT@@';
              const tokenKey = 'token';
              const startUrl = context + '/iam/sso/start';
              const logoutUrl = context + '/iam/sso/logout';
              const centerLogin = '@@IWS_CENTER_LOGIN@@';
              const readToken = () => {
                const raw = localStorage.getItem(tokenKey);
                if (!raw) return null;
                try { return JSON.parse(raw); } catch (_) { return raw; }
              };
              let logoutStarted = false;
              let explicitLogoutRequested = false;
              const clearLocalToken = () => originalRemove.call(localStorage, tokenKey);
              const tokenExpired = (token) => {
                try {
                  const part = String(token).split('.')[1];
                  if (!part) return false;
                  const normalized = part.replace(/-/g, '+').replace(/_/g, '/');
                  const payload = JSON.parse(atob(normalized + '==='.slice((normalized.length + 3) % 4)));
                  return Boolean(payload.exp && payload.exp * 1000 <= Date.now());
                } catch (_) {
                  return false;
                }
              };
              const sameCurrentToken = (token) => readToken() === token;
              const reauthenticate = (token) => {
                if (!sameCurrentToken(token)) return;
                clearLocalToken();
                goLogin();
              };
              const requestExplicitLogout = (token) => {
                if (logoutStarted) return;
                logoutStarted = true;
                fetch(logoutUrl, {
                  method: 'POST',
                  credentials: 'same-origin',
                  headers: {'SNAIL-JOB-AUTH': token}
                }).then(response => {
                  if (response.status === 401) {
                    reauthenticate(token);
                    return;
                  }
                  if (!response.ok) throw new Error('统一退出失败');
                  const localCredentialValid =
                    response.headers.get('X-IAM-Local-Credential-Valid') !== 'false';
                  if (!sameCurrentToken(token)) return;
                  clearLocalToken();
                  location.replace(localCredentialValid ? centerLogin : startUrl);
                }).catch(error => {
                  logoutStarted = false;
                  window.alert(error.message || '统一退出失败，请稍后重试');
                });
              };
              const watchUnauthorized = (status, token, url) => {
                if (status === 401 && token && !String(url).includes('/iam/sso/logout')) {
                  reauthenticate(token);
                }
              };
              const originalFetch = window.fetch;
              window.fetch = function () {
                const token = readToken();
                const url = arguments[0] && arguments[0].url ? arguments[0].url : arguments[0];
                return originalFetch.apply(this, arguments).then(function (response) {
                  watchUnauthorized(response.status, token, url);
                  return response;
                });
              };
              const originalXhrOpen = XMLHttpRequest.prototype.open;
              const originalXhrSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (method, url) {
                this.__iamUrl = url;
                return originalXhrOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function () {
                const request = this;
                const token = readToken();
                request.addEventListener('loadend', function () {
                  watchUnauthorized(request.status, token, request.__iamUrl);
                }, {once: true});
                return originalXhrSend.apply(this, arguments);
              };
              const returnPath = () => '/' + (location.hash || '');
              const goLogin = () => {
                location.replace(startUrl + '?returnPath=' + encodeURIComponent(returnPath()));
              };
              const originalRemove = Storage.prototype.removeItem;
              document.addEventListener('click', function (event) {
                const target = event.target instanceof Element
                  ? event.target.closest('button,[role="button"],.n-button')
                  : null;
                if (!target) return;
                const buttonText = (target.innerText || target.textContent || '').trim();
                const dialog = target.closest('[role="dialog"],.n-dialog');
                const dialogText = dialog ? dialog.innerText || '' : '';
                if ((buttonText === '确认' || buttonText === 'Confirm')
                    && (dialogText.includes('确认退出登录吗？')
                      || dialogText.includes('Are you sure you want to log out?'))) {
                  explicitLogoutRequested = true;
                }
              }, true);
              Storage.prototype.removeItem = function (key) {
                if (this === localStorage && key === tokenKey) {
                  const token = readToken();
                  if (token) {
                    if (explicitLogoutRequested) {
                      explicitLogoutRequested = false;
                      requestExplicitLogout(token);
                    } else {
                      reauthenticate(token);
                    }
                    return;
                  }
                }
                return originalRemove.apply(this, arguments);
              };
              if (!readToken()) {
                goLogin();
                return;
              }
              window.addEventListener('hashchange', function () {
                if (!readToken()) goLogin();
              });
              window.setInterval(function () {
                const token = readToken();
                if (!token) {
                  goLogin();
                  return;
                }
                if (tokenExpired(token)) reauthenticate(token);
              }, 1000);
            })();
            """
            .replace("@@IWS_CONTEXT@@", jsString(contextPath))
            .replace("@@IWS_CENTER_LOGIN@@", jsString(centerLogin));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/javascript"))
            .cacheControl(CacheControl.noStore())
            .body(script);
    }

    /**
     * 撤销当前中心根会话。
     *
     * @param token Job 本地 Token
     * @return 无内容响应
     */
    @PostMapping("/iam/sso/logout")
    @ResponseBody
    public ResponseEntity<Void> logout(
        @RequestHeader(AuthenticationInterceptor.AUTHENTICATION) String token
    ) {
        final IamJobLocalSessionService.IamTokenClaims claims;
        try {
            claims = localSessionService.verify(token);
        } catch (RuntimeException invalidLocalToken) {
            // 本地凭据已过期、被删除或本地密钥已变化时，不能再向中心猜 rootSid；
            // 返回成功让守卫清理当前浏览器会话，再重新进入统一认证。
            return ResponseEntity.noContent()
                .header(IamProtocolConstants.LOGOUT_LOCAL_VALID_HEADER, "false")
                .header(IamProtocolConstants.LOGOUT_CENTER_REVOKED_HEADER, "false")
                .build();
        }
        try {
            boolean revoked = centerClient.revokeRoot(
                claims.rootSid(),
                claims.frontendId(),
                claims.sessionId(),
                claims.sessionGeneration(),
                claims.terminal()
            );
            return ResponseEntity.noContent()
                .header(IamProtocolConstants.LOGOUT_LOCAL_VALID_HEADER, "true")
                .header(IamProtocolConstants.LOGOUT_CENTER_REVOKED_HEADER, Boolean.toString(revoked))
                .build();
        } catch (RuntimeException centerUnavailable) {
            return ResponseEntity.status(503).build();
        }
    }

    private ResponseEntity<String> callbackError(
        HttpServletRequest request,
        HttpServletResponse response,
        String message
    )
        throws java.io.IOException {
        response.setStatus(401);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding("UTF-8");
        String contextPath = request.getContextPath();
        response.getWriter().write(IamSsoPages.decorate("""
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="icon" type="image/png" sizes="1254x1254" href="%s/iws-auth-icon-transparent-20261104.png?v=20261104-1">
            <link rel="alternate icon" type="image/x-icon" sizes="16x16 32x32 48x48 64x64 128x128 256x256" href="%s/iws-auth-icon-transparent-20261104.ico?v=20261104-1">
            <title>统一身份回调失败</title></head>
            <main style="font:16px sans-serif;padding:40px">
              <h1 style="font-size:20px">统一身份回调失败</h1>
              <p>%s</p><p><a href="%s">重新进入统一认证</a></p>
            </main>
            """.formatted(
            contextPath,
            contextPath,
            escape(message),
            contextPath + "/iam/sso/start"
        )));
        return null;
    }

    private ResponseEntity<String> noStore(String html) {
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(html);
    }

    private String normalizeReturnPath(String value) {
        String path = value == null || value.isBlank() ? "/" : value.trim();
        if (!path.startsWith("/") || path.startsWith("//")
            || path.contains("\r") || path.contains("\n") || path.length() > 512) {
            return "/";
        }
        return path;
    }

    private String browserPath(String contextPath, String returnPath) {
        if (contextPath == null || contextPath.isBlank()
            || returnPath.equals(contextPath) || returnPath.startsWith(contextPath + "/")) {
            return returnPath;
        }
        return contextPath + returnPath;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String jsString(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
