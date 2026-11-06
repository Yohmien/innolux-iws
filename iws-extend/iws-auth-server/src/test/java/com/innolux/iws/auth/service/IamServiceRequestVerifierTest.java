package com.innolux.iws.auth.service;

import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 服务间请求校验错误信息测试。
 */
@Tag("dev")
class IamServiceRequestVerifierTest {

    @Test
    void preservesUnregisteredApplicationReason() {
        IamServiceRequestVerifier verifier = new IamServiceRequestVerifier(properties());

        ServiceException error = assertThrows(
            ServiceException.class,
            () -> verifier.verify("missing", "iws-a-1", "0", "", "", "POST", "/iam/tickets/inspect", "{}")
        );

        assertEquals(403, error.getCode());
        assertEquals("未登记的目标应用", error.getMessage());
    }

    @Test
    void preservesUnregisteredInstanceReason() {
        IamServiceRequestVerifier verifier = new IamServiceRequestVerifier(properties());

        ServiceException error = assertThrows(
            ServiceException.class,
            () -> verifier.verify("iws-a", "iws-a-9", "0", "", "", "POST", "/iam/tickets/inspect", "{}")
        );

        assertEquals(403, error.getCode());
        assertEquals("目标应用节点未登记", error.getMessage());
    }

    private IamProperties properties() {
        IamProperties.Application application = new IamProperties.Application();
        application.setEnabled(true);
        application.setAllowedInstanceIds(Set.of("iws-a-1"));
        IamProperties properties = new IamProperties();
        properties.getApplications().put("iws-a", application);
        return properties;
    }
}
