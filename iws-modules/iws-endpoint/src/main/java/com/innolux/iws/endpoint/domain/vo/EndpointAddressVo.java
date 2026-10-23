/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 共享环境地址展示对象；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 共享环境地址展示对象。
 */
@Data
public class EndpointAddressVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String addressId;

    private String environmentCode;

    private String endpointRef;

    private String protocol;

    private String host;

    private Integer port;

    private String basePath;

    private String baseUri;

    private Boolean enabled;

    private String revision;

    /**
     * 凭证引用标识；不包含凭证值。
     */
    private String credentialRef;

    /**
     * 是否已配置凭证引用。
     */
    private Boolean hasCredential;

    private List<String> systemCodes;

    private LocalDateTime updateTime;
}
