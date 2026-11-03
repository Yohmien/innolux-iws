/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆事实
 * @Version      : V1.0
 * @Description  : 描述一个装载子节点的当前父级和目标根位置变化；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.domain;

/**
 * 一个装载关系变更。
 *
 * @param childNodeId 装载子节点 ID
 * @param expectedParentNodeId 当前父节点 ID，根节点为 null
 * @param expectedNodeRevision 当前节点修订号
 * @param targetParentNodeId 目标父节点 ID，成为根节点时为 null
 * @param targetLocationKind 成为根节点时的位置类型
 * @param targetStorageId 成为储位根节点时的物理储位 ID
 */
public record WmsLoadMutation(
    long childNodeId,
    Long expectedParentNodeId,
    int expectedNodeRevision,
    Long targetParentNodeId,
    String targetLocationKind,
    Long targetStorageId
) {
}
