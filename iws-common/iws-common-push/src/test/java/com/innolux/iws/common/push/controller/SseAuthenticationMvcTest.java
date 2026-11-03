/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : SSE 认证失败响应
 * @Version      : V1.0
 * @Description  : 验证流建立前的 JSON 认证响应、配置化路径与已认证连接归属；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.push.controller;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.push.core.SseEmitterSessionManager;
import com.innolux.iws.common.satoken.handler.SaTokenExceptionHandler;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 在真实 MVC 转换链上隔离认证提供者和连接存储，不启动 Redis 或数据库。 */
@Tag("local")
class SseAuthenticationMvcTest {

    private static final String DEFAULT_PATH = "/resource/message";
    private static final String CUSTOM_PATH = "/internal/events";
    private static final String INVALID_SESSION = "登录状态异常，请重新登录";
    private static final String EXPIRED_SESSION = "登录已过期，请重新登录";

    private SseEmitterSessionManager sessions;

    @BeforeEach
    void setUp() {
        sessions = mock(SseEmitterSessionManager.class);
    }

    static Stream<Arguments> sseFailures() {
        return Stream.of(DEFAULT_PATH, CUSTOM_PATH).flatMap(path -> Stream.of(
            Arguments.of(path, NotLoginException.NOT_TOKEN, INVALID_SESSION),
            Arguments.of(path, NotLoginException.INVALID_TOKEN, INVALID_SESSION),
            Arguments.of(path, NotLoginException.TOKEN_TIMEOUT, EXPIRED_SESSION)
        ));
    }

    @ParameterizedTest
    @MethodSource("sseFailures")
    void controllerRejectsMissingInvalidOrExpiredSessionAsJson(String path, String type, String message)
        throws Exception {
        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::checkLogin).thenThrow(authFailure(type));

            mvc(path, false).perform(get(path).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().encoding(StandardCharsets.UTF_8))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value(message))
                .andExpect(request().asyncNotStarted());

            verifyNoInteractions(sessions);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {DEFAULT_PATH, CUSTOM_PATH})
    void interceptorRejectionBeforeControllerAlsoUsesHttp401(String path) throws Exception {
        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::checkLogin).thenThrow(authFailure(NotLoginException.INVALID_TOKEN));

            mvc(path, true).perform(get(path).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value(INVALID_SESSION))
                .andExpect(request().asyncNotStarted());

            verifyNoInteractions(sessions);
        }
    }

    @ParameterizedTest
    @CsvSource({"71, isolated-session-a", "72, isolated-session-b"})
    void authenticatedConnectionKeepsStreamHeadersAndOwner(long userId, String token) throws Exception {
        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getTokenValue).thenReturn(token);
            auth.when(() -> StpUtil.getExtra(LoginHelper.USER_KEY)).thenReturn(userId);
            SseEmitter emitter = new SseEmitter();
            emitter.send(SseEmitter.event().comment("connected"));
            when(sessions.connect(userId, token)).thenReturn(emitter);
            MockMvc mvc = mvc(CUSTOM_PATH, true);

            MvcResult result = mvc.perform(get(CUSTOM_PATH).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(request().asyncStarted())
                .andReturn();

            assertTrue(result.getResponse().getContentAsString().contains(":connected"));
            verify(sessions).connect(userId, token);
            emitter.complete();
            mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        }
    }

    @Test
    void ordinaryJsonAuthenticationErrorKeepsExistingResponseContract() throws Exception {
        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::checkLogin).thenThrow(authFailure(NotLoginException.TOKEN_TIMEOUT));

            mvc(DEFAULT_PATH, false).perform(get("/json/auth-error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value(EXPIRED_SESSION));

            verifyNoInteractions(sessions);
        }
    }

    @Test
    void ordinaryJsonPermissionErrorKeepsExistingResponseContract() throws Exception {
        mvc(DEFAULT_PATH, false).perform(get("/json/permission-error").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.msg").value("没有访问权限，请联系管理员授权"));

        verifyNoInteractions(sessions);
    }

    private MockMvc mvc(String messagePath, boolean withLoginInterceptor) {
        var builder = MockMvcBuilders.standaloneSetup(new SseController(sessions), new JsonFailureController())
            .addPlaceholderValue("message.path", messagePath)
            .setControllerAdvice(new SaTokenExceptionHandler())
            .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new JacksonJsonHttpMessageConverter());
        if (withLoginInterceptor) {
            builder.addInterceptors(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    StpUtil.checkLogin();
                    return true;
                }
            });
        }
        return builder.build();
    }

    private NotLoginException authFailure(String type) {
        return NotLoginException.newInstance("login", type, "隔离测试认证失败", null);
    }

    @RestController
    static class JsonFailureController {

        @GetMapping("/json/auth-error")
        R<Void> authenticationError() {
            StpUtil.checkLogin();
            return R.ok();
        }

        @GetMapping("/json/permission-error")
        R<Void> permissionError() {
            throw new NotPermissionException("isolated:permission");
        }
    }
}
