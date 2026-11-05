/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version      : V1.0
 * @Description  : 为原生 AI 远程分发提供按流隔离的 gRPC 取消传播扩展；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import com.aizuda.snail.ai.ClientInstanceManager;
import com.aizuda.snail.ai.feature.agent.chain.AgentChatContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * AI 原生分发链 gRPC 调用追踪切面。
 */
@Aspect
@Component
public class IamAiGrpcCancellationAspect {

    /**
     * 仅在 IAM 受管 HTTP 流订阅线程内替换本次上下文的目标通道，避免改动共享客户端节点。
     *
     * @param joinPoint 原生 LLM 分发调用
     * @return 原生调用结果
     * @throws Throwable 原生调用异常
     */
    @Around(
        "execution(public void com.aizuda.snail.ai.feature.agent.chain.LlmCallHandler.handle(..))"
    )
    public Object trackClientCall(ProceedingJoinPoint joinPoint) throws Throwable {
        IamAiStreamCancellationRegistry.Scope cancellationScope =
            IamAiStreamCancellationContext.current();
        if (cancellationScope == null || joinPoint.getArgs().length == 0
            || !(joinPoint.getArgs()[0] instanceof AgentChatContext context)) {
            return joinPoint.proceed();
        }

        ClientInstanceManager.ClientInstanceInfo original = context.getTargetClient();
        if (original == null || original.getChannel() == null) {
            return joinPoint.proceed();
        }

        ClientInstanceManager.ClientInstanceInfo isolated =
            new ClientInstanceManager.ClientInstanceInfo(
                original.getAppId(),
                original.getHostId(),
                original.getHostIp(),
                original.getGrpcPort()
            );
        isolated.setChannel(
            new IamAiTrackingManagedChannel(
                original.getChannel(),
                cancellationScope
            )
        );
        isolated.setMaxConcurrent(original.getMaxConcurrent());
        isolated.setActiveChats(original.getActiveChats());
        isolated.setSupportedProviders(original.getSupportedProviders());
        isolated.setLabels(original.getLabels());
        isolated.setLastHeartbeatTime(original.getLastHeartbeatTime());

        context.setTargetClient(isolated);
        try {
            return joinPoint.proceed();
        }
        finally {
            context.setTargetClient(original);
        }
    }
}
