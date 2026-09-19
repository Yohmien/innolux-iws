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

package com.innolux.iws.common.core.utils.regex;


import cn.hutool.core.util.ReUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.innolux.iws.common.core.constant.RegexConstants;

/**
 * 正则相关工具类
 *
 * @author YONGJIE.PENG
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RegexUtils extends ReUtil {

    /**
     * 从输入字符串中提取匹配的部分，如果没有匹配则返回默认值
     *
     * @param input        要提取的输入字符串
     * @param regex        用于匹配的正则表达式，可以使用 {@link RegexConstants} 中定义的常量
     * @param defaultInput 如果没有匹配时返回的默认值
     * @return 如果找到匹配的部分，则返回匹配的部分，否则返回默认值
     */
    public static String extractFromString(String input, String regex, String defaultInput) {
        try {
            String str = ReUtil.get(regex, input, 1);
            return str == null ? defaultInput : str;
        } catch (Exception e) {
            return defaultInput;
        }
    }

}
