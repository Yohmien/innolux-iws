/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录只读接口实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

import com.innolux.iws.endpoint.config.EndpointProperties;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository;
import com.innolux.iws.endpoint.util.EndpointValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 端点目录只读接口实现。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "iws.endpoint", name = "enabled", havingValue = "true")
public class EndpointCatalogReaderImpl implements EndpointCatalogReader {

    private final EndpointCatalogRepository repository;
    private final EndpointProperties properties;

    @Override
    @Transactional(readOnly = true)
    public EndpointCatalogSnapshot snapshot() {
        long revision = repository.readControlRevision();
        return buildSnapshot(revision);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public EndpointCatalogSnapshot lockSnapshot(String expectedCatalogRevision) {
        long expectedRevision = EndpointValidator.revision(expectedCatalogRevision);
        long actualRevision = repository.lockSharedControl();
        if (expectedRevision != actualRevision) {
            throw new EndpointCatalogConflictException(expectedCatalogRevision, Long.toString(actualRevision));
        }
        return buildSnapshot(actualRevision);
    }

    @Override
    public String currentEnvironmentCode() {
        return properties.getCurrentEnvironment();
    }

    private EndpointCatalogSnapshot buildSnapshot(long revision) {
        return new EndpointCatalogSnapshot(
            Long.toString(revision),
            repository.allSystems().stream()
                .map(row -> new EndpointSystemSnapshot(row.systemCode(), row.displayName(), row.enabled(),
                    Long.toString(row.revision())))
                .toList(),
            repository.allEnvironments().stream()
                .map(row -> new EndpointEnvironmentSnapshot(row.environmentCode(), row.displayName(), row.enabled(),
                    Long.toString(row.revision())))
                .toList(),
            repository.allAddresses().stream()
                .map(row -> new EndpointAddressSnapshot(Long.toString(row.addressId()), row.environmentCode(),
                    row.endpointRef(), row.protocol(), row.host(), row.port(), row.basePath(), row.enabled(),
                    Long.toString(row.revision()), row.credentialRef()))
                .toList(),
            repository.allRelations().stream()
                .map(row -> new EndpointSystemAddressSnapshot(row.systemCode(), Long.toString(row.addressId()),
                    row.enabled(), Long.toString(row.revision())))
                .toList());
    }
}
