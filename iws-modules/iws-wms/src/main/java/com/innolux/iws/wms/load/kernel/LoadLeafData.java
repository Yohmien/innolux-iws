/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 汇总一个有效物料叶的不可变属性事实和实存数量；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 由受信适配器提供的物料叶事实；每个属性名在同一叶内只能出现一次。 */
public record LoadLeafData(long nodeId, List<LoadAttributeFact> attributes, LoadQuantity quantity) {
    public LoadLeafData {
        if (nodeId <= 0 || attributes == null || quantity == null
            || attributes.stream().anyMatch(attribute -> attribute == null)) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_LEAF_INVALID);
        }
        Set<String> names = new HashSet<>();
        if (attributes.stream().anyMatch(attribute -> !names.add(attribute.name()))) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_DUPLICATE);
        }
        attributes = List.copyOf(attributes);
    }
}
