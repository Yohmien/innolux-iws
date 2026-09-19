/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.push.config;

import com.innolux.iws.common.push.annotation.ConditionalOnMessageTransport;
import com.innolux.iws.common.push.controller.SseController;
import com.innolux.iws.common.push.core.SseEmitterSessionManager;
import com.innolux.iws.common.push.listener.MessageTopicListener;
import com.innolux.iws.common.push.properties.MessageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE 消息推送自动装配。
 *
 * @author YONGJIE.PENG
 */
@AutoConfiguration(after = MessageAutoConfiguration.class)
@ConditionalOnMessageTransport("sse")
public class MessageSseConfiguration {

    /**
     * 注册 SSE 会话管理器
     * 负责管理用户 SSE 连接、消息发送、会话清理
     *
     * @return SseEmitterSessionManager 实例
     */
    @Bean
    public SseEmitterSessionManager sseEmitterManager(ScheduledExecutorService scheduledExecutorService,
                                                      MessageProperties messageProperties) {
        return new SseEmitterSessionManager(scheduledExecutorService, messageProperties);
    }

    /**
     * 注册消息主题监听器
     * 监听 Redis 全局消息，用于集群环境下的消息分发
     *
     * @param manager SSE 会话管理器
     * @return MessageTopicListener 实例
     */
    @Bean
    public MessageTopicListener messageTopicListener(SseEmitterSessionManager manager) {
        return new MessageTopicListener(manager);
    }

    /**
     * 注册 SSE 控制器
     * 提供前端建立 SSE 连接的接口
     *
     * @param manager SSE 会话管理器
     * @return SseController 实例
     */
    @Bean
    public SseController sseController(SseEmitterSessionManager manager) {
        return new SseController(manager);
    }
}
