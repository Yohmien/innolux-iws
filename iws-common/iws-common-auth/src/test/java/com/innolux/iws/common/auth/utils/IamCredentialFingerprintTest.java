/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 验证密码哈希指纹的稳定比较和缺失值拒绝；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Tag("dev")
class IamCredentialFingerprintTest {

    @Test
    void samePasswordHashProducesStableFingerprint() {
        String hash = "$2a$10$isolated-password-hash";

        assertEquals(IamCredentialFingerprint.of(hash), IamCredentialFingerprint.of(hash));
    }

    @Test
    void differentPasswordHashDoesNotMatchStoredFingerprint() {
        String stored = IamCredentialFingerprint.of("$2a$10$old-password-hash");

        assertFalse(IamCredentialFingerprint.matches("$2a$10$new-password-hash", stored));
        assertNotEquals(stored, IamCredentialFingerprint.of("$2a$10$new-password-hash"));
    }

    @Test
    void blankStoredFingerprintIsRejected() {
        assertFalse(IamCredentialFingerprint.matches("$2a$10$hash", null));
        assertFalse(IamCredentialFingerprint.matches("$2a$10$hash", " "));
    }
}
