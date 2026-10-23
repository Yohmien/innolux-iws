/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 只读环境目录快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

import java.io.Serial;
import java.io.Serializable;

/**
 * 只读环境目录快照。
 *
 * @param environmentCode 稳定环境编码
 * @param displayName 中文展示名
 * @param enabled 是否启用
 * @param revision 环境对象版本
 */
public record EndpointEnvironmentSnapshot(
    String environmentCode,
    String displayName,
    boolean enabled,
    String revision
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
