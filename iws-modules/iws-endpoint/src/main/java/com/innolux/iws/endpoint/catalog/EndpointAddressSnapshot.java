/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 只读共享地址快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

import java.io.Serial;
import java.io.Serializable;

/**
 * 只读共享地址快照。
 *
 * @param addressId 字符串地址主键
 * @param environmentCode 环境编码
 * @param endpointRef 环境内稳定地址引用
 * @param protocol http 或 https
 * @param host 域名、IPv4 或 IPv6
 * @param port 端口，空表示协议默认端口
 * @param basePath 规范相对基础路径
 * @param enabled 是否启用
 * @param revision 地址对象版本
 * @param credentialRef 凭证引用标识，不包含凭证值
 */
public record EndpointAddressSnapshot(
    String addressId,
    String environmentCode,
    String endpointRef,
    String protocol,
    String host,
    Integer port,
    String basePath,
    boolean enabled,
    String revision,
    String credentialRef
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 获取规范化基础 URI。
     *
     * <p>IPv6 主机自动添加方括号；不会追加业务接口路径。</p>
     *
     * @return 基础 URI
     */
    public String baseUri() {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        String portPart = port == null ? "" : ":" + port;
        return protocol + "://" + authority + portPart + (basePath == null ? "" : basePath);
    }

    /**
     * 判断是否配置了凭证引用。
     *
     * @return true 表示已配置引用
     */
    public boolean hasCredential() {
        return credentialRef != null && !credentialRef.isBlank();
    }
}
