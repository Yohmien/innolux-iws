/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 提供库存当前读、位置更新、流水插入和幂等读取；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.change.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物料位置事实 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsMaterialChangeMapper {

    /**
     * 锁定并当前读库存行。
     *
     * @param inventoryId 库存 ID
     * @return 库存行或 null
     */
    WmsMaterialInventoryRow selectInventoryForUpdate(@Param("inventoryId") long inventoryId);

    /**
     * 读取目标储位。
     *
     * @param storageId 储位 ID
     * @return 储位行或 null
     */
    WmsMaterialStorageRow selectStorage(@Param("storageId") long storageId);

    /**
     * 按效果键锁定库存流水。
     *
     * @param idempotencyKey 幂等键
     * @return 流水行或 null
     */
    WmsMaterialFlowRow selectFlowByIdempotencyKeyForUpdate(
        @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 按预期版本更新库存当前位置。
     *
     * @return 影响行数
     */
    int updateInventoryLocation(
        @Param("inventoryId") long inventoryId,
        @Param("expectedVersion") int expectedVersion,
        @Param("storageId") long storageId,
        @Param("storageCode") String storageCode,
        @Param("zoneId") long zoneId,
        @Param("zoneCode") String zoneCode,
        @Param("actorId") Long actorId,
        @Param("occurredAt") LocalDateTime occurredAt
    );

    /**
     * 插入库存位置流水。
     *
     * @param row 完整流水
     * @return 影响行数
     */
    int insertFlow(WmsMaterialFlowInsertRow row);

    /**
     * 按库存读取唯一装载节点 ID。
     *
     * @param inventoryId 库存 ID
     * @return 装载节点 ID 或 null
     */
    Long selectLoadNodeIdByInventory(@Param("inventoryId") long inventoryId);

    /**
     * 读取指定分配锁的储位，用于复核消费目标一致。
     *
     * @param lockIds 分配锁 ID
     * @return 锁对应储位 ID
     */
    List<Long> selectAllocationLockStorageIds(@Param("lockIds") List<Long> lockIds);
}
