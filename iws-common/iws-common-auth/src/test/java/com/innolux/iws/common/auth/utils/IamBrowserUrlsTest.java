package com.innolux.iws.common.auth.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 统一身份浏览器回环地址转换测试。
 */
@Tag("dev")
class IamBrowserUrlsTest {

    @Test
    void replacesLoopbackHostAndKeepsPortAndPath() {
        assertEquals(
            "http://10.189.88.118:18081/iam/login",
            IamBrowserUrls.resolveLoopbackHost(
                "http://127.0.0.1:18081/iam/login",
                "10.189.88.118"
            )
        );
    }

    @Test
    void keepsExplicitNonLoopbackHost() {
        assertEquals(
            "http://10.189.88.204:18000/login",
            IamBrowserUrls.resolveLoopbackHost(
                "http://10.189.88.204:18000/login",
                "10.189.88.118"
            )
        );
    }
}
