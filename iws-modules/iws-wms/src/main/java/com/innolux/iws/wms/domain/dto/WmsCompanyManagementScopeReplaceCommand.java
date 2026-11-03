/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : 公司管理角色范围全量替换命令；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 公司管理角色范围全量替换命令。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsCompanyManagementScopeReplaceCommand {

    @NotNull(message = "公司ID不能为空")
    @Positive(message = "公司ID必须为正数")
    private Long companyId;

    @NotNull(message = "角色集合不能为空，空集合请提交空数组")
    private List<@Valid @Positive(message = "角色ID必须为正数") Long> roleIds;

    @NotNull(message = "范围修订不能为空")
    @PositiveOrZero(message = "范围修订不能为负数")
    private Long expectedScopeRevision;

    @NotBlank(message = "命令键不能为空")
    @Size(max = 64, message = "命令键不能超过64个字符")
    private String commandKey;

    @NotBlank(message = "授权原因不能为空")
    @Size(max = 500, message = "授权原因不能超过500个字符")
    private String reason;
}
