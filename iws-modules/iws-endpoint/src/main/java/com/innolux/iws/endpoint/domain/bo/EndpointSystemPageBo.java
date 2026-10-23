/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 系统目录分页筛选；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.bo;

import lombok.Data;

/**
 * 系统目录分页筛选。
 */
@Data
public class EndpointSystemPageBo {

    /**
     * 系统编码模糊条件。
     */
    private String systemCode;

    /**
     * 中文名称模糊条件。
     */
    private String displayName;

    /**
     * 启停条件。
     */
    private Boolean enabled;
}
