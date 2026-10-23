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
 * 事件定义保存参数。
 *
 * @param eventCode 事件编码
 * @param displayName 中文名
 * @param eventRole 事件角色
 * @param eventDirection 方向
 * @param eventTypeKey 类型键
 * @param eventTypeVersion 类型版本
 * @param callContractKey 调用合同键
 * @param callContractVersion 调用合同版本
 * @param operationKind 动作类型
 * @param localActionKey 本地动作键
 * @param localActionVersion 本地动作版本
 * @param effectKey 逻辑效果键
 * @param localEffectKey 本地效果键
 * @param systemCode 政策系统
 * @param targetSystemCode 目标系统
 * @param endpointRef 地址引用
 * @param purpose 用途
 * @param completionContract 完成合同
 * @param sourceStateCode 源状态
 * @param targetStateCode 目标状态
 * @param enabled 是否启用
 * @param skipPolicy 跳过策略
 * @param skipContractKey 跳过合同
 * @param recoveryForEventCode 恢复来源事件
 * @param priority 优先级
 * @param editPolicy 编辑策略
 * @param graphEditPolicy 图编辑策略
 * @param lockedFields 锁定字段
 * @param remark 说明
 */
public record DefinitionEventBo(String eventCode, String displayName, String eventRole, String eventDirection,
                                String eventTypeKey, String eventTypeVersion, String callContractKey,
                                String callContractVersion, String operationKind, String localActionKey,
                                String localActionVersion, String effectKey, String localEffectKey, String systemCode,
                                String targetSystemCode, String endpointRef, String purpose,
                                String completionContract, String sourceStateCode, String targetStateCode,
                                Boolean enabled, String skipPolicy, String skipContractKey,
                                String recoveryForEventCode, Integer priority, String editPolicy,
                                String graphEditPolicy, String lockedFields, String remark) {
}
