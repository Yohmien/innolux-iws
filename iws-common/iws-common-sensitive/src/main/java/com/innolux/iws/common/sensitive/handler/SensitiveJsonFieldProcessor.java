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

package com.innolux.iws.common.sensitive.handler;

import cn.hutool.core.util.ObjectUtil;
import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.json.enhance.JsonEnhancementContext;
import com.innolux.iws.common.json.enhance.JsonFieldContext;
import com.innolux.iws.common.json.enhance.JsonFieldProcessor;
import com.innolux.iws.common.sensitive.annotation.Sensitive;
import com.innolux.iws.common.sensitive.core.SensitiveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;

/**
 * 响应脱敏处理器。
 */
@Slf4j
@Order(100)
public class SensitiveJsonFieldProcessor implements JsonFieldProcessor {

    @Autowired(required = false)
    private SensitiveService sensitiveService;

    /**
     * 判断字段是否声明了脱敏注解。
     *
     * @param fieldContext 字段上下文
     * @return 是否支持脱敏处理
     */
    @Override
    public boolean supports(JsonFieldContext fieldContext) {
        return fieldContext.getAnnotation(Sensitive.class) != null;
    }

    /**
     * 按字段脱敏策略处理字符串值。
     *
     * @param fieldContext 字段上下文
     * @param value        字段原始值
     * @param context      JSON 增强上下文
     * @return 脱敏后的字段值
     */
    @Override
    public Object process(JsonFieldContext fieldContext, Object value, JsonEnhancementContext context) {
        Sensitive sensitive = fieldContext.getAnnotation(Sensitive.class);
        if (sensitive == null || !(value instanceof String text)) {
            return value;
        }
        if (ObjectUtil.isNotNull(sensitiveService) && sensitiveService.isSensitive(sensitive.roleKey(), sensitive.perms())) {
            return sensitive.strategy().desensitizer().apply(text);
        }
        return text;
    }

}
