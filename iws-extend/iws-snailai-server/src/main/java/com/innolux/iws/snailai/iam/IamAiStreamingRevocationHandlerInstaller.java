/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version       : V1.1
 * @Description  : 将 AI Flux 撤权处理器安装到原生 SSE 返回值处理器之前；
 * @ModifyRecord : V1.0 新建；V1.1 新建 MVC 返回值处理器安装器；
 */

package com.innolux.iws.snailai.iam;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 RequestMappingHandlerAdapter 初始化后插入 Flux 撤权处理器。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class IamAiStreamingRevocationHandlerInstaller implements BeanPostProcessor {

    private final IamAiCenterClient centerClient;
    private final IamAiLocalSessionService localSessionService;
    private final IamAiProperties properties;
    private final IamAiStreamCancellationRegistry cancellationRegistry;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof RequestMappingHandlerAdapter adapter)) {
            return bean;
        }
        List<HandlerMethodReturnValueHandler> handlers = adapter.getReturnValueHandlers();
        if (handlers == null || handlers.isEmpty()) {
            return bean;
        }

        int emitterIndex = -1;
        for (int i = 0; i < handlers.size(); i++) {
            if (handlers.get(i) instanceof ResponseBodyEmitterReturnValueHandler) {
                emitterIndex = i;
                break;
            }
        }
        if (emitterIndex < 0) {
            return bean;
        }

        List<HandlerMethodReturnValueHandler> ordered = new ArrayList<>(handlers);
        ordered.add(
            emitterIndex,
            new IamAiStreamingRevocationHandler(
                (ResponseBodyEmitterReturnValueHandler) handlers.get(emitterIndex),
                centerClient,
                localSessionService,
                properties,
                cancellationRegistry
            )
        );
        adapter.setReturnValueHandlers(ordered);
        return bean;
    }
}
