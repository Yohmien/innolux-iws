/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version       : V1.2
 * @Description  : 在 AI Flux 流真正订阅前安装中心会话撤权检查；
 * @ModifyRecord : V1.0 新建；V1.1 改用 MVC 返回值处理器覆盖原生 SSE 管线；
 *                 V1.2 为完整复核链增加总预算，超时按撤权失败关闭；
 */

package com.innolux.iws.snailai.iam;

import com.aizuda.snail.ai.admin.interceptor.AuthenticationInterceptor;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 流式响应会话撤权检查。
 *
 * <p>Spring MVC 对 Flux 会优先使用
 * {@link ResponseBodyEmitterReturnValueHandler} 直接订阅响应式流，
 * 不会经过 {@code ResponseBodyAdvice}。因此这里必须在原生 emitter
 * handler 之前包装 Flux，才能让撤权同时取消上游订阅。</p>
 */
@RequiredArgsConstructor
public final class IamAiStreamingRevocationHandler implements HandlerMethodReturnValueHandler {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final ResponseBodyEmitterReturnValueHandler delegate;
    private final IamAiCenterClient centerClient;
    private final IamAiLocalSessionService localSessionService;
    private final IamAiProperties properties;
    private final IamAiStreamCancellationRegistry cancellationRegistry;

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        if (!Flux.class.isAssignableFrom(returnType.getParameterType())) {
            return false;
        }
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
            returnType.getMethod(),
            RequestMapping.class
        );
        if (mapping == null) {
            return false;
        }
        for (String produced : mapping.produces()) {
            MediaType mediaType;
            try {
                mediaType = MediaType.parseMediaType(produced);
            }
            catch (RuntimeException ignored) {
                continue;
            }
            if (mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                || mediaType.isCompatibleWith(NDJSON)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handleReturnValue(
        Object returnValue,
        MethodParameter returnType,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest
    ) throws Exception {
        Object body = returnValue;
        if (returnValue instanceof Flux<?> flux) {
            HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
            if (request != null) {
                body = wrap(flux, request);
            }
        }
        delegate.handleReturnValue(body, returnType, mavContainer, webRequest);
    }

    private Flux<?> wrap(Flux<?> flux, HttpServletRequest request) {
        String token = request.getHeader(AuthenticationInterceptor.AUTHENTICATION);
        if (token == null || token.isBlank()) {
            return flux;
        }

        final IamAiLocalSessionService.IamTokenClaims claims;
        try {
            claims = localSessionService.verify(token);
        }
        catch (RuntimeException e) {
            // 原生 AuthenticationInterceptor 负责输出具体认证错误；
            // 这里不能把无效 Token 静默降级成可长期输出的流。
            throw e;
        }

        long intervalSeconds = Math.max(1, properties.getValidationIntervalSeconds());
        Duration validationTimeout = Duration.ofMillis(
            Math.max(1, properties.getResponseTimeoutMs())
        );
        Flux<Boolean> revoked = Flux.interval(Duration.ofSeconds(intervalSeconds))
            .concatMap(ignored -> Mono.fromCallable(() -> !isActive(claims))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(validationTimeout)
                .onErrorReturn(true))
            .filter(Boolean::booleanValue);
        IamAiStreamCancellationRegistry.Scope cancellationScope =
            cancellationRegistry.open();
        Flux<Object> managed = Flux.create(sink -> {
            AtomicReference<Disposable> upstream = new AtomicReference<>();
            Runnable cancel = () -> {
                cancellationRegistry.cancel(cancellationScope);
                Disposable disposable = upstream.get();
                if (disposable != null) {
                    disposable.dispose();
                }
            };
            sink.onCancel(cancel::run);
            sink.onDispose(() -> cancellationRegistry.close(cancellationScope));
            try (IamAiStreamCancellationContext.Scope ignored =
                     IamAiStreamCancellationContext.open(cancellationScope)) {
                upstream.set(flux.subscribe(
                    sink::next,
                    error -> {
                        cancellationRegistry.close(cancellationScope);
                        sink.error(error);
                    },
                    () -> {
                        cancellationRegistry.close(cancellationScope);
                        sink.complete();
                    }
                ));
                if (sink.isCancelled()) {
                    cancel.run();
                }
            }
        }, FluxSink.OverflowStrategy.BUFFER);
        return managed.takeUntilOther(revoked);
    }

    private boolean isActive(IamAiLocalSessionService.IamTokenClaims claims) {
        try {
            IamSessionStatusResponse status = centerClient.status(
                claims.rootSid(),
                claims.frontendId(),
                claims.sessionId(),
                claims.sessionGeneration(),
                claims.terminal()
            );
            return status.active()
                && claims.subjectUserId().equals(status.userId())
                && localSessionService.matchesMappedUser(
                    status.username(),
                    claims.localUserId()
                );
        }
        catch (RuntimeException e) {
            // 中心不可达时失败关闭，让现有人连接在复核窗口内结束。
            return false;
        }
    }
}
