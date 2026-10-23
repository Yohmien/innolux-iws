/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 系统已关联地址摘要；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统已关联地址摘要。
 */
@Data
public class EndpointAssociatedAddressVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String addressId;

    private String environmentCode;

    private String endpointRef;

    private String protocol;

    private String host;

    private Integer port;

    private String basePath;

    private Boolean enabled;

    private String revision;

    private String baseUri;

    private Boolean hasCredential;
}
