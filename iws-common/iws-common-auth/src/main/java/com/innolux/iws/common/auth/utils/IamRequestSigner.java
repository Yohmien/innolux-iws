/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 服务间统一身份请求 HMAC 签名工具；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 服务间统一身份请求签名工具。
 */
public final class IamRequestSigner {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    private IamRequestSigner() {
    }

    /**
     * 生成请求签名。
     *
     * @param secret    客户端共享密钥
     * @param applicationId 目标应用组标识
     * @param instanceId 目标应用节点标识
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param timestamp Unix 秒时间戳
     * @param nonce     请求随机值
     * @param body      原始请求体
     * @return 十六进制签名
     */
    public static String sign(String secret, String applicationId, String instanceId, String method, String path,
                              String timestamp, String nonce, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(canonical(
                applicationId, instanceId, method, path, timestamp, nonce, body
            )
                .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法生成统一身份请求签名", e);
        }
    }

    /**
     * 以常量时间比较方式校验请求签名。
     *
     * @param secret          客户端共享密钥
     * @param applicationId   目标应用组标识
     * @param instanceId      目标应用节点标识
     * @param method          HTTP 方法
     * @param path            请求路径
     * @param timestamp       Unix 秒时间戳
     * @param nonce           请求随机值
     * @param body            原始请求体
     * @param suppliedSignature 发送方签名
     * @return 是否匹配
     */
    public static boolean verify(String secret, String applicationId, String instanceId, String method, String path,
                                 String timestamp, String nonce, String body, String suppliedSignature) {
        if (secret == null || secret.isBlank() || suppliedSignature == null || suppliedSignature.isBlank()) {
            return false;
        }
        byte[] expected = sign(secret, applicationId, instanceId, method, path, timestamp, nonce, body)
            .getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedSignature.toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    /**
     * 构造规范化签名原文。
     *
     * @param applicationId 目标应用组标识
     * @param instanceId    目标应用节点标识
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param timestamp Unix 秒时间戳
     * @param nonce     请求随机值
     * @param body      原始请求体
     * @return 规范化签名原文
     */
    private static String canonical(String applicationId, String instanceId, String method, String path,
                                    String timestamp, String nonce, String body) {
        return applicationId + "\n" + instanceId + "\n" + method.toUpperCase() + "\n" + path + "\n"
            + timestamp + "\n" + nonce + "\n" + sha256(body);
    }

    /**
     * 计算请求体 SHA-256。
     *
     * @param body 原始请求体
     * @return 小写十六进制摘要
     */
    private static String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法计算请求摘要", e);
        }
    }
}
