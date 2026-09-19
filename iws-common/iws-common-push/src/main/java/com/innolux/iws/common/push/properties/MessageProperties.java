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

package com.innolux.iws.common.push.properties;

import lombok.Data;
import com.innolux.iws.common.push.enums.MessageTransportEnum;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一消息推送配置。
 *
 * @author YONGJIE.PENG
 */
@Data
@ConfigurationProperties("message")
public class MessageProperties {

    /**
     * 是否启用消息推送。
     */
    private Boolean enabled = true;

    /**
     * 传输方式：sse / websocket。
     */
    private String transport = MessageTransportEnum.SSE.getCode();

    /**
     * 统一访问路径。
     */
    private String path = "/resource/message";

    /**
     * WebSocket 允许的跨域来源。
     */
    private String[] allowedOrigins = {"*"};

    /**
     * SSE 连接超时时间，单位毫秒。
     */
    private long sseTimeout = 86_400_000L;

    /**
     * 本地连接心跳检测间隔，单位秒。
     */
    private long heartbeatInterval = 60L;

    /**
     * WebSocket 单次发送超时时间，单位毫秒。
     */
    private int webSocketSendTimeLimit = 10_000;

    /**
     * WebSocket 发送缓冲区大小。
     */
    private int webSocketBufferSizeLimit = 64_000;
}
