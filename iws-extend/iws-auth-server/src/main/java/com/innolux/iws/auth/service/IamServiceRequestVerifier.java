/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 校验目标应用服务间的签名、时间窗与 nonce 重放；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.service;

import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.utils.IamRequestSigner;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.redis.utils.RedisUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 目标应用服务间请求校验。
 */
@Service
@RequiredArgsConstructor
public class IamServiceRequestVerifier {

    private final IamProperties properties;

    /**
     * 校验目标应用签名请求。
     *
     * @param applicationId 目标应用组标识
     * @param instanceId    目标应用节点标识
     * @param timestamp Unix 秒时间戳
     * @param nonce     请求随机值
     * @param signature HMAC 签名
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param body      原始请求体
     */
    public void verify(String applicationId, String instanceId, String timestamp, String nonce, String signature,
                       String method, String path, String body) {
        IamProperties.Application application = properties.getApplications().get(applicationId);
        if (application == null || !application.isEnabled()) {
            throw new ServiceException("未登记的目标应用", 403);
        }
        if (instanceId == null || instanceId.isBlank()
            || !application.getAllowedInstanceIds().contains(instanceId)) {
            throw new ServiceException("目标应用节点未登记", 403);
        }
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new ServiceException("统一身份请求时间戳不合法", 403);
        }
        long now = Instant.now().getEpochSecond();
        long clockSkewSeconds = properties.getRequestClockSkew().toSeconds();
        if (Math.abs(now - requestTime) > clockSkewSeconds) {
            throw new ServiceException("统一身份请求已过期", 403);
        }
        if (nonce == null || nonce.length() < 16 || nonce.length() > 128) {
            throw new ServiceException("统一身份请求 nonce 不合法", 403);
        }
        String nonceKey = IamProtocolConstants.NONCE_KEY_PREFIX + applicationId + ":" + instanceId + ":" + nonce;
        if (!RedisUtils.setObjectIfAbsent(nonceKey, "1", properties.getRequestClockSkew().multipliedBy(2))) {
            throw new ServiceException("统一身份请求 nonce 已使用", 403);
        }
        if (!IamRequestSigner.verify(
            application.getClientSecret(), applicationId, instanceId, method, path, timestamp, nonce, body, signature
        )) {
            throw new ServiceException("统一身份请求签名校验失败", 403);
        }
    }
}
