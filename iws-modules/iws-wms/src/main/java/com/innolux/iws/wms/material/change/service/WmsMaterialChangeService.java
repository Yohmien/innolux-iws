/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 定义库存位置、流水和 trace 的调用者事务合同；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.service;

import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeCommand;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeResult;
import com.innolux.iws.wms.material.change.domain.WmsMaterialAllocationConsumption;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;

/**
 * 内部物料位置事实服务。
 *
 * @author YONGJIE.PENG
 */
public interface WmsMaterialChangeService {

    String ERROR_INVALID_COMMAND = "MATERIAL_CHANGE_INVALID_COMMAND";

    String ERROR_INVENTORY_NOT_FOUND = "MATERIAL_CHANGE_INVENTORY_NOT_FOUND";

    String ERROR_INVENTORY_VERSION_CONFLICT = "MATERIAL_CHANGE_INVENTORY_VERSION_CONFLICT";

    String ERROR_ALLOCATION_UNSUPPORTED = "MATERIAL_CHANGE_ALLOCATION_UNSUPPORTED";

    String ERROR_TARGET_STORAGE_INVALID = "MATERIAL_CHANGE_TARGET_STORAGE_INVALID";

    String ERROR_EFFECT_KEY_CONFLICT = "MATERIAL_CHANGE_EFFECT_KEY_CONFLICT";

    String ERROR_TRACE_MISSING = "MATERIAL_CHANGE_TRACE_MISSING";

    /**
     * 在调用者事务内提交库存位置、库存流水和完整物料 trace。
     *
     * @param command 物料位置命令
     * @return 已提交事实
     */
    WmsMaterialChangeResult apply(WmsMaterialChangeCommand command);

    /**
     * 在同一调用者事务内组合物料位置、trace、分配锁消费和装载占用。
     *
     * @param command 物料位置命令
     * @param loadChange 可空装载根位置变更
     * @param allocationConsumption 可空分配锁消费
     * @return 组合事实
     */
    WmsMaterialChangeResult apply(
        WmsMaterialChangeCommand command,
        WmsLoadChangeCommand loadChange,
        WmsMaterialAllocationConsumption allocationConsumption
    );
}
