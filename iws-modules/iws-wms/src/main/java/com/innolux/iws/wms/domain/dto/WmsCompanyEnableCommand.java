/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司受控启停命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 公司受控启停命令。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsCompanyEnableCommand {

    @NotNull(message = "公司修订不能为空")
    @Positive(message = "公司修订必须为正数")
    private Long expectedRevision;

    @NotBlank(message = "命令键不能为空")
    @Size(max = 64, message = "命令键不能超过64个字符")
    private String commandKey;

    @NotBlank(message = "启停原因不能为空")
    @Size(max = 500, message = "启停原因不能超过500个字符")
    private String reason;
}
