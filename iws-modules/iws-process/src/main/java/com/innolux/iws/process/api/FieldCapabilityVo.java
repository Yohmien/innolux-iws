/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 基础字段编辑元数据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 基础字段编辑能力。
 *
 * @param field 字段名
 * @param editable 是否可编辑
 * @param editPolicy EDITABLE/REGISTERED_ONLY/CODE_FIXED
 * @param reason 只读或受限原因
 */
public record FieldCapabilityVo(String field, boolean editable, String editPolicy, String reason) {
}
