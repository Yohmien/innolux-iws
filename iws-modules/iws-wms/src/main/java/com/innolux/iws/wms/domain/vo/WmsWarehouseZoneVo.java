/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 库区只读视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库区只读视图。
 *
 * <p>{@code warehouseCode}/{@code warehouseName} 来自仓库主档；
 * {@code legacyWarehouseCode} 才是库区表保留的旧仓别列。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsWarehouseZoneVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long zoneId;

    private Long companyId;

    private Long warehouseId;

    private String warehouseCode;

    private String warehouseName;

    private String legacyWarehouseCode;

    private String zoneCode;

    private String zoneName;

    private Integer zoneType;

    private String zoneKind;

    private String lifecycleStatus;

    private String plantCode;

    private Long parentZoneId;

    private Integer allowInbound;

    private Integer allowOutbound;

    private Integer status;

    private Integer revision;

    private Integer sortNo;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
