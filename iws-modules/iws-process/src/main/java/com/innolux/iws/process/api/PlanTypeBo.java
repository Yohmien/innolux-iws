/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划配置与查询 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 计划类型保存参数。
 *
 * @param planType 类型编码
 * @param planTypeName 类型中文名
 * @param priority 类型优先级
 * @param enabled 是否启用
 */
public record PlanTypeBo(String planType, String planTypeName, Integer priority, Boolean enabled) {
}
