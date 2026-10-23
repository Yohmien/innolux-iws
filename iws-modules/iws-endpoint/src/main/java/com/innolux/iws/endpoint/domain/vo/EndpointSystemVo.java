/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 系统目录展示对象；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统目录展示对象。
 */
@Data
public class EndpointSystemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String systemCode;

    private String displayName;

    private String description;

    private Boolean enabled;

    private String revision;

    private Integer addressCount;

    private List<EndpointAssociatedAddressVo> addresses;

    private LocalDateTime updateTime;
}
