/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 验证单连接校验阻塞不会误踢已完成的合法连接；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import com.innolux.iws.common.push.core.PushSessionIdentity;
import com.innolux.iws.common.push.core.SseEmitterSessionManager;
import com.innolux.iws.common.push.core.WebSocketSessionManager;
import com.innolux.iws.web.config.IamClientProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推送巡检并发边界测试。
 */
@Tag("local")
class IamPushSessionGuardTest {

    @Test
    void blockedConnectionDoesNotKickCompletedValidConnection() {
        IamSessionValidationService validation = mock(IamSessionValidationService.class);
        when(validation.validate(anyString(), anyBoolean())).thenAnswer(invocation -> {
            if ("slow".equals(invocation.getArgument(0))) {
                Thread.sleep(3000);
            }
            return IamSessionValidationService.ValidationResult.success();
        });
        IamClientProperties properties = new IamClientProperties();
        properties.setResponseTimeoutMs(50);
        SseEmitterSessionManager sse = mock(SseEmitterSessionManager.class);
        when(sse.activeSessions()).thenReturn(List.of(
            new PushSessionIdentity(1L, "slow"),
            new PushSessionIdentity(2L, "valid")
        ));
        ObjectProvider<SseEmitterSessionManager> sseProvider = mock(ObjectProvider.class);
        when(sseProvider.getIfAvailable()).thenReturn(sse);
        ObjectProvider<WebSocketSessionManager> webSocketProvider = mock(ObjectProvider.class);
        when(webSocketProvider.getIfAvailable()).thenReturn(null);
        IamPushSessionGuard guard = new IamPushSessionGuard(
            validation, properties, sseProvider, webSocketProvider
        );

        long started = System.nanoTime();
        guard.validateConnections();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        guard.destroy();

        assertTrue(elapsedMs < 2500, "巡检不应等待阻塞连接超过预算");
        verify(sse).kick(eq(1L), eq("slow"));
        verify(sse, never()).kick(eq(2L), eq("valid"));
    }
}
