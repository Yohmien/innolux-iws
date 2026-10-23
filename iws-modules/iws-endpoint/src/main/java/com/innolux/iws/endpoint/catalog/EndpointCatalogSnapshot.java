/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录不可变只读快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 端点目录不可变只读快照。
 *
 * <p>调用方应先通过 {@link EndpointCatalogReader#lockSnapshot(String)} 锁定目录版本，
 * 再使用该快照解析目标，避免目录在事务中变化后继续使用旧快照。</p>
 *
 * @param catalogRevision 目录版本
 * @param systems 全部系统目录
 * @param environments 全部环境目录
 * @param addresses 全部共享地址
 * @param systemAddresses 全部系统地址关联
 */
public record EndpointCatalogSnapshot(
    String catalogRevision,
    List<EndpointSystemSnapshot> systems,
    List<EndpointEnvironmentSnapshot> environments,
    List<EndpointAddressSnapshot> addresses,
    List<EndpointSystemAddressSnapshot> systemAddresses
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 固化集合，保证快照不可变。
     */
    public EndpointCatalogSnapshot {
        systems = systems == null ? List.of() : List.copyOf(systems);
        environments = environments == null ? List.of() : List.copyOf(environments);
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        systemAddresses = systemAddresses == null ? List.of() : List.copyOf(systemAddresses);
    }

    /**
     * 解析指定环境、系统和地址引用。
     *
     * <p>解析只使用显式目录关联，不按主机或地址反推系统归属。</p>
     *
     * @param environmentCode 环境编码
     * @param systemCode 系统编码
     * @param endpointRef 地址引用
     * @return 含明确状态的解析结果
     */
    public EndpointAddressResolution resolveEndpoint(String environmentCode, String systemCode, String endpointRef) {
        EndpointSystemSnapshot system = systems.stream()
            .filter(item -> item.systemCode().equals(systemCode))
            .findFirst()
            .orElse(null);
        if (system == null) {
            return resolution(EndpointAddressStatus.UNREGISTERED, null, "系统未登记");
        }

        EndpointEnvironmentSnapshot environment = environments.stream()
            .filter(item -> item.environmentCode().equals(environmentCode))
            .findFirst()
            .orElse(null);
        if (environment == null) {
            return resolution(EndpointAddressStatus.UNCONFIGURED, null, "环境未配置");
        }

        List<EndpointAddressSnapshot> matches = addresses.stream()
            .filter(item -> item.environmentCode().equals(environmentCode) && item.endpointRef().equals(endpointRef))
            .toList();
        if (matches.isEmpty()) {
            return resolution(EndpointAddressStatus.UNCONFIGURED, null, "环境内地址未配置");
        }
        if (matches.size() > 1) {
            return resolution(EndpointAddressStatus.AMBIGUOUS, null, "环境内地址匹配不唯一");
        }

        EndpointAddressSnapshot address = matches.getFirst();
        List<EndpointSystemAddressSnapshot> links = systemAddresses.stream()
            .filter(item -> item.systemCode().equals(systemCode) && item.addressId().equals(address.addressId()))
            .toList();
        if (links.isEmpty()) {
            return resolution(EndpointAddressStatus.UNLINKED, null, "地址尚未关联该系统");
        }
        if (links.size() > 1) {
            return resolution(EndpointAddressStatus.AMBIGUOUS, null, "系统地址关联不唯一");
        }
        if (!system.enabled() || !environment.enabled() || !address.enabled() || !links.getFirst().enabled()) {
            return resolution(EndpointAddressStatus.DISABLED, null, "系统、环境、地址或关联已停用");
        }
        return resolution(EndpointAddressStatus.ENABLED, address, "地址解析成功");
    }

    private static EndpointAddressResolution resolution(EndpointAddressStatus status,
                                                        EndpointAddressSnapshot address,
                                                        String message) {
        return new EndpointAddressResolution(status, address, message);
    }
}
