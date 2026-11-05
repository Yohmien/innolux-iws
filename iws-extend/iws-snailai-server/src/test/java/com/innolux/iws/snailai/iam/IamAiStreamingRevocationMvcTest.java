/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version       : V1.0
 * @Description  : 在真实 Spring MVC Flux/SSE 管线验证撤权取消传播；
 * ModifyRecord  : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import com.aizuda.snail.ai.admin.interceptor.AuthenticationInterceptor;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 Flux 不经过 ResponseBodyAdvice，但撤权包装能取消原生 SSE 上游。
 */
@Tag("dev")
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = IamAiStreamingRevocationMvcTest.TestConfig.class)
class IamAiStreamingRevocationMvcTest {

    private static final String TOKEN = "isolated-ai-token";
    private static final IamAiLocalSessionService.IamTokenClaims CLAIMS =
        new IamAiLocalSessionService.IamTokenClaims(
            9L,
            42L,
            "root-isolated",
            "ai-main",
            "ai-session-isolated",
            1L,
            "WEB"
        );

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private IamAiCenterClient centerClient;

    @Autowired
    private IamAiLocalSessionService localSessionService;

    @Autowired
    private IamAiProperties properties;

    @Autowired
    private RecordingResponseBodyAdvice responseBodyAdvice;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @Autowired
    private StreamingController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        reset(centerClient, localSessionService);
        responseBodyAdvice.reset();
        when(localSessionService.verify(TOKEN)).thenReturn(CLAIMS);
        when(localSessionService.matchesMappedUser(anyString(), any())).thenReturn(true);
        properties.setValidationIntervalSeconds(1);
        properties.setResponseTimeoutMs(5000);
        controller.reset();
    }

    @Test
    void revocationAndCenterFailureCancelFluxBeforeNativeSseCompletes() throws Exception {
        when(centerClient.status(
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString()
        )).thenReturn(new IamSessionStatusResponse(false, "iws-auth-center", 42L, 0L, "撤销"));

        MvcResult result = startStream();
        awaitCancellation(controller);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        assertEquals(0, responseBodyAdvice.fluxCount.get(),
            "Flux SSE 不应经过 ResponseBodyAdvice，撤权必须由返回值处理器安装");
        assertInstalledBeforeNativeEmitterHandler();
    }

    @Test
    void centerUnavailableAlsoCancelsFlux() throws Exception {
        when(centerClient.status(
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString()
        )).thenThrow(new IllegalStateException("统一身份中心不可达"));

        MvcResult result = startStream();
        awaitCancellation(controller);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        assertTrue(controller.cancelled.get());
        assertEquals(0, responseBodyAdvice.fluxCount.get());
    }

    @Test
    void activeStreamContinuesUntilRevocationThenStopsWithoutEarlyCutoff() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger validations = new AtomicInteger();
        when(centerClient.status(
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString()
        )).thenAnswer(invocation -> new IamSessionStatusResponse(
            active.get(), "iws-auth-center", 42L, "center-user", 0L,
            active.get() ? null : "撤销"
        ));
        when(localSessionService.matchesMappedUser(anyString(), any())).thenAnswer(invocation -> {
            validations.incrementAndGet();
            Thread.sleep(50L);
            return true;
        });
        properties.setResponseTimeoutMs(2000);

        MvcResult result = startStream();
        awaitChunks(controller, 2);
        awaitValidations(validations, 2);
        int emittedBeforeActiveCheck = controller.emitted.get();
        Thread.sleep(300L);
        assertFalse(controller.cancelled.get(),
            "持续有效且复核在总预算内时不应提前切断流");
        assertTrue(controller.emitted.get() > emittedBeforeActiveCheck);
        active.set(false);
        awaitCancellation(controller);
        int emittedAtCancellation = controller.emitted.get();
        Thread.sleep(300L);
        assertEquals(emittedAtCancellation, controller.emitted.get(),
            "撤权取消后不应继续向下游发送 chunk");
        assertEquals(0, controller.emittedAfterCancel.get(),
            "上游取消后不应再产生输出");
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test
    void hangingNativeMappingValidationFailsClosedWithinBudget() throws Exception {
        CompletableFuture<Void> validationStarted = new CompletableFuture<>();
        CompletableFuture<Void> releaseValidation = new CompletableFuture<>();
        when(centerClient.status(
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString()
        )).thenReturn(new IamSessionStatusResponse(
            true, "iws-auth-center", 42L, "center-user", 0L, null
        ));
        when(localSessionService.matchesMappedUser(anyString(), any())).thenAnswer(invocation -> {
            validationStarted.complete(null);
            try {
                releaseValidation.get(10L, TimeUnit.SECONDS);
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            catch (Exception ignored) {
                // 总预算超时后测试只验证失败关闭，不要求验证任务继续完成。
            }
            return true;
        });
        properties.setResponseTimeoutMs(250);

        MvcResult result = startStream();
        try {
            awaitChunks(controller, 2);
            validationStarted.get(3L, TimeUnit.SECONDS);
            assertTrue(validationStarted.isDone(),
                "本地映射查询应已进入验证链");
            long cancellationStarted = System.nanoTime();
            awaitCancellation(controller, Duration.ofSeconds(2));
            long elapsedMillis = Duration.ofNanos(
                System.nanoTime() - cancellationStarted
            ).toMillis();
            assertTrue(elapsedMillis < 1500L,
                "包含排队和本地映射查询的复核应在总预算附近失败关闭");
            int emittedAtCancellation = controller.emitted.get();
            Thread.sleep(300L);
            assertEquals(emittedAtCancellation, controller.emitted.get());
            mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        }
        finally {
            releaseValidation.complete(null);
        }
    }

    @Test
    void openApiStreamWithoutPersonHeaderKeepsNativeStreaming() throws Exception {
        MvcResult result = mvc.perform(post("/ai-isolated/openapi-stream")
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        awaitChunks(controller, 3);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertEquals(3, controller.emitted.get());
        verifyNoInteractions(centerClient);
    }

    private MvcResult startStream() throws Exception {
        return mvc.perform(post("/ai-isolated/stream")
                .header(AuthenticationInterceptor.AUTHENTICATION, TOKEN)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andReturn();
    }

    private void awaitCancellation(StreamingController controller) throws InterruptedException {
        awaitCancellation(controller, Duration.ofSeconds(4));
    }

    private void awaitCancellation(
        StreamingController controller,
        Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!controller.cancelled.get() && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue(controller.cancelled.get(), "撤权后应取消 Flux 上游订阅");
    }

    private void awaitValidations(AtomicInteger validations, int count)
        throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (validations.get() < count && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue(validations.get() >= count, "有效会话复核应持续执行");
    }

    private void awaitChunks(StreamingController controller, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (controller.emitted.get() < count && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue(controller.emitted.get() >= count, "可控流源应已产生 SSE chunk");
    }

    private void assertInstalledBeforeNativeEmitterHandler() {
        int revocationIndex = -1;
        int nativeEmitterIndex = -1;
        var handlers = handlerAdapter.getReturnValueHandlers();
        for (int i = 0; i < handlers.size(); i++) {
            if (handlers.get(i) instanceof IamAiStreamingRevocationHandler) {
                revocationIndex = i;
            }
            if (handlers.get(i).getClass().getSimpleName()
                .equals("ResponseBodyEmitterReturnValueHandler")) {
                nativeEmitterIndex = i;
            }
        }
        assertTrue(revocationIndex >= 0);
        assertTrue(revocationIndex < nativeEmitterIndex);
    }

    /**
     * 隔离 MVC 配置。
     */
    @Configuration
    @EnableWebMvc
    static class TestConfig {

        @Bean
        IamAiProperties properties() {
            IamAiProperties properties = new IamAiProperties();
            properties.setValidationIntervalSeconds(1);
            return properties;
        }

        @Bean
        IamAiCenterClient centerClient() {
            return mock(IamAiCenterClient.class);
        }

        @Bean
        IamAiLocalSessionService localSessionService() {
            return mock(IamAiLocalSessionService.class);
        }

        @Bean
        IamAiStreamCancellationRegistry cancellationRegistry() {
            return new IamAiStreamCancellationRegistry();
        }

        @Bean
        IamAiStreamingRevocationHandlerInstaller installer(
            IamAiCenterClient centerClient,
            IamAiLocalSessionService localSessionService,
            IamAiProperties properties,
            IamAiStreamCancellationRegistry cancellationRegistry
        ) {
            return new IamAiStreamingRevocationHandlerInstaller(
                centerClient,
                localSessionService,
                properties,
                cancellationRegistry
            );
        }

        @Bean
        RecordingResponseBodyAdvice recordingResponseBodyAdvice() {
            return new RecordingResponseBodyAdvice();
        }

        @Bean
        StreamingController streamingController() {
            return new StreamingController();
        }
    }

    /**
     * 记录 Flux 是否错误地进入 body advice。
     */
    @ControllerAdvice
    static class RecordingResponseBodyAdvice implements ResponseBodyAdvice<Object> {

        private final AtomicInteger fluxCount = new AtomicInteger();

        @Override
        public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType
        ) {
            return true;
        }

        @Override
        public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
        ) {
            if (body instanceof Flux<?>) {
                fluxCount.incrementAndGet();
            }
            return body;
        }

        void reset() {
            fluxCount.set(0);
        }
    }

    /**
     * 可控 Flux 流源，记录上游取消。
     */
    @RestController
    static class StreamingController {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean cancelObserved = new AtomicBoolean();
        private final AtomicInteger emitted = new AtomicInteger();
        private final AtomicInteger emittedAfterCancel = new AtomicInteger();

        @PostMapping(value = "/ai-isolated/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<String> stream() {
            return Flux.interval(Duration.ofMillis(100))
                .map(index -> {
                    if (cancelObserved.get()) {
                        emittedAfterCancel.incrementAndGet();
                    }
                    emitted.incrementAndGet();
                    return "chunk-" + index;
                })
                .doOnCancel(() -> {
                    cancelObserved.set(true);
                    cancelled.set(true);
                });
        }

        @PostMapping(value = "/ai-isolated/openapi-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<String> openApiStream() {
            return Flux.interval(Duration.ofMillis(20))
                .take(3)
                .map(index -> {
                    emitted.incrementAndGet();
                    return "openapi-chunk-" + index;
                });
        }

        void reset() {
            cancelled.set(false);
            cancelObserved.set(false);
            emitted.set(0);
            emittedAfterCancel.set(0);
        }
    }
}
