/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 统一身份应用导航、一次性票据与下游会话接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.exception.NotPermissionException;
import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.auth.service.IamServiceRequestVerifier;
import com.innolux.iws.auth.service.IamApplicationPreparation;
import com.innolux.iws.auth.service.IamSessionRegistry;
import com.innolux.iws.auth.service.IamTicketService;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamAuthorizationResponse;
import com.innolux.iws.common.auth.domain.IamPortalApplication;
import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.domain.IamTicketExchangeRequest;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.domain.IamTicketRecord;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一身份中心 SSO 接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/iam")
public class IamSsoController {

    private static final Pattern TRANSACTION_PATTERN =
        Pattern.compile("[A-Za-z0-9._~-]{16,128}");
    private static final Pattern COMMIT_ID_PATTERN =
        Pattern.compile("[A-Za-z0-9._~-]{16,128}");

    private final IamProperties properties;
    private final IamSessionRegistry sessionRegistry;
    private final IamTicketService ticketService;
    private final IamServiceRequestVerifier requestVerifier;
    private final PermissionService permissionService;
    private final ISysUserService userService;

    /**
     * 登录前返回已启用且支持当前终端的最小系统选项。
     *
     * @param terminal WEB 或 PDA
     * @return 系统标识、名称、类型与受控入口
     */
    @SaIgnore
    @GetMapping("/login/apps")
    public R<List<LoginApplicationOption>> loginApplications(
        @RequestParam String frontendId,
        @RequestParam String terminal
    ) {
        IamProperties.Frontend frontend = requireFrontend(frontendId, terminal);
        List<LoginApplicationOption> applications = properties.getApplications().entrySet().stream()
            .filter(entry -> entry.getValue().isEnabled())
            .filter(entry -> entry.getValue().getAllowedFrontendIds().contains(frontendId))
            .filter(entry -> entry.getValue().getTerminals().contains(frontend.getTerminal()))
            .map(entry -> new LoginApplicationOption(
                entry.getKey(),
                frontendId,
                entry.getValue().getName(),
                entry.getValue().getType(),
                frontend.getLaunchUri(),
                entry.getValue().getApiBaseUrl()
            ))
            .toList();
        return R.ok(applications);
    }

    /**
     * 返回当前用户可见应用及接入授权状态。
     *
     * @return 应用导航
     */
    @GetMapping("/portal/apps")
    public R<List<IamPortalApplication>> applications() {
        Set<String> permissions = currentPermissions();
        List<IamPortalApplication> applications = properties.getApplications().entrySet().stream()
            .filter(entry -> entry.getValue().isEnabled())
            .filter(entry -> entry.getValue().getAllowedFrontendIds().stream()
                .map(properties.getFrontends()::get)
                .anyMatch(frontend -> frontend != null && frontend.isEnabled()))
            .map(entry -> new IamPortalApplication(
                entry.getKey(),
                entry.getValue().getType(),
                entry.getValue().getName(),
                entry.getValue().getDescription(),
                entry.getValue().getAllowedFrontendIds().stream()
                    .map(properties.getFrontends()::get)
                    .filter(frontend -> frontend != null && frontend.isEnabled())
                    .map(frontend -> new IamPortalApplication.FrontendLaunch(
                        findFrontendId(frontend),
                        frontend.getTerminal(),
                        frontend.getLaunchUri()
                    ))
                    .toList(),
                entry.getValue().getAccessPermission(),
                hasPermission(permissions, entry.getValue().getAccessPermission())
            ))
            .toList();
        return R.ok(applications);
    }

    /**
     * 校验接入授权并签发目标应用一次性票据。
     *
     * @param request 应用跳转请求
     * @return 一次性票据与精确回调地址
     */
    @PostMapping("/authorize")
    public R<IamAuthorizationResponse> authorize(@RequestBody AuthorizeRequest request) {
        IamProperties.Application application = properties.getApplications().get(request.applicationId());
        if (application == null || !application.isEnabled()) {
            throw new NotPermissionException("未登记的目标应用");
        }
        IamProperties.Frontend frontend = requireFrontend(request.frontendId(), request.terminal());
        if (!application.getAllowedFrontendIds().contains(request.frontendId())) {
            throw new NotPermissionException("当前前端未绑定目标应用");
        }
        SysUserVo user = requireActiveUser();
        if (!hasPermission(currentPermissions(), application.getAccessPermission())) {
            throw new NotPermissionException("未获得目标应用接入权限");
        }
        String terminal = frontend.getTerminal();
        if (!application.getTerminals().contains(terminal)) {
            throw new NotPermissionException("目标应用未开放该终端");
        }
        if (request.transactionId() == null || !TRANSACTION_PATTERN.matcher(request.transactionId()).matches()) {
            throw new ServiceException("登录事务标识格式不合法");
        }
        IamTicketService.PreparedAuthorization authorization =
            ticketService.prepareAuthorization(request.state(), request.returnPath());
        String rootSid = sessionRegistry.currentRootSid();
        IamSessionStatusResponse status = sessionRegistry.status(rootSid);
        if (!status.active() || !user.getUserId().equals(status.userId())) {
            throw new NotPermissionException("统一身份会话已失效");
        }
        LoginUser loginUser = LoginHelper.getLoginUser();
        IamApplicationPreparation preparation = sessionRegistry.prepareApplication(
            loginUser,
            request.applicationId(),
            application,
            request.frontendId(),
            terminal,
            request.transactionId(),
            rootSid
        );
        return R.ok(ticketService.issue(
            loginUser,
            request.applicationId(),
            request.frontendId(),
            authorization,
            rootSid,
            preparation
        ));
    }

    /**
     * 目标应用本地准备前只读核验票据，不消费票据、不替换当前应用占位。
     *
     * @param applicationId 目标应用组标识
     * @param instanceId    目标应用节点标识
     * @param timestamp     请求时间戳
     * @param nonce         请求随机值
     * @param signature     请求签名
     * @param body          原始 JSON 请求体
     * @param request       HTTP 请求
     * @return 待提交身份与申请
     */
    @SaIgnore
    @PostMapping("/tickets/inspect")
    public R<IamTicketExchangeResponse> inspect(
        @RequestHeader(IamProtocolConstants.APPLICATION_HEADER) String applicationId,
        @RequestHeader(IamProtocolConstants.INSTANCE_HEADER) String instanceId,
        @RequestHeader(IamProtocolConstants.TIMESTAMP_HEADER) String timestamp,
        @RequestHeader(IamProtocolConstants.NONCE_HEADER) String nonce,
        @RequestHeader(IamProtocolConstants.SIGNATURE_HEADER) String signature,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        requestVerifier.verify(applicationId, instanceId, timestamp, nonce, signature,
            request.getMethod(), request.getRequestURI(), body);
        IamTicketExchangeRequest exchangeRequest = requireExchangeRequest(body, applicationId);
        IamTicketRecord record = ticketService.inspect(
            exchangeRequest.ticket(),
            applicationId,
            exchangeRequest.frontendId(),
            exchangeRequest.state()
        );
        return R.ok(inspectIdentity(record, instanceId));
    }

    /**
     * 目标应用服务端核销一次性票据。
     *
     * @param applicationId 目标应用组标识
     * @param instanceId    目标应用节点标识
     * @param timestamp 请求时间戳
     * @param nonce     请求随机值
     * @param signature 请求签名
     * @param body      原始 JSON 请求体
     * @param request   HTTP 请求
     * @return 原系统身份与根会话
     */
    @SaIgnore
    @PostMapping("/tickets/exchange")
    public R<IamTicketExchangeResponse> exchange(
        @RequestHeader(IamProtocolConstants.APPLICATION_HEADER) String applicationId,
        @RequestHeader(IamProtocolConstants.INSTANCE_HEADER) String instanceId,
        @RequestHeader(IamProtocolConstants.TIMESTAMP_HEADER) String timestamp,
        @RequestHeader(IamProtocolConstants.NONCE_HEADER) String nonce,
        @RequestHeader(IamProtocolConstants.SIGNATURE_HEADER) String signature,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        requestVerifier.verify(applicationId, instanceId, timestamp, nonce, signature, request.getMethod(),
            request.getRequestURI(), body);
        IamTicketExchangeRequest exchangeRequest = requireExchangeRequest(body, applicationId);
        if (exchangeRequest.commitId() == null
            || !COMMIT_ID_PATTERN.matcher(exchangeRequest.commitId()).matches()) {
            throw new ServiceException("票据提交标识格式不合法");
        }
        IamTicketExchangeResponse committed = ticketService.committed(
            exchangeRequest.ticket(),
            applicationId,
            exchangeRequest.frontendId(),
            exchangeRequest.state(),
            exchangeRequest.commitId()
        );
        if (committed != null) {
            if (!instanceId.equals(committed.instanceId())) {
                throw new ServiceException("票据提交结果与当前实例不匹配");
            }
            requireActiveCommittedSession(committed);
            return R.ok(committed);
        }
        IamTicketExchangeResponse identity = inspectIdentity(
            ticketService.inspect(
                exchangeRequest.ticket(),
                applicationId,
                exchangeRequest.frontendId(),
                exchangeRequest.state()
            ),
            instanceId
        );
        IamTicketRecord record = ticketService.inspect(
            exchangeRequest.ticket(),
            applicationId,
            exchangeRequest.frontendId(),
            exchangeRequest.state()
        );
        sessionRegistry.commitApplication(
            preparation(record),
            record.rootSid(),
            exchangeRequest.ticket(),
            ticketService.rawPayload(exchangeRequest.ticket()),
            exchangeRequest.state(),
            exchangeRequest.commitId(),
            identity
        );
        return R.ok(identity);
    }

    /**
     * 查询根会话是否仍有效，供客户端短租约校验。
     *
     * @param body 根会话查询请求
     * @return 根会话状态
     */
    @SaIgnore
    @PostMapping("/session/status")
    public R<IamSessionStatusResponse> sessionStatus(
        @RequestHeader(IamProtocolConstants.APPLICATION_HEADER) String applicationId,
        @RequestHeader(IamProtocolConstants.INSTANCE_HEADER) String instanceId,
        @RequestHeader(IamProtocolConstants.TIMESTAMP_HEADER) String timestamp,
        @RequestHeader(IamProtocolConstants.NONCE_HEADER) String nonce,
        @RequestHeader(IamProtocolConstants.SIGNATURE_HEADER) String signature,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        requestVerifier.verify(applicationId, instanceId, timestamp, nonce, signature, request.getMethod(),
            request.getRequestURI(), body);
        IamSessionStatusRequest statusRequest = JsonUtils.parseObject(body, IamSessionStatusRequest.class);
        if (statusRequest == null || !applicationId.equals(statusRequest.applicationId())
            || statusRequest.frontendId() == null || statusRequest.frontendId().isBlank()) {
            throw new ServiceException("应用会话查询请求不匹配");
        }
        IamProperties.Application application = requireApplicationForFrontend(
            statusRequest.applicationId(), statusRequest.frontendId(), statusRequest.terminal()
        );
        IamSessionStatusResponse status = sessionRegistry.status(
            statusRequest.rootSid(),
            statusRequest.applicationId(),
            statusRequest.frontendId(),
            statusRequest.sessionId(),
            statusRequest.sessionGeneration(),
            statusRequest.terminal()
        );
        if (!status.active()) {
            return R.ok(status);
        }
        SysUserVo user = userService.selectUserById(status.userId());
        if (user == null || SystemConstants.DISABLE.equals(user.getStatus())) {
            return R.ok(new IamSessionStatusResponse(
                false, IamProtocolConstants.ISSUER, null, null, 0, "USER_DISABLED_OR_MISSING"
            ));
        }
        if (!hasPermission(permissionService.getMenuPermission(user.getUserId()), application.getAccessPermission())) {
            return R.ok(new IamSessionStatusResponse(
                false, IamProtocolConstants.ISSUER, null, user.getUserName(), 0,
                "APPLICATION_ACCESS_REVOKED"
            ));
        }
        return R.ok(status);
    }

    /**
     * 撤销指定根会话，目标应用退出时调用。
     *
     * @param applicationId 目标应用组标识
     * @param instanceId    目标应用节点标识
     * @param timestamp 请求时间戳
     * @param nonce     请求随机值
     * @param signature 请求签名
     * @param body      原始 JSON 请求体
     * @param request   HTTP 请求
     * @return 操作结果
     */
    @SaIgnore
    @PostMapping("/session/revoke")
    public R<Boolean> revokeSession(
        @RequestHeader(IamProtocolConstants.APPLICATION_HEADER) String applicationId,
        @RequestHeader(IamProtocolConstants.INSTANCE_HEADER) String instanceId,
        @RequestHeader(IamProtocolConstants.TIMESTAMP_HEADER) String timestamp,
        @RequestHeader(IamProtocolConstants.NONCE_HEADER) String nonce,
        @RequestHeader(IamProtocolConstants.SIGNATURE_HEADER) String signature,
        @RequestBody String body,
        HttpServletRequest request
    ) {
        requestVerifier.verify(applicationId, instanceId, timestamp, nonce, signature, request.getMethod(),
            request.getRequestURI(), body);
        JsonNode revokeRequest = JsonUtils.getJsonMapper().readTree(body);
        String rootSid = revokeRequest.path("rootSid").asString(null);
        if (rootSid == null || rootSid.isBlank()) {
            throw new ServiceException("根会话标识不能为空");
        }
        String requestApplicationId = revokeRequest.path("applicationId").asString(null);
        String requestFrontendId = revokeRequest.path("frontendId").asString(null);
        String requestSessionId = revokeRequest.path("sessionId").asString(null);
        String requestTerminal = revokeRequest.path("terminal").asString(null);
        if (!applicationId.equals(requestApplicationId)
            || requestFrontendId == null
            || requestFrontendId.isBlank()
            || requestSessionId == null
            || requestSessionId.isBlank()
            || requestTerminal == null
            || requestTerminal.isBlank()
            || revokeRequest.path("sessionGeneration").asLong(0) <= 0) {
            throw new ServiceException("统一退出必须携带当前应用会话上下文");
        }
        requireApplicationForFrontend(
            requestApplicationId, requestFrontendId, requestTerminal
        );
        return R.ok(sessionRegistry.revokeRootIfCurrent(
            rootSid,
            requestApplicationId,
            requestFrontendId,
            requestSessionId,
            revokeRequest.path("sessionGeneration").asLong(0),
            requestTerminal
        ));
    }

    private IamTicketExchangeRequest requireExchangeRequest(
        String body,
        String applicationId
    ) {
        IamTicketExchangeRequest exchangeRequest = JsonUtils.parseObject(
            body,
            IamTicketExchangeRequest.class
        );
        if (exchangeRequest == null || !applicationId.equals(exchangeRequest.applicationId())) {
            throw new ServiceException("票据核销请求不匹配");
        }
        return exchangeRequest;
    }

    private IamTicketExchangeResponse inspectIdentity(
        IamTicketRecord record,
        String instanceId
    ) {
        IamProperties.Application application = requireApplicationForFrontend(
            record.applicationId(), record.frontendId(), record.terminal()
        );
        SysUserVo user = userService.selectUserById(record.userId());
        if (user == null || SystemConstants.DISABLE.equals(user.getStatus())) {
            throw new NotPermissionException("统一身份账号已停用或不存在");
        }
        if (!hasPermission(
            permissionService.getMenuPermission(record.userId()),
            application.getAccessPermission()
        )) {
            throw new NotPermissionException("目标应用接入权限已撤销");
        }
        IamSessionStatusResponse status = sessionRegistry.status(record.rootSid());
        if (!status.active() || !record.userId().equals(status.userId())) {
            throw new NotPermissionException("统一身份会话已失效");
        }
        return new IamTicketExchangeResponse(
            IamProtocolConstants.ISSUER,
            record.applicationId(),
            record.applicationType(),
            record.frontendId(),
            record.userId(),
            record.username(),
            record.rootSid(),
            record.sessionId(),
            record.sessionGeneration(),
            record.terminal(),
            instanceId,
            record.returnPath(),
            status.expiresAt()
        );
    }

    private IamApplicationPreparation preparation(IamTicketRecord record) {
        return new IamApplicationPreparation(
            record.issuer(),
            record.applicationId(),
            record.applicationType(),
            record.frontendId(),
            record.userId(),
            record.username(),
            record.rootSid(),
            record.sessionId(),
            record.sessionGeneration(),
            record.terminal(),
            record.transactionId(),
            record.expiresAt(),
            record.expiresAt()
        );
    }

    private void requireActiveCommittedSession(IamTicketExchangeResponse identity) {
        IamSessionStatusResponse status = sessionRegistry.status(
            identity.rootSid(),
            identity.applicationId(),
            identity.frontendId(),
            identity.sessionId(),
            identity.sessionGeneration(),
            identity.terminal()
        );
        if (!status.active() || !identity.userId().equals(status.userId())) {
            throw new NotPermissionException("统一身份应用会话已失效或被替换");
        }
    }

    /**
     * 读取当前有效账号，停用账号立即拒绝。
     *
     * @return 当前账号
     */
    private SysUserVo requireActiveUser() {
        SysUserVo user = userService.selectUserById(LoginHelper.getUserId());
        if (user == null || SystemConstants.DISABLE.equals(user.getStatus())) {
            throw new NotPermissionException("统一身份账号已停用或不存在");
        }
        return user;
    }

    /**
     * 读取当前账号原菜单权限。
     *
     * @return 菜单权限
     */
    private Set<String> currentPermissions() {
        return permissionService.getMenuPermission(LoginHelper.getUserId());
    }

    /**
     * 判断原菜单权限是否满足应用接入要求。
     *
     * @param permissions 当前权限
     * @param required    接入权限
     * @return 是否允许接入
     */
    private boolean hasPermission(Set<String> permissions, String required) {
        return required == null || required.isBlank()
            || permissions.contains("*:*:*")
            || permissions.contains(required);
    }

    /**
     * 校验受信前端登记、启停与终端。
     *
     * @param frontendId 前端标识
     * @param terminal 请求终端
     * @return 前端配置
     */
    private IamProperties.Frontend requireFrontend(String frontendId, String terminal) {
        IamProperties.Frontend frontend = properties.getFrontends().get(frontendId);
        if (frontend == null || !frontend.isEnabled()) {
            throw new NotPermissionException("受信前端未登记或已停用");
        }
        String normalized = terminal == null ? "" : terminal.toUpperCase(java.util.Locale.ROOT);
        if (!frontend.getTerminal().equals(normalized)) {
            throw new NotPermissionException("前端终端不匹配");
        }
        return frontend;
    }

    /**
     * 校验目标应用、受信前端、终端和绑定关系。
     *
     * @param applicationId 目标应用
     * @param frontendId    受信前端
     * @param terminal      终端
     * @return 已校验应用配置
     */
    private IamProperties.Application requireApplicationForFrontend(
        String applicationId,
        String frontendId,
        String terminal
    ) {
        IamProperties.Application application = properties.getApplications().get(applicationId);
        if (application == null || !application.isEnabled()) {
            throw new NotPermissionException("目标应用未登记或已停用");
        }
        IamProperties.Frontend frontend = requireFrontend(frontendId, terminal);
        if (!application.getAllowedFrontendIds().contains(frontendId)) {
            throw new NotPermissionException("当前前端未绑定目标应用");
        }
        if (!application.getTerminals().contains(frontend.getTerminal())) {
            throw new NotPermissionException("目标应用未开放该终端");
        }
        return application;
    }

    /**
     * 从配置值反查稳定的前端标识。
     *
     * @param target 目标前端
     * @return 前端标识
     */
    private String findFrontendId(IamProperties.Frontend target) {
        return properties.getFrontends().entrySet().stream()
            .filter(entry -> entry.getValue() == target)
            .map(java.util.Map.Entry::getKey)
            .findFirst()
            .orElseThrow();
    }

    /**
     * 应用跳转参数。
     *
     * @param applicationId 目标应用标识
     * @param state      客户端随机状态
     * @param returnPath 应用内返回路径
     * @param terminal 终端类型
     * @param transactionId 幂等登录事务标识
     * @param frontendId 发起登录的受信前端
     */
    public record AuthorizeRequest(
        String applicationId,
        String frontendId,
        String state,
        String returnPath,
        String terminal,
        String transactionId
    ) {
    }

    /**
     * 登录前系统选项。
     *
     * @param applicationId 应用标识
     * @param frontendId 受信前端标识
     * @param name          展示名称
     * @param applicationType 应用类型
     * @param launchUri     浏览器可访问入口
     * @param apiBaseUrl    公共后端 API 地址或代理前缀
     */
    public record LoginApplicationOption(
        String applicationId,
        String frontendId,
        String name,
        String applicationType,
        String launchUri,
        String apiBaseUrl
    ) {
    }
}
