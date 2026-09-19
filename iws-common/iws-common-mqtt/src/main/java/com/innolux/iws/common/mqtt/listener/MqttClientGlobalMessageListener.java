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

package com.innolux.iws.common.mqtt.listener;

import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.net.core.ChannelContext;
import org.dromara.mica.mqtt.codec.message.MqttPublishMessage;
import org.dromara.mica.mqtt.core.client.IMqttClientGlobalMessageListener;

import java.nio.charset.StandardCharsets;

/**
 * 全局消息监听，可以监听到所有订阅消息
 *
 * @author YONGJIE.PENG
 */
@Slf4j
public class MqttClientGlobalMessageListener implements IMqttClientGlobalMessageListener {

    /**
     * 接收并记录全局 MQTT 消息。
     *
     * @param context 通道上下文
     * @param topic   主题
     * @param message MQTT 消息对象
     * @param payload 消息载荷
     */
    @Override
    public void onMessage(ChannelContext context, String topic, MqttPublishMessage message, byte[] payload) {
        log.info("MqttGlobalMessageEvent => topic: {}, msg: {}", topic, new String(payload, StandardCharsets.UTF_8));
    }

}
