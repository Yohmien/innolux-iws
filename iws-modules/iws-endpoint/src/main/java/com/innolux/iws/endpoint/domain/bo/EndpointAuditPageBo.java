/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点配置审计分页筛选；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.bo;

import lombok.Data;

/**
 * 端点配置审计分页筛选。
 */
@Data
public class EndpointAuditPageBo {

    /**
     * 对象类型。
     */
    private String objectType;

    /**
     * 对象键模糊条件。
     */
    private String objectKey;

    /**
     * 环境编码过滤。
     */
    private String environmentCode;

    /**
     * 操作者名称模糊条件。
     */
    private String operatorName;

    /**
     * 命令键精确条件。
     */
    private String commandKey;
}
