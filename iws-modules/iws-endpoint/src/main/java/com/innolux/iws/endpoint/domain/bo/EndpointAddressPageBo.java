/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 共享地址分页筛选；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.bo;

import lombok.Data;

/**
 * 共享地址分页筛选。
 */
@Data
public class EndpointAddressPageBo {

    /**
     * 环境编码过滤。
     */
    private String environmentCode;

    /**
     * 关联系统编码过滤。
     */
    private String systemCode;

    /**
     * 地址引用模糊条件。
     */
    private String endpointRef;

    /**
     * 主机模糊条件。
     */
    private String host;

    /**
     * 启停条件。
     */
    private Boolean enabled;
}
