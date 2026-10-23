/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点地址解析状态；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

/**
 * 端点地址解析状态。
 */
public enum EndpointAddressStatus {

    /**
     * 系统、环境、地址和关联均启用。
     */
    ENABLED,

    /**
     * 系统、环境、地址或关联已停用。
     */
    DISABLED,

    /**
     * 环境不存在或目录中没有对应地址。
     */
    UNCONFIGURED,

    /**
     * 地址存在但尚未与系统关联。
     */
    UNLINKED,

    /**
     * 系统编码未登记。
     */
    UNREGISTERED,

    /**
     * 同一解析键出现多个地址或有效关联。
     */
    AMBIGUOUS
}
