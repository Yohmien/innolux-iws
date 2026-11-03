/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 描述一次受控物料位置事实及可信业务身份；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.domain;

import java.time.LocalDateTime;

/**
 * 物料位置事实命令。调用者必须在已有事务中执行。
 *
 * @param effectKey 领域效果键
 * @param inputHash 规范化输入摘要
 * @param inventoryId 库存单元 ID
 * @param expectedVersion 锁定后复核的库存版本
 * @param targetStorageId 目标储位 ID
 * @param occurredAt 真实业务发生时间
 * @param reason 业务原因
 * @param evidenceRef 脱敏证据引用
 * @param sourceSystem 来源系统
 * @param requestId 请求 ID
 * @param businessType 父业务类型
 * @param businessName 父业务类型名称快照
 * @param businessId 父业务 ID
 * @param businessNo 父业务号
 * @param businessLineId 父业务明细 ID
 * @param rootBusinessType 根父业务类型
 * @param rootBusinessId 根父业务 ID
 * @param taskKey 任务键
 * @param planInstanceId 计划实例 ID
 * @param planCode 计划编码
 * @param planType 计划类型
 * @param definitionVersion 计划定义版本
 * @param eventId 计划事件 ID
 * @param attemptId 计划尝试 ID
 * @param actorId 受信实际操作者 ID
 * @param departmentId 受信实际操作部门 ID
 * @author YONGJIE.PENG
 */
public record WmsMaterialChangeCommand(
    String effectKey,
    String inputHash,
    long inventoryId,
    int expectedVersion,
    long targetStorageId,
    LocalDateTime occurredAt,
    String reason,
    String evidenceRef,
    String sourceSystem,
    String requestId,
    String businessType,
    String businessName,
    long businessId,
    String businessNo,
    Long businessLineId,
    String rootBusinessType,
    long rootBusinessId,
    String taskKey,
    Long planInstanceId,
    String planCode,
    String planType,
    String definitionVersion,
    Long eventId,
    Long attemptId,
    Long actorId,
    Long departmentId
) {
}
