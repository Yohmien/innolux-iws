/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.1
 * @Description  : 实例运行查询与命令 DTO；
 * @ModifyRecord : V1.0 新建；
 *                 V1.1 增加实例动作准入元数据；
 */

package com.innolux.iws.process.api;

/**
 * 计划实例摘要。
 *
 * @param instanceId 实例 ID
 * @param planCode 计划编码
 * @param planName 计划名称
 * @param planRole 计划角色
 * @param planStatus 计划状态
 * @param mode 模式快照
 * @param taskKey 任务键
 * @param supervisorInstanceId 监督实例 ID
 * @param instanceVersion 实例版本
 * @param priority 实例优先级
 * @param exceptionSummary 异常摘要
 * @param systems 系统模式
 * @param tokens token
 * @param events 事件
 * @param allowedActions 可用动作
 * @param reasons 不可用原因
 */
public record InstanceSummaryVO(String instanceId, String planCode, String planName, String planRole,
                                String planStatus, String mode, String taskKey, String supervisorInstanceId,
                                String instanceVersion, Integer priority, String exceptionSummary,
                                java.util.List<InstanceSystemVO> systems,
                                java.util.List<TokenSummaryVO> tokens,
                                java.util.List<EventSummaryVO> events,
                                java.util.List<String> allowedActions,
                                java.util.Map<String, String> reasons) {
}
