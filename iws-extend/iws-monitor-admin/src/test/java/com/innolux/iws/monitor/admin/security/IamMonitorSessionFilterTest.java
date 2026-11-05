package com.innolux.iws.monitor.admin.security;

import com.innolux.iws.monitor.admin.config.IamMonitorProperties;
import com.innolux.iws.monitor.admin.service.IamMonitorCenterClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("dev")
class IamMonitorSessionFilterTest {

    private final IamMonitorSessionFilter filter = new IamMonitorSessionFilter(
        mock(IamMonitorProperties.class),
        mock(IamMonitorCenterClient.class)
    );

    @Test
    void allowsLogoutToReachLocalPendingRevocationHandler() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/logout");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void stillChecksProtectedMonitorRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/instances");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void preservesPublicAndMachinePaths() {
        assertThat(filter.shouldNotFilter(
            new MockHttpServletRequest("GET", "/admin/sso/start")
        )).isTrue();
        assertThat(filter.shouldNotFilter(
            new MockHttpServletRequest("GET", "/actuator/health")
        )).isTrue();
    }
}
