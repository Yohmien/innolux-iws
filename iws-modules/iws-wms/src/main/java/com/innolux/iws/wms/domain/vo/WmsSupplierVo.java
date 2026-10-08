/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : WMS 供应商 VO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import com.innolux.iws.wms.domain.WmsSupplier;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * WMS 供应商 VO。
 *
 * @author YONGJIE.PENG
 */
@Data
@AutoMapper(target = WmsSupplier.class)
public class WmsSupplierVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long supplierId;

    private String supplierCode;

    private String supplierName;

    private String contactPerson;

    private String contactPhone;

    private String contactEmail;

    private String address;

    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
