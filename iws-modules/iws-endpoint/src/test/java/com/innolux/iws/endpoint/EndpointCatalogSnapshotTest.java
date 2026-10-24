/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 只读快照地址解析状态测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint;

import com.innolux.iws.endpoint.catalog.EndpointAddressResolution;
import com.innolux.iws.endpoint.catalog.EndpointAddressSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointAddressStatus;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemAddressSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读快照地址解析状态测试。
 */
@Tag("dev")
class EndpointCatalogSnapshotTest {

    @Test
    void explicitAssociationResolvesSharedAddressWithoutIpBasedSystemInference() {
        EndpointCatalogSnapshot snapshot = snapshot(true, true);

        EndpointAddressResolution mid = snapshot.resolveEndpoint("PROD", "MID_WMS", "GATEWAY");
        EndpointAddressResolution sap = snapshot.resolveEndpoint("PROD", "SAP", "GATEWAY");
        EndpointAddressResolution mes = snapshot.resolveEndpoint("PROD", "MES", "GATEWAY");

        assertThat(mid.status()).isEqualTo(EndpointAddressStatus.ENABLED);
        assertThat(mid.address().baseUri()).isEqualTo("https://[2001:db8::10]:8443/gw");
        assertThat(sap.status()).isEqualTo(EndpointAddressStatus.ENABLED);
        assertThat(mes.status()).isEqualTo(EndpointAddressStatus.UNLINKED);
        assertThat(mes.address()).isNull();
    }

    @Test
    void unknownAndDisabledAndUnconfiguredStatesAreExplicit() {
        EndpointCatalogSnapshot snapshot = snapshot(false, false);

        assertThat(snapshot.resolveEndpoint("PROD", "UNKNOWN", "GATEWAY").status())
            .isEqualTo(EndpointAddressStatus.UNREGISTERED);
        assertThat(snapshot.resolveEndpoint("PROD", "MID_WMS", "GATEWAY").status())
            .isEqualTo(EndpointAddressStatus.DISABLED);
        assertThat(snapshot.resolveEndpoint("QA", "MID_WMS", "GATEWAY").status())
            .isEqualTo(EndpointAddressStatus.UNCONFIGURED);
        assertThat(snapshot.resolveEndpoint("PROD", "MID_WMS", "MISSING").status())
            .isEqualTo(EndpointAddressStatus.UNCONFIGURED);
    }

    private EndpointCatalogSnapshot snapshot(boolean addressEnabled, boolean relationEnabled) {
        return new EndpointCatalogSnapshot(
            "7",
            List.of(
                new EndpointSystemSnapshot("MID_WMS", "MID 仓储系统", true, "1"),
                new EndpointSystemSnapshot("SAP", "SAP", true, "1"),
                new EndpointSystemSnapshot("MES", "MES", true, "1")
            ),
            List.of(new EndpointEnvironmentSnapshot("PROD", "生产", true, "1")),
            List.of(new EndpointAddressSnapshot("1", "PROD", "GATEWAY", "https", "2001:db8::10",
                8443, "/gw", addressEnabled, "1", null)),
            List.of(
                new EndpointSystemAddressSnapshot("MID_WMS", "1", relationEnabled, "1"),
                new EndpointSystemAddressSnapshot("SAP", "1", relationEnabled, "1")
            ));
    }
}
