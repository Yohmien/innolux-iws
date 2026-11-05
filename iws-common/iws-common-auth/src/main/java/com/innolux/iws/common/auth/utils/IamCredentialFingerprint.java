/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 基于原密码哈希的会话凭据指纹，避免账号表新增版本字段；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 原账号密码哈希指纹工具。
 */
public final class IamCredentialFingerprint {

    private IamCredentialFingerprint() {
    }

    /**
     * 计算原密码哈希的 SHA-256 指纹。
     *
     * @param passwordHash 原账号密码哈希
     * @return 十六进制指纹
     */
    public static String of(String passwordHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(String.valueOf(passwordHash).getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算账号凭据指纹", e);
        }
    }

    /**
     * 以常量时间比较原密码哈希与已保存指纹。
     *
     * @param passwordHash 当前原密码哈希
     * @param expectedFingerprint 已保存指纹
     * @return 是否匹配
     */
    public static boolean matches(String passwordHash, String expectedFingerprint) {
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            of(passwordHash).getBytes(StandardCharsets.US_ASCII),
            expectedFingerprint.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
