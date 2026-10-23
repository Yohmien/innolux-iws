/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 命令请求包装；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 业务调度保存请求。
 *
 * @param schedule 业务调度参数
 * @param expectedRevision 期望版本，新增为 null
 * @param commandKey 命令键
 * @param reason 原因
 */
public record ScheduleSaveRequest(BusinessScheduleBo schedule, Long expectedRevision, String commandKey,
                                  String reason) {
}
