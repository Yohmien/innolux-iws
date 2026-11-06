/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : IWS 服务端访问统一身份中心的签名客户端；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamBridgeIssueRequest;
import com.innolux.iws.common.auth.domain.IamBridgeTicketResponse;
import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.domain.IamTicketExchangeRequest;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.utils.IamRequestSigner;
import com.innolux.iws.common.auth.utils.IamSecureTokens;
import com.innolux.iws.common.auth.utils.IamHttpClientFactory;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.web.config.IamClientProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 统一身份中心服务端客户端。
 */
@Service
@RequiredArgsConstructor
public class IamCenterClient {

    private final IamClientProperties properties;

    /**
     * 核销中心一次性票据。
     *
     * @param ticket 一次性票据
     * @param frontendId 发起登录的受信前端
     * @param state  客户端随机状态
     * @return 原系统用户和根会话
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
     * 只读核验票据，供 IWS 在提交中心占位前完成本地身份准备。
     *
     * @param ticket 一次性票据
     * @param frontendId 发起登录的受信前端
     * @param state 客户端随机状态
     * @return 待提交身份与根会话
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
        JsonNode response;
        try {
            response = postSigned(path, body);
        } catch (RuntimeException firstFailure) {
            if (!"/iam/tickets/exchange".equals(path)) {
                throw firstFailure;
            }
            response = postSigned(path, body);
        }
        IamTicketExchangeResponse result = JsonUtils.parseObject(
            response.toString(), IamTicketExchangeResponse.class
        );
        if (result == null || !IamProtocolConstants.ISSUER.equals(result.issuer())
            || !properties.getApplicationId().equals(result.applicationId())
            || !properties.getApplicationType().equals(result.applicationType())
            || !frontendId.equals(result.frontendId())
            || !properties.getInstanceId().equals(result.instanceId())) {
            throw new ServiceException("统一身份中心返回的权威身份不匹配", 503);
        }
        return result;
    }

    /**
     * 查询中心根会话状态。
     *
     * @param rootSid           根会话标识
     * @param frontendId        受信前端入口
     * @param sessionId         应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal          终端类型
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
        JsonNode response = postSigned("/iam/session/status", body);
        IamSessionStatusResponse result = JsonUtils.parseObject(
            response.toString(), IamSessionStatusResponse.class
        );
        if (result == null || !IamProtocolConstants.ISSUER.equals(result.issuer())) {
            throw new ServiceException("统一身份中心返回的根会话签发方不匹配", 503);
        }
        return result;
    }

    /**
     * 为当前 IWS 会话申请嵌入应用一次性桥接票据。
     *
     * @param request 来源会话与目标应用
     * @return 目标应用一次性票据
     */
    public IamBridgeTicketResponse issueBridgeTicket(IamBridgeIssueRequest request) {
        requireConfiguration();
        JsonNode response = postSigned(
            "/iam/bridge/issue",
            JsonUtils.toJsonString(request)
        );
        IamBridgeTicketResponse result = JsonUtils.parseObject(
            response.toString(),
            IamBridgeTicketResponse.class
        );
        if (result == null
            || result.ticket() == null
            || result.ticket().isBlank()
            || result.state() == null
            || result.state().isBlank()
            || !request.targetApplicationId().equals(result.applicationId())
            || result.frontendId() == null
            || result.frontendId().isBlank()
            || !IamProtocolConstants.TERMINAL_WEB.equals(result.terminal())) {
            throw new ServiceException("统一身份中心返回的嵌入应用票据不匹配", 503);
        }
        return result;
    }

    /**
     * 撤销中心根会话。
     *
     * @param rootSid           根会话标识
     * @param sessionId         应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal          终端类型
     */
    public boolean revoke(
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
        return postSigned("/iam/session/revoke", body).asBoolean(false);
    }

    /**
     * 发送已签名的中心服务请求，并读取 R.data。
     *
     * @param path 请求路径
     * @param body 原始 JSON 请求体
     * @return data 节点
     */
    private JsonNode postSigned(String path, String body) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = IamSecureTokens.randomToken();
        String signature = IamRequestSigner.sign(
            properties.getClientSecret(), properties.getApplicationId(), properties.getInstanceId(),
            "POST", path, timestamp, nonce, body
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
                throw new ServiceException(envelope.path("msg").asText("统一身份中心请求失败"), code);
            }
            return envelope.path("data");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("统一身份中心不可达", 503);
        }
    }

    /**
     * 校验应用组、节点和协议密钥配置完整。
     */
    private void requireConfiguration() {
        if (isBlank(properties.getApplicationId()) || isBlank(properties.getInstanceId())
            || isBlank(properties.getClientSecret())) {
            throw new ServiceException("统一身份应用组、节点或密钥未配置", 503);
        }
    }

    /**
     * 判断文本是否为空。
     *
     * @param value 待判断文本
     * @return 是否为空
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
