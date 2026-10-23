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
 * 完整草稿保存请求。
 *
 * @param draft 草稿
 * @param expectedVersion 聚合版本
 * @param commandKey 命令键
 * @param reason 原因
 */
public record DraftSaveRequest(DefinitionDraftBo draft, long expectedVersion, String commandKey, String reason) {
}
