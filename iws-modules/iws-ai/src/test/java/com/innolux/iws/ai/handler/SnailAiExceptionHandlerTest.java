/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : SnailAI 异常映射与全局异常处理优先级测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.ai.handler;

import com.aizuda.snail.ai.common.execption.SnailAiException;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.web.handler.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SnailAI 异常处理器测试。
 */
@Tag("dev")
class SnailAiExceptionHandlerTest {

    private final SnailAiExceptionHandler handler = new SnailAiExceptionHandler();

    @Test
    void returnsFixedApplicationPairingHintForAuthenticationFailure() {
        HttpServletRequest request = new MockHttpServletRequest("POST", "/snail-ai/user/register");
        String secret = "HTTP_BODY_SECRET_d1f3c8";

        R<Void> response = handler.handleSnailAiException(
            new SnailAiException("应用认证失败: 1; body=" + secret), request);

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMsg())
            .contains("SnailAI 应用认证失败")
            .contains("应用管理")
            .contains("snail-ai.app-id")
            .contains("snail-ai.token")
            .doesNotContain(secret)
            .doesNotContain("body=");
    }

    @Test
    void returnsUnifiedSafeMessageForNonAuthenticationFailure() {
        HttpServletRequest request = new MockHttpServletRequest("POST", "/snail-ai/user/register");
        String secret = "HTTP_BODY_SECRET_7a9e2b";

        R<Void> response = handler.handleSnailAiException(
            new SnailAiException("OpenAPI request failed: HTTP 500 - " + secret), request);

        assertThat(response.getMsg())
            .isEqualTo("SnailAI 调用失败，请检查 SnailAI 服务端状态和应用配置。")
            .doesNotContain(secret)
            .doesNotContain("HTTP 500");
    }

    @Test
    void doesNotLogSdkMessageOrCause() {
        Logger logger = (Logger) LoggerFactory.getLogger(SnailAiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String secret = "HTTP_BODY_SECRET_9c1f4a";
            SnailAiException exception = new SnailAiException("应用认证失败: 1; body=" + secret);

            handler.handleSnailAiException(exception, new MockHttpServletRequest("POST", "/snail-ai/user/register"));

            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining(System.lineSeparator()));
            assertThat(logs)
                .contains("SnailAiException")
                .contains("/snail-ai/user/register")
                .contains("分类: 应用认证失败")
                .doesNotContain(secret)
                .doesNotContain("应用认证失败: 1")
                .doesNotContain("body=");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void specificHandlerPrecedesGenericRuntimeHandler() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler(), new SnailAiExceptionHandler())
            .build();

        mockMvc.perform(post("/snail-ai/user/register"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.msg").value(containsString("SnailAI 应用认证失败")))
            .andExpect(jsonPath("$.msg").value(containsString("应用管理")));
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/snail-ai/user/register")
        public R<Void> register() {
            throw new SnailAiException("应用认证失败: 1");
        }
    }
}
