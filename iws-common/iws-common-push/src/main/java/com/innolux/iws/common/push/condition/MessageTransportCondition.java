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

package com.innolux.iws.common.push.condition;

import com.innolux.iws.common.push.annotation.ConditionalOnMessageTransport;
import com.innolux.iws.common.push.enums.MessageTransportEnum;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * 消息推送传输方式条件判断。
 *
 * @author YONGJIE.PENG
 */
public class MessageTransportCondition implements Condition {

    /**
     * 判断当前消息推送配置是否匹配注解声明的传输方式。
     *
     * @param context  条件上下文
     * @param metadata 注解元数据
     * @return 是否匹配
     */
    @Override
    public boolean matches(@NonNull ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnMessageTransport.class.getName());
        if (attributes == null) {
            return true;
        }

        Boolean enabled = context.getEnvironment().getProperty("message.enabled", Boolean.class, true);
        String transport = context.getEnvironment().getProperty("message.transport", MessageTransportEnum.SSE.getCode());
        String expected = (String) attributes.get("value");
        return enabled && expected.equalsIgnoreCase(transport);
    }

}
