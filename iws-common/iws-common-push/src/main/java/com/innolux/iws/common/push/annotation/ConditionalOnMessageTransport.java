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

package com.innolux.iws.common.push.annotation;

import com.innolux.iws.common.push.condition.MessageTransportCondition;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.*;

/**
 * 按消息推送传输方式启用组件。
 *
 * @author YONGJIE.PENG
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(MessageTransportCondition.class)
public @interface ConditionalOnMessageTransport {

    /**
     * 传输方式：sse / websocket。
     */
    String value();

}
