/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 单次应用跳转票据签发与原子核销；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.service;

import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamAuthorizationResponse;
import com.innolux.iws.common.auth.domain.IamTicketCommitResult;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.domain.IamTicketRecord;
import com.innolux.iws.common.auth.utils.IamSecureTokens;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.common.redis.utils.RedisUtils;
import com.innolux.iws.system.api.model.LoginUser;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 一次性应用票据服务。
 */
@Service
@RequiredArgsConstructor
public class IamTicketService {

    private static final Pattern STATE_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{16,256}");

    private final IamProperties properties;

    /**
     * 为已授权应用签发一次性票据。
     *
     * @param loginUser  当前原系统登录用户
     * @param applicationId 目标应用标识
     * @param frontendId 发起登录的受信前端
     * @param state      客户端随机状态
     * @param returnPath 应用内返回路径
     * @param rootSid    中心根会话标识
     * @param session    已激活的当前应用会话
     * @return 票据与精确回调地址
     */
    public IamAuthorizationResponse issue(LoginUser loginUser, String applicationId, String frontendId,
                                          PreparedAuthorization authorization, String rootSid,
                                          IamApplicationPreparation preparation) {
        IamProperties.Application application = properties.getApplications().get(applicationId);
        IamProperties.Frontend frontend = properties.getFrontends().get(frontendId);
        if (application == null || !application.isEnabled()
            || frontend == null || !frontend.isEnabled()
            || !application.getAllowedFrontendIds().contains(frontendId)
            || !frontend.getTerminal().equals(preparation.terminal())) {
            throw new ServiceException("未登记的目标应用");
        }
        long expiresAt = Instant.now().plus(properties.getTicketTtl()).toEpochMilli();
        String ticket = IamSecureTokens.randomToken();
        IamTicketRecord record = new IamTicketRecord(
            IamProtocolConstants.ISSUER,
            applicationId,
            preparation.applicationType(),
            frontendId,
            authorization.state(),
            loginUser.getUserId(),
            loginUser.getUsername(),
            rootSid,
            preparation.sessionId(),
            preparation.sessionGeneration(),
            preparation.terminal(),
            preparation.transactionId(),
            authorization.returnPath(),
            expiresAt
        );
        RedisUtils.setCacheObject(
            IamProtocolConstants.TICKET_KEY_PREFIX + ticket,
            JsonUtils.toJsonString(record),
            properties.getTicketTtl()
        );
        String redirectUri = buildRedirectUri(
            frontend.getRedirectUri(),
            ticket,
            authorization.state(),
            authorization.returnPath(),
            applicationId,
            frontendId,
            record.terminal()
        );
        return new IamAuthorizationResponse(ticket, redirectUri, expiresAt);
    }

    /**
     * 读取票据提交后缓存的身份结果；网络重试不能重复激活应用。
     *
     * @param ticket 一次性票据
     * @param applicationId 发起核销的应用
     * @param frontendId 发起核销的前端
     * @return 已提交结果；不存在时返回 null
     */
    public IamTicketExchangeResponse committed(
        String ticket,
        String applicationId,
        String frontendId,
        String state,
        String commitId
    ) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        String payload = RedisUtils.getCacheObject(
            IamProtocolConstants.TICKET_COMMIT_KEY_PREFIX + ticket
        );
        if (payload == null) {
            return null;
        }
        IamTicketCommitResult result = JsonUtils.parseObject(
            payload,
            IamTicketCommitResult.class
        );
        if (result == null || result.response() == null
            || !applicationId.equals(result.response().applicationId())
            || !frontendId.equals(result.response().frontendId())) {
            throw new ServiceException("票据提交结果与当前应用不匹配");
        }
        if (!state.equals(result.state()) || !commitId.equals(result.commitId())) {
            throw new ServiceException("票据提交请求与已提交记录不匹配");
        }
        return result.response();
    }

    /**
     * 缓存票据提交结果，供同一票据短时幂等重试。
     *
     * @param ticket 一次性票据
     * @param state 受绑定客户端状态
     * @param commitId 目标应用提交标识
     * @param response 已提交身份结果
     */
    public void cacheCommitted(
        String ticket,
        String state,
        String commitId,
        IamTicketExchangeResponse response
    ) {
        RedisUtils.setCacheObject(
            IamProtocolConstants.TICKET_COMMIT_KEY_PREFIX + ticket,
            JsonUtils.toJsonString(new IamTicketCommitResult(state, commitId, response)),
            properties.getTicketTtl()
        );
    }

    /**
     * 在产生任何应用会话副作用前校验签票参数。
     *
     * @param state      客户端随机状态
     * @param returnPath 应用内返回路径
     * @return 已校验参数
     */
    public PreparedAuthorization prepareAuthorization(String state, String returnPath) {
        if (state == null || !STATE_PATTERN.matcher(state).matches()) {
            throw new ServiceException("state 格式不合法");
        }
        return new PreparedAuthorization(state, normalizeReturnPath(returnPath));
    }

    /**
     * 同时兼容普通地址与 uni-app/H5 的 hash 路由回调。
     *
     * @param redirectUri 已登记回调地址
     * @param ticket 一次性票据
     * @param state 客户端状态
     * @param returnPath 受控返回路径
     * @param applicationId 目标应用
     * @param frontendId 受信前端
     * @param terminal 终端
     * @return 带回调参数的地址
     */
    private String buildRedirectUri(
        String redirectUri,
        String ticket,
        String state,
        String returnPath,
        String applicationId,
        String frontendId,
        String terminal
    ) {
        String query = "ticket=" + encode(ticket)
            + "&state=" + encode(state)
            + "&returnPath=" + encode(returnPath)
            + "&applicationId=" + encode(applicationId)
            + "&frontendId=" + encode(frontendId)
            + "&terminal=" + encode(terminal);
        int fragmentIndex = redirectUri.indexOf('#');
        if (fragmentIndex >= 0) {
            String base = redirectUri.substring(0, fragmentIndex);
            String fragment = redirectUri.substring(fragmentIndex + 1);
            String separator = fragment.contains("?") ? "&" : "?";
            return base + "#" + fragment + separator + query;
        }
        return UriComponentsBuilder.fromUriString(redirectUri)
            .query(query)
            .build(true)
            .toUri()
            .toString();
    }

    /**
     * 编码回调查询值。
     *
     * @param value 原值
     * @return 编码值
     */
    private String encode(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }

    /**
     * 原子消费一次性票据。
     *
     * @param ticket   待核销票据
     * @param applicationId 发起核销的目标应用
     * @param frontendId 发起登录的受信前端
     * @param state    目标应用持有的原始 state
     * @return 票据载荷
     */
    public IamTicketRecord consume(String ticket, String applicationId, String frontendId, String state) {
        String payload = loadPayload(ticket);
        IamTicketRecord record = validatePayload(payload, applicationId, frontendId, state);
        RBucket<String> bucket = RedisUtils.getClient().getBucket(IamProtocolConstants.TICKET_KEY_PREFIX + ticket);
        if (!bucket.compareAndSet(payload, null)) {
            throw new ServiceException("票据已被并发使用");
        }
        return record;
    }

    /**
     * 读取并校验票据，但暂不消费；供调用方先完成配置和会话复核。
     *
     * @param ticket        待核验票据
     * @param applicationId 发起核销的目标应用
     * @param frontendId    发起登录的受信前端
     * @param state         目标应用持有的原始 state
     * @return 票据载荷
     */
    public IamTicketRecord inspect(String ticket, String applicationId, String frontendId, String state) {
        return validatePayload(loadPayload(ticket), applicationId, frontendId, state);
    }

    /**
     * 读取票据原始载荷，供在同一原子提交脚本中核对票据仍未被消费。
     *
     * @param ticket 待核验票据
     * @return 原始载荷
     */
    public String payload(String ticket) {
        return loadPayload(ticket);
    }

    /**
     * 读取票据在 Redis 中的原始 JSON 编码，供 StringCodec Lua 精确比较。
     *
     * @param ticket 待核验票据
     * @return 原始 Redis 值
     */
    public String rawPayload(String ticket) {
        String payload = RedisUtils.getClient()
            .<String>getBucket(
                IamProtocolConstants.TICKET_KEY_PREFIX + ticket,
                StringCodec.INSTANCE
            )
            .get();
        if (payload == null) {
            throw new ServiceException("票据不存在、已使用或已过期");
        }
        return payload;
    }

    /**
     * 读取票据原始载荷。
     *
     * @param ticket 待读取票据
     * @return 未消费的票据载荷
     */
    private String loadPayload(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new ServiceException("票据不能为空");
        }
        RBucket<String> bucket = RedisUtils.getClient().getBucket(IamProtocolConstants.TICKET_KEY_PREFIX + ticket);
        String payload = bucket.get();
        if (payload == null) {
            throw new ServiceException("票据不存在、已使用或已过期");
        }
        return payload;
    }

    /**
     * 校验票据签发方、目标、前端、state 与有效期。
     *
     * @param payload       票据原始载荷
     * @param applicationId 发起核销的目标应用
     * @param frontendId    发起登录的受信前端
     * @param state         目标应用持有的原始 state
     * @return 校验后的票据
     */
    private IamTicketRecord validatePayload(
        String payload,
        String applicationId,
        String frontendId,
        String state
    ) {
        IamTicketRecord record = JsonUtils.parseObject(payload, IamTicketRecord.class);
        if (record == null || !IamProtocolConstants.ISSUER.equals(record.issuer())
            || record.expiresAt() <= Instant.now().toEpochMilli()) {
            throw new ServiceException("票据不存在、已使用或已过期");
        }
        if (applicationId == null || !applicationId.equals(record.applicationId())) {
            throw new ServiceException("票据与目标应用不匹配");
        }
        if (frontendId == null || !frontendId.equals(record.frontendId())) {
            throw new ServiceException("票据与受信前端不匹配");
        }
        if (state == null || !state.equals(record.state())) {
            throw new ServiceException("state 校验失败");
        }
        return record;
    }

    /**
     * 只允许应用内绝对路径，拒绝协议相对地址和外部跳转。
     *
     * @param returnPath 原始返回路径
     * @return 规范化返回路径
     */
    private String normalizeReturnPath(String returnPath) {
        if (returnPath == null || returnPath.isBlank()) {
            return "/";
        }
        String value = returnPath.trim();
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("\r") || value.contains("\n")
            || value.length() > 512) {
            throw new ServiceException("returnPath 不合法");
        }
        return value;
    }

    /**
     * 已校验的授权请求参数。
     *
     * @param state      随机状态
     * @param returnPath 规范化返回路径
     */
    public record PreparedAuthorization(String state, String returnPath) {
    }
}
