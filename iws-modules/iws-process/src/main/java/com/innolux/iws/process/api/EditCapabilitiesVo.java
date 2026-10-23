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
 * 草稿编辑能力。
 *
 * @param versionId 版本 ID
 * @param revision 聚合版本
 * @param editable 是否可编辑
 * @param readOnlyReason 只读原因
 * @param lockedFields 锁定字段
 * @param fields 基础字段级编辑元数据
 * @param events 事件级代码绑定元数据
 */
public record EditCapabilitiesVo(String versionId, String revision, boolean editable, String readOnlyReason,
                                 java.util.List<String> lockedFields,
                                 java.util.List<FieldCapabilityVo> fields,
                                 java.util.List<EventEditCapabilityVo> events) {
}
