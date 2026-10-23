/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件任务参数读取与校验；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.param;

import com.aizuda.snailjob.common.core.util.JsonUtil;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 SnailJob 参数中读取必填正整数。
 *
 * <p>任务 ID、批次和预算均来自已发布配置，缺失或非正数时拒绝执行，不设置业务默认值。</p>
 */
public final class ProcessJobParameters {

    private ProcessJobParameters() {
    }

    /**
     * 将 SnailJob 参数转换为字符串键映射。
     *
     * @param rawParams SnailJob 原始参数
     * @return 参数映射
     */
    public static Map<String, Object> asMap(Object rawParams) {
        if (rawParams == null) {
            throw new IllegalArgumentException("缺少计划事件任务参数");
        }
        if (rawParams instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        if (rawParams instanceof String text) {
            if (text.isBlank()) {
                throw new IllegalArgumentException("计划事件任务参数为空");
            }
            return parse(text);
        }
        return parse(JsonUtil.toJsonString(rawParams));
    }

    /**
     * 读取必填正整数长整型参数。
     *
     * @param params 参数映射
     * @param name 参数名
     * @return 正长整型值
     */
    public static long requiredPositiveLong(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("缺少任务参数 " + name);
        }
        long parsed;
        try {
            parsed = new BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("任务参数 " + name + " 必须为正整数", ex);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException("任务参数 " + name + " 必须为正整数");
        }
        return parsed;
    }

    /**
     * 读取必填正整数整型参数。
     *
     * @param params 参数映射
     * @param name 参数名
     * @return 正整型值
     */
    public static int requiredPositiveInt(Map<String, Object> params, String name) {
        long value = requiredPositiveLong(params, name);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("任务参数 " + name + " 超出整型范围");
        }
        return (int) value;
    }

    private static Map<String, Object> parse(String text) {
        try {
            Map<String, Object> parsed = JsonUtil.parseHashMap(text);
            if (parsed == null) {
                throw new IllegalArgumentException("计划事件任务参数不是 JSON 对象");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("计划事件任务参数不是合法 JSON 对象", ex);
        }
    }
}
