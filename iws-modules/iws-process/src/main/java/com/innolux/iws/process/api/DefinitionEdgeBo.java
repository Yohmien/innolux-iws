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
 * 事件边保存参数。
 *
 * @param predecessorEventCode 前驱事件
 * @param successorEventCode 后继事件
 * @param branchKey 分支键
 * @param branchKind 分支类型
 * @param conditionKey 条件键
 * @param conditionVersion 条件版本
 * @param defaultBranch 是否默认分支
 * @param joinCode 汇合编码
 * @param priority 优先级
 */
public record DefinitionEdgeBo(String predecessorEventCode, String successorEventCode, String branchKey,
                               String branchKind, String conditionKey, String conditionVersion,
                               Boolean defaultBranch, String joinCode, Integer priority) {
}
