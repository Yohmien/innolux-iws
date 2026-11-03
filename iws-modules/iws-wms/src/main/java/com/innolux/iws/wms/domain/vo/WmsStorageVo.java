/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 储位只读视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储位只读视图。
 *
 * <p>只返回已能由 {@code warehouse_id} 和父库区一致解析的储位；地址字段保留可空语义。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsStorageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long storageId;

    private Long companyId;

    private Long warehouseId;

    private String warehouseCode;

    private String warehouseName;

    private Long zoneId;

    private String zoneCode;

    private String zoneName;

    private String storageCode;

    private Integer storageType;

    private Integer heightType;

    private String heightDesc;

    private Integer rowNo;

    private Integer colNo;

    private Integer levelNo;

    private BigDecimal xCoordinate;

    private BigDecimal yCoordinate;

    private String portCode;

    private String wcsPointCode;

    private Integer status;

    private Integer lockStatus;

    private Integer disableStatus;

    private String lifecycleStatus;

    private Integer version;

    private String remark;

    private String aisleText;

    private String rowText;

    private String levelText;

    private String columnText;

    private String depthText;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
