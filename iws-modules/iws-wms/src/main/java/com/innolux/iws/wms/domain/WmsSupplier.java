/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : WMS 供应商实体；
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
 * WMS 供应商实体。
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_supplier")
public class WmsSupplier extends BaseEntity {

    /**
     * 供应商主键。
     */
    @TableId(value = "supplier_id")
    private Long supplierId;

    /**
     * 供应商编码。
     */
    private String supplierCode;

    /**
     * 供应商名称。
     */
    private String supplierName;

    /**
     * 联系人。
     */
    private String contactPerson;

    /**
     * 联系电话。
     */
    private String contactPhone;

    /**
     * 联系邮箱。
     */
    private String contactEmail;

    /**
     * 地址。
     */
    private String address;

    /**
     * 状态：0停用，1启用。
     */
    private Integer status;

    /**
     * 乐观锁版本号。
     */
    private Integer version;

    /**
     * 删除标志：0存在，1删除。
     */
    @TableLogic
    private String delFlag;

    /**
     * 备注。
     */
    private String remark;
}
