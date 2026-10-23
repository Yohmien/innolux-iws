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
 * 单事件及其关联边保存参数。
 *
 * @param event 事件定义
 * @param edges 与本事件相关的边
 * @param expectedVersion 聚合版本
 * @param catalogRevision 目录版本
 */
public record DefinitionEventPatchBo(DefinitionEventBo event, java.util.List<DefinitionEdgeBo> edges,
                                     Long expectedVersion, String catalogRevision) {
}
