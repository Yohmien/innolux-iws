/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 空间六层父链的唯一领域判定；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.domain;

/**
 * 空间父链规则。
 *
 * <p>公司包含仓库，物料库区直属仓库，设备库区直属物料库区，储位直属设备库区。
 * 同类不相嵌、不跳层；所有判定只接受稳定 ID 和当前数据库行。</p>
 */
public final class WmsSpaceHierarchy {

    public static final String MATERIAL = "MATERIAL";

    public static final String DEVICE = "DEVICE";

    private WmsSpaceHierarchy() {
    }

    /**
     * 判断库区父链是否符合六层模型。
     *
     * @param zoneKind          当前库区种类
     * @param parentZoneId      当前父库区 ID
     * @param parentZoneKind    父库区种类，无父时为空
     * @param parentWarehouseId 父库区所属仓库，无父时为空
     * @param warehouseId       当前库区所属仓库
     * @return true 表示合法
     */
    public static boolean validZoneParent(
        String zoneKind,
        Long parentZoneId,
        String parentZoneKind,
        Long parentWarehouseId,
        long warehouseId
    ) {
        if (MATERIAL.equals(zoneKind)) {
            return parentZoneId == null;
        }
        return DEVICE.equals(zoneKind)
            && parentZoneId != null
            && MATERIAL.equals(parentZoneKind)
            && parentWarehouseId != null
            && parentWarehouseId == warehouseId;
    }

    /**
     * 判断储位是否直属同仓设备库区。
     *
     * @param warehouseId       储位所属仓库
     * @param zoneWarehouseId   库区所属仓库
     * @param zoneKind          库区种类
     * @return true 表示合法
     */
    public static boolean validStorageParent(
        long warehouseId,
        Long zoneWarehouseId,
        String zoneKind
    ) {
        return zoneWarehouseId != null
            && zoneWarehouseId == warehouseId
            && DEVICE.equals(zoneKind);
    }

    /**
     * 校验库区种类的封闭枚举。
     *
     * @param zoneKind 库区种类
     * @return true 表示受支持
     */
    public static boolean supportsZoneKind(String zoneKind) {
        return MATERIAL.equals(zoneKind) || DEVICE.equals(zoneKind);
    }
}
