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

package com.innolux.iws.common.core.validate.dicts;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import com.innolux.iws.common.core.service.DictService;
import com.innolux.iws.common.core.utils.SpringUtils;
import com.innolux.iws.common.core.utils.StringUtils;

/**
 * 自定义字典值校验器
 *
 * @author YONGJIE.PENG
 */
public class DictPatternValidator implements ConstraintValidator<DictPattern, String> {

    /**
     * 字典类型
     */
    private String dictType;

    /**
     * 分隔符
     */
    private String separator = ",";

    /**
     * 初始化校验器，提取注解上的字典类型
     *
     * @param annotation 注解实例
     */
    @Override
    public void initialize(DictPattern annotation) {
        this.dictType = annotation.dictType();
        if (StringUtils.isNotBlank(annotation.separator())) {
            this.separator = annotation.separator();
        }
    }

    /**
     * 校验字段值是否为指定字典类型中的合法值
     *
     * @param value   被校验的字段值
     * @param context 校验上下文（可用于构建错误信息）
     * @return true 表示校验通过（合法字典值），false 表示不通过
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (StringUtils.isBlank(value)) {
            return true;
        }
        if (StringUtils.isBlank(dictType)) {
            return false;
        }
        String dictLabel = SpringUtils.getBean(DictService.class).getDictLabel(dictType, value, separator);
        return StringUtils.isNotBlank(dictLabel);
    }

}
