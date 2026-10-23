/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 共享环境地址写请求；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 共享环境地址写请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EndpointAddressBo extends EndpointWriteBo {

    /**
     * 环境编码。
     */
    @Size(max = 64, message = "环境编码不能超过 64 个字符")
    private String environmentCode;

    /**
     * 环境内稳定地址引用。
     */
    @Size(max = 64, message = "地址引用不能超过 64 个字符")
    private String endpointRef;

    /**
     * 协议：http 或 https。
     */
    @Size(max = 8, message = "协议不能超过 8 个字符")
    private String protocol;

    /**
     * 域名、IPv4 或 IPv6；不包含协议、端口和路径。
     */
    @Size(max = 253, message = "主机不能超过 253 个字符")
    private String host;

    /**
     * 端口；为空时使用协议默认端口。
     */
    @Min(value = 1, message = "端口不能小于 1")
    @Max(value = 65535, message = "端口不能大于 65535")
    private Integer port;

    /**
     * 规范相对基础路径。
     */
    @Size(max = 512, message = "基础路径不能超过 512 个字符")
    private String basePath;

    /**
     * 安全配置中的凭证引用标识，不承载凭证值。
     */
    @Size(max = 128, message = "凭证引用不能超过 128 个字符")
    private String credentialRef;

    /**
     * 是否启用。
     */
    @NotNull(message = "启停状态不能为空")
    private Boolean enabled;

    /**
     * 创建地址时同时关联的稳定系统编码。
     */
    private List<String> systemCodes;
}
