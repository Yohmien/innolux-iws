/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 定义不可变物料或载具装载节点及其唯一物理身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

/**
 * 装载节点。MATERIAL 恰有 inventoryId，CARRIER 恰有 containerId；
 * 节点 ID 与各自物理身份由调用方在受信快照中提供。
 */
public record LoadNode(long id, Type type, Long inventoryId, Long containerId) {
    public LoadNode {
        if (id <= 0 || type == null || (inventoryId == null) == (containerId == null)
            || !positiveOrNull(inventoryId) || !positiveOrNull(containerId)
            || (type == Type.MATERIAL && containerId != null)
            || (type == Type.CARRIER && inventoryId != null)) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_NODE_INVALID);
        }
    }

    private static boolean positiveOrNull(Long value) {
        return value == null || value > 0;
    }

    public enum Type {
        MATERIAL,
        CARRIER
    }
}
