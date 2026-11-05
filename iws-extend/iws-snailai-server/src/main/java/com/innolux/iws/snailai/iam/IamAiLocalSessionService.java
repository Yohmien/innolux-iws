/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 将中心权威身份显式映射到既有 AI 本地用户并签发原生兼容 Token；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import com.aizuda.snail.ai.admin.dto.AudienceDTO;
import com.aizuda.snail.ai.common.util.JsonUtil;
import com.aizuda.snail.ai.persistence.admin.mapper.UserMapper;
import com.aizuda.snail.ai.persistence.admin.po.UserPO;
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
import java.util.concurrent.TimeUnit;

/**
 * AI 本地身份适配与会话签发。
 */
@Service
public class IamAiLocalSessionService {

    private static final long MAX_LOCAL_TOKEN_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final IamAiProperties properties;
    private final UserMapper userMapper;

    /**
     * 创建 AI 本地会话服务。
     *
     * @param properties 统一身份配置
     * @param userMapper 原生用户 Mapper
     */
    public IamAiLocalSessionService(IamAiProperties properties, UserMapper userMapper) {
        this.properties = properties;
        this.userMapper = userMapper;
    }

    /**
     * 按显式映射查找既有 AI 用户，不按同名自动合并。
     *
     * @param centerUsername 中心原账号名
     * @return 既有 AI 本地用户
     */
    public UserPO requireMappedUser(String centerUsername) {
        String localUsername = properties.getAccounts().get(centerUsername);
        if (!StringUtils.hasText(localUsername)) {
            throw new IllegalStateException("统一身份账号未配置 AI 本地用户映射");
        }
        UserPO user = userMapper.selectOne(
            new LambdaQueryWrapper<UserPO>().eq(UserPO::getUsername, localUsername)
        );
        if (user == null) {
            throw new IllegalStateException("AI 本地映射用户不存在");
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
     * 使用既有 AI 用户和角色签发原生兼容 Token。
     *
     * @param identity 中心权威身份
     * @return 本地 Token 和返回路径
     */
    public IssuedSession issue(IamTicketExchangeResponse identity) {
        return issue(identity, prepare(identity));
    }

    /**
     * 在中心提交应用占位前准备本地用户和资源上下文。
     *
     * @param identity 待提交中心身份
     * @return 本地用户
     */
    public UserPO prepare(IamTicketExchangeResponse identity) {
        return requireMappedUser(identity.username());
    }

    /**
     * 使用已准备用户签发 AI 原生兼容 Token。
     *
     * @param identity 中心已提交身份
     * @param user 本地准备用户
     * @return 本地 Token 和返回路径
     */
    public IssuedSession issue(IamTicketExchangeResponse identity, UserPO user) {
        AudienceDTO audience = new AudienceDTO();
        audience.setUsername(user.getUsername());
        long now = System.currentTimeMillis();
        long expiresAt = Math.min(now + MAX_LOCAL_TOKEN_MILLIS, identity.expiresAt());
        String token = JWT.create()
            .withExpiresAt(new Date(expiresAt))
            .withAudience(JsonUtil.toJsonString(audience))
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
        return new IssuedSession(token, identity.returnPath());
    }

    /**
     * 验证 AI 本地签名并读取中心会话声明。
     *
     * @param token AI 原生认证头中的 Token
     * @return 已验签的中心会话声明
     */
    public IamTokenClaims verify(String token) {
        DecodedJWT decoded = JWT.decode(token);
        String username = JsonUtil.parseObject(
            decoded.getAudience().get(0),
            AudienceDTO.class
        ).getUsername();
        UserPO user = userMapper.selectOne(
            new LambdaQueryWrapper<UserPO>().eq(UserPO::getUsername, username)
        );
        if (user == null) {
            throw new IllegalStateException("AI 本地用户不存在");
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
            throw new IllegalStateException("AI Token 缺少统一身份会话声明");
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
     * 签发给 AI 前端的本地会话。
     *
     * @param accessToken 原生兼容 Token
     * @param returnPath 受控返回路径
     */
    public record IssuedSession(String accessToken, String returnPath) {
    }

    /**
     * AI Token 中携带的中心会话声明。
     *
     * @param localUserId AI 本地用户主键
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
