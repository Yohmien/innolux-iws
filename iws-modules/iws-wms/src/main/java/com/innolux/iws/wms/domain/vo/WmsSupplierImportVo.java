/*
 * @Date         : 2026-09-24
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : WMS 供应商标准导入对象；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serial;
import java.io.Serializable;

/**
 * WMS 供应商标准导入对象。
 *
 * <p>表头名称必须与供应商标准页导入模板保持一致，导入时只接收这些
 * 标准字段，避免 Excel 任意列直通数据库。</p>
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsSupplierImportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 供应商编码。
     */
    @NotBlank(message = "供应商编码不能为空")
    @ExcelProperty("供应商编码")
    private String supplierCode;

    /**
     * 供应商名称。
     */
    @NotBlank(message = "供应商名称不能为空")
    @ExcelProperty("供应商名称")
    private String supplierName;

    /**
     * 联系人。
     */
    @ExcelProperty("联系人")
    private String contactPerson;

    /**
     * 联系电话。
     */
    @ExcelProperty("联系电话")
    private String contactPhone;

    /**
     * 联系邮箱。
     */
    @ExcelProperty("联系邮箱")
    private String contactEmail;

    /**
     * 地址。
     */
    @ExcelProperty("地址")
    private String address;

    /**
     * 状态：支持 1/0、启用/停用。
     */
    @ExcelProperty("状态")
    private String status;

    /**
     * 备注。
     */
    @ExcelProperty("备注")
    private String remark;
}
