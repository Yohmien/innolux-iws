/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆预检
 * @Version      : V1.0
 * @Description  : 返回装拆结构预检后的节点关系、根路径和受影响储位；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.domain;

import java.util.List;

/**
 * 装拆结构预检结果。
 *
 * <p>本结果只证明冻结输入在读取时能形成合法装载森林，不替代最终动作的容量、
 * 占用、活动锁和持久化复核。</p>
 *
 * @param effectKey 领域效果键
 * @param inputHash 冻结输入摘要
 * @param changes 各变更子节点的前后父级与根节点
 * @param beforeRootNodeIds 预检前全部根节点
 * @param afterRootNodeIds 预检后全部根节点
 * @param impactedStorageIds 受关系变化影响的物理储位
 */
public record WmsLoadChangeCheckResult(
    String effectKey,
    String inputHash,
    List<NodeChange> changes,
    List<Long> beforeRootNodeIds,
    List<Long> afterRootNodeIds,
    List<Long> impactedStorageIds
) {

    public WmsLoadChangeCheckResult {
        changes = List.copyOf(changes);
        beforeRootNodeIds = List.copyOf(beforeRootNodeIds);
        afterRootNodeIds = List.copyOf(afterRootNodeIds);
        impactedStorageIds = List.copyOf(impactedStorageIds);
    }

    /**
     * 单个子节点的结构变化。
     *
     * @param childNodeId 子节点 ID
     * @param beforeParentNodeId 预检前父节点，根节点为 null
     * @param afterParentNodeId 预检后父节点，将作为根节点时为 null
     * @param beforeRootNodeId 预检前根节点
     * @param afterRootNodeId 预检后根节点
     */
    public record NodeChange(
        long childNodeId,
        Long beforeParentNodeId,
        Long afterParentNodeId,
        long beforeRootNodeId,
        long afterRootNodeId
    ) {
    }
}
