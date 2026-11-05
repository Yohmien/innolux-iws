/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 统一身份协议随机标识生成器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.utils;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 统一身份协议随机标识生成器。
 */
public final class IamSecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private IamSecureTokens() {
    }

    /**
     * 生成 256 位随机标识。
     *
     * @return 十六进制随机标识
     */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
