/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 库区实体；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 库区实体。
 *
 * <p>{@code warehouseCode} 是库区表保留的旧仓别列，不是仓库主档编码；
 * 查询返回的权威仓库编码由 {@code wms_warehouse.warehouse_code} 解析。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_warehouse_zone")
public class WmsWarehouseZone extends BaseEntity {

    @TableId("zone_id")
    private Long zoneId;

    private String zoneCode;

    private String zoneName;

    private Integer zoneType;

    private String zoneKind;

    private String lifecycleStatus;

    private String plantCode;

    /**
     * 旧仓别字段，仅只读保留。
     */
    private String warehouseCode;

    private Long parentZoneId;

    private Long warehouseId;

    private Integer allowInbound;

    private Integer allowOutbound;

    private Integer status;

    private Integer revision;

    private Integer sortNo;

    @TableLogic
    private String delFlag;

    private String remark;
}
