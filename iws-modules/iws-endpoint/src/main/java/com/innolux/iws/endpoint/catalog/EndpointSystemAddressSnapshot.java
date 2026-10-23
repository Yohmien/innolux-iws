/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 只读系统地址关联快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

import java.io.Serial;
import java.io.Serializable;

/**
 * 只读系统地址关联快照。
 *
 * @param systemCode 系统编码
 * @param addressId 字符串地址主键
 * @param enabled 关联是否启用
 * @param revision 关联版本
 */
public record EndpointSystemAddressSnapshot(
    String systemCode,
    String addressId,
    boolean enabled,
    String revision
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
