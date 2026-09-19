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

package com.innolux.iws.common.push.constant;

/**
 * 模块通用消息常量定义。
 *
 * @author YONGJIE.PENG
 */
public interface MessageConstants {

    /**
     * 登录用户信息
     */
    String LOGIN_USER_KEY = "loginUser";

    /**
     * 登录令牌
     */
    String LOGIN_TOKEN_KEY = "token";

    /**
     * 全局消息订阅主题
     */
    String MESSAGE_TOPIC = "global:message";

    /**
     * 心跳请求标识
     */
    String PING = "ping";

    /**
     * 心跳响应标识
     */
    String PONG = "pong";

    /**
     * 同一 token 的新连接替换旧连接时发送给旧连接的控制消息。
     */
    String KICKED = "kicked";
}
