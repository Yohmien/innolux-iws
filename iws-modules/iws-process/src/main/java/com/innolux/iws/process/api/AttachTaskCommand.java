/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件公开命令与结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 挂载业务任务命令。
 *
 * @param scheduleId 业务调度 ID
 * @param planType 计划类型
 * @param businessType 业务类型
 * @param businessId 业务标识
 * @param taskKey 业务任务唯一键
 * @param holdStart 是否准入前挂起
 * @param inputHash 关键输入摘要
 * @param rootSet 请求起点集合，空则使用定义全部起点
 * @param commandKey 命令幂等键
 */
public record AttachTaskCommand(long scheduleId, String planType, String businessType, String businessId,
                                String taskKey, boolean holdStart, String inputHash, java.util.List<String> rootSet,
                                String commandKey) {
}
