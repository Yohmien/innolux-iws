/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : Job 访问统一身份中心的签名客户端；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.domain.IamTicketExchangeRequest;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.utils.IamRequestSigner;
import com.innolux.iws.common.auth.utils.IamSecureTokens;
import com.innolux.iws.common.auth.utils.IamHttpClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

/**
 * Job 统一身份中心客户端。
 */
@Service
@RequiredArgsConstructor
public class IamJobCenterClient {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final IamJobProperties properties;

    /**
     * 核销中心一次性票据。
     *
     * @param ticket 一次性票据
     * @param frontendId 受信前端标识
     * @param state 浏览器随机状态
     * @return 中心权威身份与应用会话
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
     * 只读核验票据，供 Job 在提交中心占位前完成本地用户和 namespace 准备。
     *
     * @param ticket 一次性票据
     * @param frontendId 受信前端标识
     * @param state 浏览器随机状态
     * @return 待提交权威身份与应用会话
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
        String body = JSON_MAPPER.writeValueAsString(
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
        IamTicketExchangeResponse response = JSON_MAPPER.readValue(
            rawResponse.toString(),
            IamTicketExchangeResponse.class
        );
        if (response == null || !IamProtocolConstants.ISSUER.equals(response.issuer())
            || !properties.getApplicationId().equals(response.applicationId())
            || !properties.getApplicationType().equals(response.applicationType())
            || !frontendId.equals(response.frontendId())
            || !properties.getInstanceId().equals(response.instanceId())) {
            throw new IllegalStateException("统一身份中心返回的 Job 身份不匹配");
        }
        return response;
    }

    /**
     * 查询中心根会话和 Job 应用接入状态。
     *
     * @param rootSid 根会话标识
     * @param frontendId 受信前端标识
     * @param sessionId Job 应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal 终端类型
     * @return 会话状态
     */
    public IamSessionStatusResponse status(
        String rootSid,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        requireConfiguration();
        String body = JSON_MAPPER.writeValueAsString(new IamSessionStatusRequest(
            rootSid,
            properties.getApplicationId(),
            frontendId,
            sessionId,
            sessionGeneration,
            terminal
        ));
        IamSessionStatusResponse response = JSON_MAPPER.readValue(
            postSigned("/iam/session/status", body).toString(),
            IamSessionStatusResponse.class
        );
        if (response == null || !IamProtocolConstants.ISSUER.equals(response.issuer())) {
            throw new IllegalStateException("统一身份中心返回的根会话签发方不匹配");
        }
        return response;
    }

    /**
     * 仅在当前 Job 应用会话仍有效时撤销整个中心根会话。
     *
     * @param rootSid           根会话标识
     * @param frontendId        受信前端入口
     * @param sessionId         应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal          终端类型
     */
    public boolean revokeRoot(
        String rootSid,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        requireConfiguration();
        return postSigned("/iam/session/revoke", JSON_MAPPER.writeValueAsString(
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
     * 发送签名后的中心请求并读取 R.data。
     *
     * @param path 中心路径
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
            JsonNode envelope = JSON_MAPPER.readTree(rawResponse);
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
            || isBlank(properties.getApplicationId()) || isBlank(properties.getApplicationType())
            || isBlank(properties.getFrontendId()) || isBlank(properties.getInstanceId())
            || isBlank(properties.getClientSecret())) {
            throw new IllegalStateException("Job 统一身份配置不完整");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
