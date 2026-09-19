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

package com.innolux.iws.common.core.xss;

import cn.hutool.core.util.ReUtil;
import cn.hutool.http.HtmlUtil;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import com.innolux.iws.common.core.utils.StringUtils;

/**
 * 自定义xss校验注解实现
 *
 * @author YONGJIE.PENG
 */
public class XssValidator implements ConstraintValidator<Xss, String> {

    /**
     * 校验字符串是否包含 HTML 标签。
     *
     * @param value                      待校验值
     * @param constraintValidatorContext 校验上下文
     * @return true 校验通过 false 包含 HTML 标签
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext) {
        if (StringUtils.isBlank(value)) {
            return true;
        }
        return !ReUtil.contains(HtmlUtil.RE_HTML_MARK, value);
    }

}
