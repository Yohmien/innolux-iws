/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : IWS 统一身份回调、票据核销与统一退出适配；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.utils.IamCredentialFingerprint;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysClientVo;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysClientService;
import com.innolux.iws.system.service.ISysUserService;
import com.innolux.iws.web.config.IamClientProperties;
import com.innolux.iws.web.service.IamCenterClient;
import com.innolux.iws.web.service.IamPendingRevocationService;
import com.innolux.iws.web.service.IamPushSessionGuard;
import com.innolux.iws.web.service.IamSessionResumeService;
import com.innolux.iws.web.service.LoginSessionService;
import com.innolux.iws.web.service.SysLoginService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * IWS 统一身份接入控制器。
 */
@SaIgnore
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/sso")
public class IwsSsoController {

    private final IamClientProperties properties;
    private final IamCenterClient centerClient;
    private final ISysUserService userService;
    private final ISysClientService clientService;
    private final SysLoginService loginService;
    private final LoginSessionService loginSessionService;
    private final PermissionService permissionService;
    private final IamPendingRevocationService pendingRevocationService;
    private final IamPushSessionGuard pushSessionGuard;
    private final IamSessionResumeService resumeService;

    /**
     * 核销中心票据并建立 IWS 自己的本地会话。
     *
     * @param request 票据核销请求
     * @param servletRequest HTTP 请求
     * @param servletResponse HTTP 响应
     * @return IWS 本地 Token 与返回地址
     */
    @PostMapping("/exchange")
    public R<ExchangeResult> exchange(
        @Valid @RequestBody ExchangeRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        if (!properties.getApplicationId().equals(request.applicationId())) {
            throw new ServiceException("票据目标应用与当前 IWS 应用不匹配");
        }
        if (!properties.getAllowedLoginClientIds().contains(request.loginClientId())) {
            throw new ServiceException("不允许使用该登录渠道建立统一身份会话");
        }
        SysClientVo client = clientService.queryByClientId(request.loginClientId());
        if (client == null || !SystemConstants.NORMAL.equals(client.getStatus())) {
            throw new ServiceException("登录渠道不存在或已停用");
        }
        IamTicketExchangeResponse inspected = centerClient.inspect(
            request.ticket(), request.frontendId(), request.state()
        );
        if (!request.frontendId().equals(inspected.frontendId())) {
            throw new ServiceException("票据核销结果与受信前端不匹配");
        }
        SysUserVo user = userService.selectUserById(inspected.userId());
        if (user == null || SystemConstants.DISABLE.equals(user.getStatus())) {
            throw new ServiceException("统一身份账号已停用或不存在");
        }
        if (!hasApplicationAccess(permissionService.getMenuPermission(user.getUserId()))) {
            throw new ServiceException("未获得当前 IWS 应用接入权限", 403);
        }
        LoginUser loginUser = loginService.buildLoginUser(user);
        LoginSessionService.LocalSession localSession = loginSessionService.login(
            loginUser, client, inspected.rootSid()
        );
        String commitId = request.commitId() == null || request.commitId().isBlank()
            ? UUID.randomUUID().toString().replace("-", "")
            : request.commitId();
        try {
            IamTicketExchangeResponse identity = centerClient.exchange(
                request.ticket(), request.frontendId(), request.state(), commitId
            );
            StpUtil.getTokenSession().set("iamRootValidatedAt", Instant.now().toEpochMilli());
            StpUtil.getTokenSession().set(
                IamProtocolConstants.CREDENTIAL_FINGERPRINT_KEY,
                IamCredentialFingerprint.of(user.getPassword())
            );
            StpUtil.getTokenSession().set(
                IamProtocolConstants.APPLICATION_ID_SESSION_KEY,
                properties.getApplicationId()
            );
            StpUtil.getTokenSession().set(
                IamProtocolConstants.APPLICATION_TYPE_SESSION_KEY, properties.getApplicationType()
            );
            StpUtil.getTokenSession().set(
                IamProtocolConstants.FRONTEND_ID_SESSION_KEY, identity.frontendId()
            );
            StpUtil.getTokenSession().set(
                IamProtocolConstants.INSTANCE_ID_SESSION_KEY, properties.getInstanceId()
            );
            StpUtil.getTokenSession().set(
                IamProtocolConstants.APPLICATION_SESSION_ID_KEY, identity.sessionId()
            );
            StpUtil.getTokenSession().set(
                IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY,
                identity.sessionGeneration()
            );
            StpUtil.getTokenSession().set(
                IamProtocolConstants.TERMINAL_SESSION_KEY, identity.terminal()
            );
            StpUtil.getTokenSession().set("iamSessionNamespace", properties.getSessionNamespace());
            writeResumeCookie(
                servletRequest,
                servletResponse,
                resumeService.issue(identity, request.loginClientId())
            );
            return R.ok(exchangeResult(localSession, identity));
        } catch (RuntimeException error) {
            loginService.logout();
            throw error;
        }
    }

    /**
     * 使用同源 HttpOnly 恢复凭据静默重建当前应用会话。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @return 新本地会话；没有可用恢复凭据时 data 为空
     */
    @PostMapping("/resume")
    public R<ExchangeResult> resume(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String resumeToken = readResumeCookie(request);
        if (resumeToken == null) {
            return R.<ExchangeResult>ok(null);
        }
        IamSessionResumeService.ResumeSession resumed = resumeService.resume(resumeToken);
        if (resumed == null) {
            clearResumeCookie(request, response);
            return R.<ExchangeResult>ok(null);
        }
        return R.ok(exchangeResult(resumed.localSession(), resumed.identity()));
    }

    /**
     * 统一退出 IWS 本地会话并撤销中心根会话。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @return 操作结果
     */
    @PostMapping("/logout")
    public R<LogoutResult> logout(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String token = StpUtil.getTokenValue();
        String resumeToken = readResumeCookie(request);
        if (!StpUtil.isLogin()) {
            resumeService.revoke(resumeToken);
            clearResumeCookie(request, response);
            return R.ok("退出成功", new LogoutResult(true, false, false, true));
        }
        Long userId = LoginHelper.getUserId();
        Object rootSid = StpUtil.getTokenSession().get(IamProtocolConstants.ROOT_SID_SESSION_KEY);
        IamPendingRevocationService.PendingRevocationResult revokeResult;
        try {
            Object frontendId = StpUtil.getTokenSession().get(IamProtocolConstants.FRONTEND_ID_SESSION_KEY);
            Object sessionId = StpUtil.getTokenSession().get(IamProtocolConstants.APPLICATION_SESSION_ID_KEY);
            Object generation = StpUtil.getTokenSession().get(
                IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY
            );
            Object terminal = StpUtil.getTokenSession().get(IamProtocolConstants.TERMINAL_SESSION_KEY);
            revokeResult = pendingRevocationService.revokeOrQueue(
                new IamSessionStatusRequest(
                    rootSid == null ? null : rootSid.toString(),
                    properties.getApplicationId(),
                    frontendId == null ? null : frontendId.toString(),
                    sessionId == null ? null : sessionId.toString(),
                    generation instanceof Number number ? number.longValue() : 0L,
                    terminal == null ? null : terminal.toString()
                )
            );
        } finally {
            pushSessionGuard.close(userId, token);
            loginService.logout();
            resumeService.revoke(resumeToken);
            clearResumeCookie(request, response);
        }
        LogoutResult result = new LogoutResult(
            true,
            revokeResult.centerRevoked(),
            revokeResult.pending(),
            revokeResult.stale()
        );
        return R.ok(
            result.centerRevokePending()
                ? "本地已退出，中心撤销待确认，系统将自动重试"
                : result.centerStale()
                    ? "本地已退出，旧应用会话无权撤销当前中心会话"
                    : "退出成功",
            result
        );
    }

    /**
     * 校验当前应用接入权限；超级管理员沿用原权限通配规则。
     *
     * @param permissions 当前原菜单权限
     * @return 是否允许接入
     */
    private boolean hasApplicationAccess(Set<String> permissions) {
        String required = properties.getAccessPermission();
        return required == null || required.isBlank()
            || permissions.contains("*:*:*")
            || permissions.contains(required);
    }

    private ExchangeResult exchangeResult(
        LoginSessionService.LocalSession localSession,
        IamTicketExchangeResponse identity
    ) {
        return new ExchangeResult(
            localSession.accessToken(),
            localSession.expireIn(),
            localSession.clientId(),
            identity.returnPath(),
            identity.issuer(),
            identity.applicationId(),
            identity.applicationType(),
            identity.frontendId(),
            identity.userId(),
            identity.sessionId(),
            identity.sessionGeneration(),
            identity.terminal()
        );
    }

    private String readResumeCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = resumeService.cookieName();
        return Arrays.stream(cookies)
            .filter(cookie -> name.equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private void writeResumeCookie(
        HttpServletRequest request,
        HttpServletResponse response,
        String resumeToken
    ) {
        response.addHeader(
            "Set-Cookie",
            resumeCookie(request, resumeToken)
                .maxAge(properties.getSessionResumeTtl())
                .build()
                .toString()
        );
    }

    private void clearResumeCookie(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        response.addHeader(
            "Set-Cookie",
            resumeCookie(request, "")
                .maxAge(0)
                .build()
                .toString()
        );
    }

    private ResponseCookie.ResponseCookieBuilder resumeCookie(
        HttpServletRequest request,
        String value
    ) {
        return ResponseCookie.from(resumeService.cookieName(), value)
            .httpOnly(true)
            .secure(request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")))
            .sameSite("Lax")
            .path("/");
    }

    /**
     * 统一身份票据核销请求。
     *
     * @param ticket        中心一次性票据
     * @param state         客户端随机状态
     * @param applicationId SSO 应用组标识
     * @param frontendId    受信前端标识
     * @param loginClientId 原 sys_client 登录渠道
     * @param commitId      同一次本地准备的幂等提交标识
     */
    public record ExchangeRequest(
        @NotBlank String ticket,
        @NotBlank String state,
        @NotBlank String applicationId,
        @NotBlank String frontendId,
        @NotBlank String loginClientId,
        String commitId
    ) {
    }

    /**
     * IWS 本地会话结果。
     *
     * @param accessToken IWS 本地 Token
     * @param expireIn    过期秒数
     * @param clientId    原登录渠道
     * @param returnPath  应用内返回地址
     * @param issuer      权威签发方
     * @param applicationId 目标应用标识
     * @param applicationType 应用类型
     * @param frontendId  受信前端入口
     * @param userId      原系统用户主键
     * @param sessionId   应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal    终端类型
     */
    public record ExchangeResult(
        String accessToken,
        long expireIn,
        String clientId,
        String returnPath,
        String issuer,
        String applicationId,
        String applicationType,
        String frontendId,
        Long userId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
    }

    /**
     * IWS 统一退出结果。
     *
     * @param localLoggedOut     本地会话是否已退出
     * @param centerRevoked      中心根会话是否已确认撤销
     * @param centerRevokePending 中心撤销是否待重试
     * @param centerStale        当前应用会话是否已被替换
     */
    public record LogoutResult(
        boolean localLoggedOut,
        boolean centerRevoked,
        boolean centerRevokePending,
        boolean centerStale
    ) {
    }
}
