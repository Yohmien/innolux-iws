/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 为已登录 IWS 签发嵌入应用一次性桥接票据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.auth.service.IamApplicationPreparation;
import com.innolux.iws.auth.service.IamServiceRequestVerifier;
import com.innolux.iws.auth.service.IamSessionRegistry;
import com.innolux.iws.auth.service.IamTicketService;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamAuthorizationResponse;
import com.innolux.iws.common.auth.domain.IamBridgeIssueRequest;
import com.innolux.iws.common.auth.domain.IamBridgeTicketResponse;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.utils.IamSecureTokens;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysUserService;
import com.innolux.iws.web.service.SysLoginService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 嵌入应用一次性桥接票据控制器。
 */
@SaIgnore
@RestController
@RequiredArgsConstructor
@RequestMapping("/iam/bridge")
public class IamBridgeController {

    private static final Set<String> BRIDGEABLE_TYPES = Set.of(
        "SNAIL_JOB", "SNAIL_AI", "ADMIN_MONITOR"
    );

    private final IamProperties properties;
    private final IamServiceRequestVerifier requestVerifier;
    private final IamSessionRegistry sessionRegistry;
    private final IamTicketService ticketService;
    private final ISysUserService userService;
    private final PermissionService permissionService;
    private final SysLoginService loginService;

    /**
     * 为当前 IWS 会话申请目标嵌入应用票据。
     *
     * @param applicationId 来源应用标识
     * @param instanceId 来源实例标识
     * @param timestamp 请求时间戳
     * @param nonce 请求随机值
     * @param signature 请求签名
     * @param body 原始请求体
     * @param request HTTP 请求
     * @return 目标应用一次性桥接票据
     */
    @PostMapping("/issue")
    public R<IamBridgeTicketResponse> issue(
        @RequestHeader(IamProtocolConstants.APPLICATION_HEADER) String applicationId,
        @RequestHeader(IamProtocolConstants.INSTANCE_HEADER) String instanceId,
        @RequestHeader(IamProtocolConstants.TIMESTAMP_HEADER) String timestamp,
        @RequestHeader(IamProtocolConstants.NONCE_HEADER) String nonce,
        @RequestHeader(IamProtocolConstants.SIGNATURE_HEADER) String signature,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        requestVerifier.verify(
            applicationId,
            instanceId,
            timestamp,
            nonce,
            signature,
            request.getMethod(),
            request.getRequestURI(),
            body
        );
        IamBridgeIssueRequest bridgeRequest = JsonUtils.parseObject(
            body,
            IamBridgeIssueRequest.class
        );
        if (bridgeRequest == null) {
            throw new ServiceException("嵌入应用桥接请求不合法", 400);
        }
        requireSourceApplication(applicationId);
        if (!IamProtocolConstants.TERMINAL_WEB.equalsIgnoreCase(bridgeRequest.sourceTerminal())) {
            throw new ServiceException("仅 Web 应用会话可申请嵌入桥接", 403);
        }
        IamSessionStatusResponse sourceStatus = sessionRegistry.status(
            bridgeRequest.rootSid(),
            applicationId,
            bridgeRequest.sourceFrontendId(),
            bridgeRequest.sourceSessionId(),
            bridgeRequest.sourceSessionGeneration(),
            bridgeRequest.sourceTerminal()
        );
        if (!sourceStatus.active()) {
            throw new ServiceException(
                sourceStatus.reason() == null ? "来源应用会话已失效" : sourceStatus.reason(),
                403
            );
        }
        IamProperties.Application targetApplication = requireTargetApplication(
            bridgeRequest.targetApplicationId()
        );
        String targetFrontendId = requireTargetWebFrontend(targetApplication);
        SysUserVo user = userService.selectUserById(sourceStatus.userId());
        if (user == null || SystemConstants.DISABLE.equals(user.getStatus())) {
            throw new ServiceException("统一身份账号已停用或不存在", 403);
        }
        Set<String> permissions = permissionService.getMenuPermission(user.getUserId());
        if (!hasPermission(permissions, targetApplication.getAccessPermission())) {
            throw new ServiceException("未获得目标应用接入权限", 403);
        }

        String state = IamSecureTokens.randomToken();
        String transactionId = IamSecureTokens.randomToken();
        IamTicketService.PreparedAuthorization authorization =
            ticketService.prepareAuthorization(state, bridgeRequest.returnPath());
        LoginUser loginUser = loginService.buildLoginUser(user);
        IamApplicationPreparation preparation = sessionRegistry.prepareApplication(
            loginUser,
            bridgeRequest.targetApplicationId(),
            targetApplication,
            targetFrontendId,
            IamProtocolConstants.TERMINAL_WEB,
            transactionId,
            bridgeRequest.rootSid()
        );
        IamAuthorizationResponse issued = ticketService.issue(
            loginUser,
            bridgeRequest.targetApplicationId(),
            targetFrontendId,
            authorization,
            bridgeRequest.rootSid(),
            preparation
        );
        return R.ok(new IamBridgeTicketResponse(
            issued.ticket(),
            authorization.state(),
            bridgeRequest.targetApplicationId(),
            targetFrontendId,
            IamProtocolConstants.TERMINAL_WEB,
            authorization.returnPath(),
            issued.expiresAt()
        ));
    }

    private IamProperties.Application requireSourceApplication(String applicationId) {
        IamProperties.Application application = properties.getApplications().get(applicationId);
        if (application == null || !application.isEnabled() || !"IWS".equals(application.getType())) {
            throw new ServiceException("嵌入应用桥接来源未登记或不是 IWS", 403);
        }
        return application;
    }

    private IamProperties.Application requireTargetApplication(String applicationId) {
        IamProperties.Application application = properties.getApplications().get(applicationId);
        if (application == null || !application.isEnabled()
            || !BRIDGEABLE_TYPES.contains(application.getType())
            || !application.getTerminals().contains(IamProtocolConstants.TERMINAL_WEB)) {
            throw new ServiceException("目标应用未开放嵌入桥接", 403);
        }
        return application;
    }

    private String requireTargetWebFrontend(IamProperties.Application application) {
        List<String> candidates = application.getAllowedFrontendIds().stream()
            .filter(frontendId -> {
                IamProperties.Frontend frontend = properties.getFrontends().get(frontendId);
                return frontend != null
                    && frontend.isEnabled()
                    && IamProtocolConstants.TERMINAL_WEB.equals(frontend.getTerminal());
            })
            .distinct()
            .toList();
        if (candidates.size() != 1) {
            throw new ServiceException("目标应用必须且只能登记一个 WEB 桥接前端", 403);
        }
        return candidates.get(0);
    }

    private boolean hasPermission(Set<String> permissions, String required) {
        return required == null || required.isBlank()
            || permissions.contains("*:*:*")
            || permissions.contains(required);
    }
}
