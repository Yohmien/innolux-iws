/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录写结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.domain.vo;

import java.io.Serial;
import java.io.Serializable;

/**
 * 端点目录写结果。
 *
 * <p>重复提交同一命令时返回首次提交的版本结果。</p>
 *
 * @param objectType 对象类型
 * @param objectKey 对象稳定键
 * @param revision 写后对象版本
 * @param catalogRevision 写后目录版本
 * @param commandKey 命令键
 */
public record EndpointChangeResultVo(
    String objectType,
    String objectKey,
    String revision,
    String catalogRevision,
    String commandKey
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
