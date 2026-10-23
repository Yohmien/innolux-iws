/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 系统地址解析结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统地址解析结果。
 *
 * @param status 解析状态
 * @param address 仅在 ENABLED 时返回地址快照
 * @param message 非敏感且可展示的解析说明
 */
public record EndpointAddressResolution(
    EndpointAddressStatus status,
    EndpointAddressSnapshot address,
    String message
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否解析到唯一启用的地址。
     *
     * @return true 表示可发送
     */
    public boolean resolved() {
        return status == EndpointAddressStatus.ENABLED && address != null;
    }
}
