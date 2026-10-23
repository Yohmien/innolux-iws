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
 * 状态定义保存参数。
 *
 * @param stateCode 状态编码
 * @param displayName 中文名
 * @param stateKind 状态类别
 * @param validatorKey 校验器键
 * @param validatorVersion 校验器版本
 * @param conditionDescription 条件说明
 * @param terminal 是否终点
 * @param sortOrder 展示顺序
 */
public record DefinitionStateBo(String stateCode, String displayName, String stateKind, String validatorKey,
                                String validatorVersion, String conditionDescription, Boolean terminal,
                                Integer sortOrder) {
}
