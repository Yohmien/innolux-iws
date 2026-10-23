/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 事件代码绑定编辑元数据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 事件编辑能力。
 *
 * @param eventCode 事件编码
 * @param editPolicy 动作编辑策略
 * @param graphEditPolicy 图编辑策略
 * @param editable 动作字段是否可编辑
 * @param graphEditable 源/目标状态与边是否可编辑
 * @param deletable 是否可删除
 * @param lockedFields 锁定字段
 * @param reason 受限原因
 */
public record EventEditCapabilityVo(String eventCode, String editPolicy, String graphEditPolicy,
                                    boolean editable, boolean graphEditable, boolean deletable,
                                    java.util.List<String> lockedFields, String reason) {
}
