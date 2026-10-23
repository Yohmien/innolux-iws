/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 系统目录写请求；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.bo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统目录写请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EndpointSystemBo extends EndpointWriteBo {

    /**
     * 稳定系统编码。
     */
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.:-]{0,63}$", message = "系统编码格式不正确")
    private String systemCode;

    /**
     * 中文展示名。
     */
    @Size(max = 128, message = "系统名称不能超过 128 个字符")
    private String displayName;

    /**
     * 说明。
     */
    @Size(max = 500, message = "系统说明不能超过 500 个字符")
    private String description;

    /**
     * 是否启用。
     */
    @NotNull(message = "启停状态不能为空")
    private Boolean enabled;
}
