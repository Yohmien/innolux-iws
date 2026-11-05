/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.0
 * @Description  : Admin 监控访问统一身份中心的签名客户端；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.monitor.admin.service;

import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.domain.IamTicketExchangeRequest;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.utils.IamRequestSigner;
import com.innolux.iws.common.auth.utils.IamSecureTokens;
import com.innolux.iws.common.auth.utils.IamHttpClientFactory;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.monitor.admin.config.IamMonitorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/**
 * Admin 监控统一身份中心客户端。
 */
@Service
@RequiredArgsConstructor
public class IamMonitorCenterClient {

    private final IamMonitorProperties properties;

    /**
     * 核销一次性票据。
     *
     * @param ticket 一次性票据
     * @param frontendId 受信前端
     * @param state      客户端随机状态
     * @return 原 user_id、根会话和应用标识
     */
    public IamTicketExchangeResponse exchange(String ticket, String frontendId, String state) {
        return exchange(ticket, frontendId, state, null);
    }

    public IamTicketExchangeResponse exchange(
        String ticket,
        String frontendId,
        String state,
        String commitId
    ) {
        return ticketIdentity("/iam/tickets/exchange", ticket, frontendId, state, commitId);
    }

    /**
     * 只读核验票据，供监控在提交中心占位前完成人员会话准备。
     *
     * @param ticket 一次性票据
     * @param frontendId 受信前端
     * @param state 随机状态
     * @return 待提交身份
     */
    public IamTicketExchangeResponse inspect(String ticket, String frontendId, String state) {
        return ticketIdentity("/iam/tickets/inspect", ticket, frontendId, state, null);
    }

    private IamTicketExchangeResponse ticketIdentity(
        String path,
        String ticket,
        String frontendId,
        String state,
        String commitId
    ) {
        requireConfiguration();
        String body = JsonUtils.toJsonString(
            new IamTicketExchangeRequest(
                properties.getApplicationId(), frontendId, ticket, state, commitId
            )
        );
        JsonNode rawResponse;
        try {
            rawResponse = postSigned(path, body);
        } catch (RuntimeException firstFailure) {
            if (!"/iam/tickets/exchange".equals(path)) {
                throw firstFailure;
            }
            rawResponse = postSigned(path, body);
        }
        IamTicketExchangeResponse response = JsonUtils.parseObject(
            rawResponse.toString(),
            IamTicketExchangeResponse.class
        );
        if (response == null || !IamProtocolConstants.ISSUER.equals(response.issuer())
            || !properties.getApplicationId().equals(response.applicationId())
            || !properties.getApplicationType().equals(response.applicationType())
            || !properties.getFrontendId().equals(response.frontendId())
            || !properties.getInstanceId().equals(response.instanceId())) {
            throw new IllegalStateException("统一身份中心返回的权威身份不匹配");
        }
        return response;
    }

    /**
     * 查询根会话及当前应用接入状态。
     *
     * @param rootSid           根会话标识
     * @param frontendId        受信前端
     * @param sessionId         应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal          终端
     * @return 根会话状态
     */
    public IamSessionStatusResponse status(
        String rootSid,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        requireConfiguration();
        String body = JsonUtils.toJsonString(new IamSessionStatusRequest(
            rootSid,
            properties.getApplicationId(),
            frontendId,
            sessionId,
            sessionGeneration,
            terminal
        ));
        IamSessionStatusResponse response = JsonUtils.parseObject(
            postSigned("/iam/session/status", body).toString(),
            IamSessionStatusResponse.class
        );
        if (response == null || !IamProtocolConstants.ISSUER.equals(response.issuer())) {
            throw new IllegalStateException("统一身份中心返回的根会话签发方不匹配");
        }
        return response;
    }

    /**
     * 撤销根会话及当前根下全部应用会话。
     *
     * @param rootSid 根会话标识
     */
    public boolean revoke(
        String rootSid,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        requireConfiguration();
        return postSigned("/iam/session/revoke", JsonUtils.toJsonString(
            new IamSessionStatusRequest(
                rootSid,
                properties.getApplicationId(),
                frontendId,
                sessionId,
                sessionGeneration,
                terminal
            )
        )).asBoolean(false);
    }

    /**
     * 发送已签名的中心请求。
     *
     * @param path 请求路径
     * @param body 原始 JSON 请求体
     * @return data 节点
     */
    private JsonNode postSigned(String path, String body) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = IamSecureTokens.randomToken();
        String signature = IamRequestSigner.sign(
            properties.getClientSecret(),
            properties.getApplicationId(),
            properties.getInstanceId(),
            "POST",
            path,
            timestamp,
            nonce,
            body
        );
        try {
            String rawResponse = IamHttpClientFactory.builder(
                    properties.getCenterBaseUrl(),
                    properties.getConnectTimeoutMs(),
                    properties.getResponseTimeoutMs()
                )
                .build()
                .post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IamProtocolConstants.APPLICATION_HEADER, properties.getApplicationId())
                .header(IamProtocolConstants.INSTANCE_HEADER, properties.getInstanceId())
                .header(IamProtocolConstants.TIMESTAMP_HEADER, timestamp)
                .header(IamProtocolConstants.NONCE_HEADER, nonce)
                .header(IamProtocolConstants.SIGNATURE_HEADER, signature)
                .body(body)
                .retrieve()
                .body(String.class);
            JsonNode envelope = JsonUtils.getJsonMapper().readTree(rawResponse);
            int code = envelope.path("code").asInt();
            if (code != 200) {
                throw new IllegalStateException(envelope.path("msg").asText("统一身份中心请求失败"));
            }
            return envelope.path("data");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("统一身份中心不可达", e);
        }
    }

    private void requireConfiguration() {
        if (isBlank(properties.getCenterBaseUrl()) || isBlank(properties.getPublicBaseUrl())
            || isBlank(properties.getApplicationId()) || isBlank(properties.getInstanceId())
            || isBlank(properties.getClientSecret())) {
            throw new IllegalStateException("Admin 监控统一身份配置不完整");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
