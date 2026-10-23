/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 通过 endpoint Reader 解析出站目标；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.endpoint.catalog.EndpointAddressResolution;
import com.innolux.iws.endpoint.catalog.EndpointAddressSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointAddressStatus;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * 只读目录目标解析。不在 process 复制地址或按 IP 推断系统。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "com.innolux.iws.endpoint.catalog.EndpointCatalogReader")
public class EndpointTargetResolver {

    private final EndpointCatalogReader catalogReader;

    /**
     * 构造解析器。
     *
     * @param catalogReader 目录 Reader
     */
    public EndpointTargetResolver(EndpointCatalogReader catalogReader) {
        this.catalogReader = catalogReader;
    }

    /**
     * 解析当前环境目标。
     *
     * @param systemCode 系统编码
     * @param endpointRef 地址引用
     * @return 解析目标
     */
    public ResolvedEndpoint resolve(String systemCode, String endpointRef) {
        String environmentCode = catalogReader.currentEnvironmentCode();
        if (environmentCode == null || environmentCode.isBlank()) {
            throw new ServiceException("当前运行环境未配置");
        }
        var snapshot = catalogReader.snapshot();
        EndpointAddressResolution resolution = snapshot.resolveEndpoint(environmentCode, systemCode, endpointRef);
        if (resolution.status() != EndpointAddressStatus.ENABLED || resolution.address() == null) {
            throw new ServiceException("端点不可用: " + resolution.status() + " " + resolution.message());
        }
        return new ResolvedEndpoint(resolution.address().baseUri(), resolution.address().addressId(),
            resolution.address().revision(), snapshot.catalogRevision(), environmentCode);
    }

    /**
     * 已解析目标。
     *
     * @param baseUrl 基础地址
     * @param addressId 地址 ID
     * @param addressRevision 地址对象版本
     * @param catalogRevision 目录版本
     * @param environmentCode 环境编码
     */
    public record ResolvedEndpoint(String baseUrl, String addressId, String addressRevision,
                                   String catalogRevision, String environmentCode) {
    }
}
