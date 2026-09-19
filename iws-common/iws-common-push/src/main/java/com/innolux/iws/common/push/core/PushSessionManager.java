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

package com.innolux.iws.common.push.core;

import com.innolux.iws.common.push.dto.PushDTO;
import com.innolux.iws.system.api.domain.PushPayloadDTO;

import java.util.function.Consumer;

/**
 * 统一推送会话管理器。
 *
 * @author YONGJIE.PENG
 */
public interface PushSessionManager {

    /**
     * 订阅消息通道
     * 注册消息消费者，用于监听并处理消息推送事件
     *
     * @param consumer 消息消费逻辑
     */
    void subscribeMessage(Consumer<PushDTO> consumer);

    /**
     * 发送消息给指定用户
     *
     * @param userId  目标用户ID
     * @param payload 消息体
     */
    void sendMessage(Long userId, PushPayloadDTO payload);

    /**
     * 全局广播消息（所有在线用户）
     *
     * @param payload 消息体
     */
    void sendMessage(PushPayloadDTO payload);

    /**
     * 批量发布消息给指定用户列表
     *
     * @param pushDTO 推送参数封装对象
     */
    void publishMessage(PushDTO pushDTO);

    /**
     * 全局广播消息（所有用户）
     *
     * @param payload 消息体
     */
    void publishAll(PushPayloadDTO payload);
}
