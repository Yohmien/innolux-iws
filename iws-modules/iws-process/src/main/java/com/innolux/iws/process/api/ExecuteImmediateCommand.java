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
 * 即时计划同步执行命令。
 *
 * <p>所有字段都来自已冻结的即时业务合同。计划范围由服务端解析，调用方不能提交
 * scope、rootSet 或第二条业务输入通道。</p>
 *
 * @param scheduleId 业务调度 ID
 * @param planType 计划类型
 * @param businessType 业务类型
 * @param businessId 业务标识
 * @param taskKey 业务任务唯一键
 * @param inputHash 输入摘要
 * @param commandKey 命令幂等键
 */
public record ExecuteImmediateCommand(long scheduleId, String planType, String businessType, String businessId,
                                      String taskKey, String inputHash, String commandKey) {
}
