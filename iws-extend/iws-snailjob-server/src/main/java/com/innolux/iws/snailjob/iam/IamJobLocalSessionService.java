/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 将中心权威身份显式映射到既有 Job 本地用户并签发原生兼容 Token；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.common.core.util.JsonUtil;
import com.aizuda.snailjob.server.web.model.response.SystemUserResponseVO;
import com.aizuda.snailjob.server.web.service.SystemUserService;
import com.aizuda.snailjob.template.datasource.persistence.mapper.SystemUserMapper;
import com.aizuda.snailjob.template.datasource.persistence.po.SystemUser;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.Objects;

/**
 * Job 本地身份适配与会话签发。
 */
@Service
public class IamJobLocalSessionService {

    private static final long MAX_LOCAL_TOKEN_MILLIS = 60 * 60 * 1000L;

    private final IamJobProperties properties;
    private final SystemUserMapper systemUserMapper;
    private final SystemUserService systemUserService;

    /**
     * 创建本地会话服务。
     *
     * @param properties 统一身份配置
     * @param systemUserMapper 原生用户 Mapper
     * @param systemUserService 原生用户服务
     */
    public IamJobLocalSessionService(
        IamJobProperties properties,
        SystemUserMapper systemUserMapper,
        SystemUserService systemUserService
    ) {
        this.properties = properties;
        this.systemUserMapper = systemUserMapper;
        this.systemUserService = systemUserService;
    }

    /**
     * 按显式映射查找既有 Job 用户，不按同名自动合并。
     *
     * @param centerUsername 中心原账号名
     * @return 既有 Job 本地用户
     */
    public SystemUser requireMappedUser(String centerUsername) {
        String localUsername = properties.getAccounts().get(centerUsername);
        if (!StringUtils.hasText(localUsername)) {
            throw new IllegalStateException("统一身份账号未配置 Job 本地用户映射");
        }
        SystemUser user = systemUserMapper.selectOne(
            new LambdaQueryWrapper<SystemUser>().eq(SystemUser::getUsername, localUsername)
        );
        if (user == null) {
            throw new IllegalStateException("Job 本地映射用户不存在");
        }
        return user;
    }

    /**
     * 使用当前配置映射复核 Token 对应的本地用户仍为中心当前账号的绑定对象。
     *
     * @param centerUsername 中心当前账号名
     * @param localUserId    Token 验签后的本地用户主键
     * @return 当前映射仍指向同一本地用户
     */
    public boolean matchesMappedUser(String centerUsername, Long localUserId) {
        if (!StringUtils.hasText(centerUsername) || localUserId == null) {
            return false;
        }
        try {
            return localUserId.equals(requireMappedUser(centerUsername).getId());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 使用既有 Job 用户、角色和 namespace 信息签发原生兼容 Token。
     *
     * @param identity 中心权威身份
     * @return 本地 Token 和返回路径
     */
    public IssuedSession issue(IamTicketExchangeResponse identity) {
        return issue(identity, prepare(identity));
    }

    /**
     * 在中心提交应用占位前准备本地用户、角色和 namespace。
     *
     * @param identity 待提交中心身份
     * @return 本地准备结果
     */
    public PreparedSession prepare(IamTicketExchangeResponse identity) {
        SystemUser user = requireMappedUser(identity.username());
        SystemUserResponseVO response = systemUserService.getSystemUserByUserName(user.getUsername());
        if (response.getNamespaceIds() == null || response.getNamespaceIds().isEmpty()) {
            throw new IllegalStateException("Job 本地用户没有可用命名空间");
        }
        String namespaceId = response.getNamespaceIds().get(0).getUniqueId();
        if (!StringUtils.hasText(namespaceId)) {
            throw new IllegalStateException("Job 本地用户命名空间标识为空");
        }
        return new PreparedSession(user, response, namespaceId);
    }

    /**
     * 使用已准备用户签发原生兼容 Token。
     *
     * @param identity 中心已提交身份
     * @param prepared 本地准备结果
     * @return 本地 Token 和返回路径
     */
    public IssuedSession issue(
        IamTicketExchangeResponse identity,
        PreparedSession prepared
    ) {
        SystemUser user = prepared.user();
        SystemUserResponseVO response = prepared.response();
        String namespaceId = prepared.namespaceId();
        long now = System.currentTimeMillis();
        long expiresAt = Math.min(now + MAX_LOCAL_TOKEN_MILLIS, identity.expiresAt());
        String token = JWT.create()
            .withExpiresAt(new Date(expiresAt))
            .withAudience(JsonUtil.toJsonString(response))
            .withClaim(IamProtocolConstants.SUBJECT_USER_ID_CLAIM, identity.userId())
            .withClaim(IamProtocolConstants.ROOT_SID_SESSION_KEY, identity.rootSid())
            .withClaim(IamProtocolConstants.APPLICATION_ID_SESSION_KEY, identity.applicationId())
            .withClaim(IamProtocolConstants.FRONTEND_ID_SESSION_KEY, identity.frontendId())
            .withClaim(IamProtocolConstants.APPLICATION_SESSION_ID_KEY, identity.sessionId())
            .withClaim(
                IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY,
                identity.sessionGeneration()
            )
            .withClaim(IamProtocolConstants.TERMINAL_SESSION_KEY, identity.terminal())
            .withClaim(IamProtocolConstants.INSTANCE_ID_SESSION_KEY, identity.instanceId())
            .sign(Algorithm.HMAC256(user.getPassword()));
        return new IssuedSession(token, identity.returnPath(), namespaceId);
    }

    /**
     * 验证 Job 本地签名并读取中心会话声明。
     *
     * @param token Job 原生认证头中的 Token
     * @return 已验签的中心会话声明
     */
    public IamTokenClaims verify(String token) {
        DecodedJWT decoded = JWT.decode(token);
        Long localUserId = JsonUtil.parseObject(
            decoded.getAudience().get(0),
            SystemUserResponseVO.class
        ).getId();
        SystemUser user = systemUserMapper.selectById(localUserId);
        if (user == null) {
            throw new IllegalStateException("Job 本地用户不存在");
        }
        JWTVerifier verifier = JWT.require(Algorithm.HMAC256(user.getPassword())).build();
        verifier.verify(token);
        String rootSid = decoded.getClaim(IamProtocolConstants.ROOT_SID_SESSION_KEY).asString();
        Long subjectUserId = decoded.getClaim(IamProtocolConstants.SUBJECT_USER_ID_CLAIM).asLong();
        String frontendId = decoded.getClaim(IamProtocolConstants.FRONTEND_ID_SESSION_KEY).asString();
        String sessionId = decoded.getClaim(IamProtocolConstants.APPLICATION_SESSION_ID_KEY).asString();
        Long generation = decoded.getClaim(
            IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY
        ).asLong();
        String terminal = decoded.getClaim(IamProtocolConstants.TERMINAL_SESSION_KEY).asString();
        if (!StringUtils.hasText(rootSid) || Objects.isNull(subjectUserId)
            || !StringUtils.hasText(frontendId) || !StringUtils.hasText(sessionId)
            || Objects.isNull(generation) || !StringUtils.hasText(terminal)) {
            throw new IllegalStateException("Job Token 缺少统一身份会话声明");
        }
        return new IamTokenClaims(
            user.getId(),
            subjectUserId,
            rootSid,
            frontendId,
            sessionId,
            generation,
            terminal
        );
    }

    /**
     * 签发给 Job 前端的本地会话。
     *
     * @param accessToken 原生兼容 Token
     * @param returnPath 受控返回路径
     * @param namespaceId 默认原生命名空间
     */
    public record IssuedSession(String accessToken, String returnPath, String namespaceId) {
    }

    /**
     * 未提交中心占位前的本地身份准备。
     *
     * @param user 本地用户
     * @param response Job 原生用户信息
     * @param namespaceId 默认命名空间
     */
    public record PreparedSession(
        SystemUser user,
        SystemUserResponseVO response,
        String namespaceId
    ) {
    }

    /**
     * Job Token 中携带的中心会话声明。
     *
     * @param localUserId Job 本地用户主键
     * @param subjectUserId 中心原 user_id
     * @param rootSid 根会话标识
     * @param frontendId 受信前端标识
     * @param sessionId 应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal 终端类型
     */
    public record IamTokenClaims(
        Long localUserId,
        Long subjectUserId,
        String rootSid,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
    }
}
