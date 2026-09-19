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

package com.innolux.iws.system.api;

import com.innolux.iws.system.api.domain.PushPayloadDTO;

import java.util.List;

/**
 * 通用 消息服务
 *
 * @author YONGJIE.PENG
 */
public interface MessageService {

    /**
     * 发送指定用户文本消息
     *
     * @param userId  目标用户ID
     * @param message 文本消息内容
     */
    void sendMessage(Long userId, String message);

    /**
     * 全局广播文本消息
     *
     * @param message 文本消息内容
     */
    void sendMessage(String message);

    /**
     * 发送指定用户自定义消息体
     *
     * @param userId  目标用户ID
     * @param payload 消息推送体
     */
    void sendMessage(Long userId, PushPayloadDTO payload);

    /**
     * 全局广播自定义消息体
     *
     * @param payload 消息推送体
     */
    void sendMessage(PushPayloadDTO payload);

    /**
     * 批量发布消息给指定用户列表
     *
     * @param userIds 用户ID集合
     * @param payload 消息推送体
     */
    void publishMessage(List<Long> userIds, PushPayloadDTO payload);

    /**
     * 发布全局广播文本消息
     *
     * @param message 文本消息内容
     */
    void publishAll(String message);

    /**
     * 发布全局广播自定义消息体
     *
     * @param payload 消息推送体
     */
    void publishAll(PushPayloadDTO payload);

}
