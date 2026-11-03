/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司创建命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 公司创建命令。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsCompanyCreateDTO {

    @NotBlank(message = "公司编码不能为空")
    @Size(max = 64, message = "公司编码不能超过64个字符")
    private String companyCode;

    @NotBlank(message = "公司名称不能为空")
    @Size(max = 100, message = "公司名称不能超过100个字符")
    private String companyName;

    @Size(max = 500, message = "备注不能超过500个字符")
    private String remark;

    @NotBlank(message = "命令键不能为空")
    @Size(max = 64, message = "命令键不能超过64个字符")
    private String commandKey;
}
