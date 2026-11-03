/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P7 UNKNOWN 查证
 * @Version      : V1.0
 * @Description  : 按库存 visibility 与逐行数据权限读取待查证分配效果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.allocation.effect.investigation.mapper;

import com.innolux.iws.common.mybatis.annotation.DataColumn;
import com.innolux.iws.common.mybatis.annotation.DataPermission;
import com.innolux.iws.wms.inventory.query.WmsInventoryReadScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * UNKNOWN 分配效果只读 Mapper。
 */
public interface WmsAllocationEffectUnknownMapper {

    @DataPermission({
        @DataColumn(key = "deptName", value = "lock_row.create_dept"),
        @DataColumn(key = "userName", value = "lock_row.create_by")
    })
    List<WmsAllocationEffectUnknownRow> selectUnknown(
        @Param("businessType") String businessType,
        @Param("businessId") Long businessId,
        @Param("taskKey") String taskKey,
        @Param("limit") int limit,
        @Param("scope") WmsInventoryReadScope scope
    );
}
