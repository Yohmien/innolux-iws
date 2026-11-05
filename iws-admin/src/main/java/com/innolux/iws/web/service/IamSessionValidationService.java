/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 对指定本地 Token 复用统一身份根会话和原权限复核；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.utils.IamCredentialFingerprint;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.service.PermissionService;
import com.innolux.iws.common.mybatis.helper.DataPermissionHelper;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysUserService;
import com.innolux.iws.web.config.IamClientProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * 统一身份 Token 校验服务。
 */
@Service
@RequiredArgsConstructor
public class IamSessionValidationService {

    private final IamCenterClient centerClient;
    private final IamClientProperties properties;
    private final ISysUserService userService;
    private final PermissionService permissionService;
    private final Clock clock;

    /**
     * 校验指定 Token 的中心会话、账号凭据和当前应用接入权限。
     *
     * @param token      本地登录 Token
     * @param allowLease 是否允许中心不可达时使用既有短租约
     * @return 校验结果
     */
    public ValidationResult validate(String token, boolean allowLease) {
        if (token == null || token.isBlank()) {
            return ValidationResult.invalid("统一身份根会话不存在");
        }
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            return ValidationResult.invalid("统一身份会话已失效，请重新登录");
        }
        LoginUser loginUser = LoginHelper.getLoginUser(token);
        if (loginUser == null || loginUser.getUserId() == null) {
            return ValidationResult.invalid("统一身份会话主体不存在");
        }
        SaSession session = StpUtil.getTokenSessionByToken(token);
        if (session == null) {
            return ValidationResult.invalid("统一身份会话已失效，请重新登录");
        }
        Object rootSid = session.get(IamProtocolConstants.ROOT_SID_SESSION_KEY);
        if (rootSid == null || rootSid.toString().isBlank()) {
            return ValidationResult.invalid("统一身份根会话不存在");
        }
        Object frontendId = session.get(IamProtocolConstants.FRONTEND_ID_SESSION_KEY);
        Object applicationId = session.get(IamProtocolConstants.APPLICATION_ID_SESSION_KEY);
        Object applicationType = session.get(IamProtocolConstants.APPLICATION_TYPE_SESSION_KEY);
        Object sessionId = session.get(IamProtocolConstants.APPLICATION_SESSION_ID_KEY);
        Object generation = session.get(IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY);
        Object terminal = session.get(IamProtocolConstants.TERMINAL_SESSION_KEY);
        Object sessionNamespace = session.get("iamSessionNamespace");
        if (!properties.getApplicationId().equals(applicationId)
            || !properties.getApplicationType().equals(applicationType)
            || frontendId == null
            || sessionId == null
            || !(generation instanceof Number)
            || terminal == null
            || !properties.getSessionNamespace().equals(sessionNamespace)) {
            return ValidationResult.invalid("统一身份应用会话上下文不完整");
        }

        long now = Instant.now(clock).toEpochMilli();
        long validatedAt = readLong(session, "iamRootValidatedAt");
        boolean centerVerified = false;
        try {
            IamSessionStatusResponse status = centerClient.status(
                rootSid.toString(),
                frontendId.toString(),
                sessionId.toString(),
                ((Number) generation).longValue(),
                terminal.toString()
            );
            if (!status.active()) {
                return ValidationResult.invalid(
                    "APPLICATION_SESSION_REPLACED".equals(status.reason())
                        ? IamProtocolConstants.SESSION_REPLACED_PREFIX + "该账号已在另一同类型系统登录"
                        : "统一身份会话已失效，请重新登录"
                );
            }
            centerVerified = true;
        } catch (ServiceException error) {
            long leaseMs = properties.getRootValidationLeaseSeconds() * 1000;
            if (!allowLease || validatedAt <= 0 || now - validatedAt > leaseMs) {
                return ValidationResult.centerUnavailable(
                    error.getMessage() == null ? "统一身份中心不可达" : error.getMessage()
                );
            }
        }

        ValidationResult authorization = validateLocalAuthorization(loginUser.getUserId(), session);
        if (!authorization.valid()) {
            return authorization;
        }
        if (centerVerified) {
            session.set("iamRootValidatedAt", now);
        }
        return ValidationResult.success();
    }

    /**
     * 校验当前本地账号、密码哈希指纹和原菜单接入权限。
     *
     * @param userId  本地登录账号主键
     * @param session Token 会话
     * @return 本地授权校验结果
     */
    private ValidationResult validateLocalAuthorization(Long userId, SaSession session) {
        SysUserVo user = DataPermissionHelper.ignore(() -> userService.selectUserById(userId));
        if (user == null || SystemConstants.DISABLE.equals(user.getStatus())) {
            return ValidationResult.invalid("统一身份账号已停用或不存在");
        }
        Object expectedFingerprint = session.get(IamProtocolConstants.CREDENTIAL_FINGERPRINT_KEY);
        if (!IamCredentialFingerprint.matches(
            user.getPassword(), expectedFingerprint == null ? null : expectedFingerprint.toString()
        )) {
            return ValidationResult.invalid("统一身份账号凭据已变更，请重新登录");
        }
        Set<String> permissions = permissionService.getMenuPermission(userId);
        String required = properties.getAccessPermission();
        if (required != null && !required.isBlank()
            && !permissions.contains("*:*:*")
            && !permissions.contains(required)) {
            return ValidationResult.invalid("当前应用接入权限已撤销");
        }
        return ValidationResult.success();
    }

    /**
     * 读取会话中的长整型值。
     *
     * @param session Token 会话
     * @param key     键
     * @return 长整型值；不存在时返回 0
     */
    private long readLong(SaSession session, String key) {
        Object value = session.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * Token 校验结果。
     *
     * @param status  状态
     * @param message 失败说明
     */
    public record ValidationResult(Status status, String message) {

        public static ValidationResult success() {
            return new ValidationResult(Status.VALID, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(Status.INVALID, message);
        }

        public static ValidationResult centerUnavailable(String message) {
            return new ValidationResult(Status.CENTER_UNAVAILABLE, message);
        }

        public boolean valid() {
            return status == Status.VALID;
        }

        /**
         * 校验状态。
         */
        public enum Status {
            VALID,
            INVALID,
            CENTER_UNAVAILABLE
        }
    }
}
