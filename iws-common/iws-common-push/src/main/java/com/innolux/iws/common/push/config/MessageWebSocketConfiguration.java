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
import com.innolux.iws.common.push.core.WebSocketSessionManager;
import com.innolux.iws.common.push.handler.PlusWebSocketHandler;
import com.innolux.iws.common.push.interceptor.PlusWebSocketInterceptor;
import com.innolux.iws.common.push.listener.MessageTopicListener;
import com.innolux.iws.common.push.properties.MessageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.concurrent.ScheduledExecutorService;

/**
 * WebSocket 消息推送自动装配。
 *
 * @author YONGJIE.PENG
 */
@EnableWebSocket
@AutoConfiguration(after = MessageAutoConfiguration.class)
@ConditionalOnMessageTransport("websocket")
public class MessageWebSocketConfiguration {

    /**
     * WebSocket 配置注册
     * 配置连接路径、拦截器、跨域
     */
    @Bean
    public WebSocketConfigurer webSocketConfigurer(HandshakeInterceptor handshakeInterceptor,
                                                   WebSocketHandler webSocketHandler,
                                                   MessageProperties messageProperties) {
        return registry -> registry
            .addHandler(webSocketHandler, messageProperties.getPath())
            .addInterceptors(handshakeInterceptor)
            .setAllowedOrigins(messageProperties.getAllowedOrigins());
    }

    /**
     * WebSocket 会话管理器
     * 负责连接管理、消息发送、定时清理失效会话
     */
    @Bean
    public WebSocketSessionManager webSocketSessionManager(ScheduledExecutorService scheduledExecutorService,
                                                           MessageProperties messageProperties) {
        return new WebSocketSessionManager(scheduledExecutorService, messageProperties);
    }

    /**
     * WebSocket 握手拦截器
     * 建立连接前做登录校验、客户端ID校验
     */
    @Bean
    public HandshakeInterceptor handshakeInterceptor() {
        return new PlusWebSocketInterceptor();
    }

    /**
     * WebSocket 消息处理器
     * 处理连接、消息、心跳、断开、异常等事件
     */
    @Bean
    public WebSocketHandler webSocketHandler(WebSocketSessionManager webSocketSessionManager,
                                             MessageProperties messageProperties) {
        return new PlusWebSocketHandler(webSocketSessionManager, messageProperties);
    }

    /**
     * 消息主题监听器
     * 订阅 Redis 消息，实现集群环境下的消息分发
     */
    @Bean
    public MessageTopicListener messageTopicListener(WebSocketSessionManager webSocketSessionManager) {
        return new MessageTopicListener(webSocketSessionManager);
    }
}
