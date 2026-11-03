/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司名称和备注修改命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 公司名称和备注修改命令。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsCompanyUpdateDTO {

    @NotNull(message = "公司ID不能为空")
    @Positive(message = "公司ID必须为正数")
    private Long companyId;

    @NotBlank(message = "公司名称不能为空")
    @Size(max = 100, message = "公司名称不能超过100个字符")
    private String companyName;

    @Size(max = 500, message = "备注不能超过500个字符")
    private String remark;

    @NotNull(message = "公司修订不能为空")
    @Positive(message = "公司修订必须为正数")
    private Long expectedRevision;

    @NotBlank(message = "命令键不能为空")
    @Size(max = 64, message = "命令键不能超过64个字符")
    private String commandKey;

    @NotBlank(message = "修改原因不能为空")
    @Size(max = 500, message = "修改原因不能超过500个字符")
    private String reason;

    /**
     * 请求是否显式提交了备注字段；供服务端区分“不变”和“清空”。
     */
    private boolean remarkProvided;
}
