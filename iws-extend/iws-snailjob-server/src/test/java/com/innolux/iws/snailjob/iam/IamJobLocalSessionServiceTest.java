package com.innolux.iws.snailjob.iam;

import com.aizuda.snailjob.server.web.model.response.NamespaceResponseVO;
import com.aizuda.snailjob.server.web.model.response.SystemUserResponseVO;
import com.aizuda.snailjob.server.web.service.SystemUserService;
import com.aizuda.snailjob.template.datasource.persistence.mapper.SystemUserMapper;
import com.aizuda.snailjob.template.datasource.persistence.po.SystemUser;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class IamJobLocalSessionServiceTest {

    @Test
    void missingExplicitMappingDoesNotFallbackToSameName() {
        IamJobProperties properties = new IamJobProperties();
        properties.getAccounts().put("other-center-user", "local-user");
        SystemUserMapper mapper = mock(SystemUserMapper.class);
        SystemUserService service = mock(SystemUserService.class);
        IamJobLocalSessionService localSessionService =
            new IamJobLocalSessionService(properties, mapper, service);

        assertThrows(
            IllegalStateException.class,
            () -> localSessionService.requireMappedUser("center-user")
        );
        verifyNoInteractions(mapper, service);
    }

    @Test
    void issuerAddsCenterSessionClaimsToNativeCompatibleToken() {
        IamJobProperties properties = new IamJobProperties();
        properties.getAccounts().put("center-user", "local-user");
        SystemUserMapper mapper = mock(SystemUserMapper.class);
        SystemUserService service = mock(SystemUserService.class);

        SystemUser localUser = new SystemUser();
        localUser.setId(7L);
        localUser.setUsername("local-user");
        localUser.setPassword("native-signing-key");
        when(mapper.selectOne(any())).thenReturn(localUser);

        NamespaceResponseVO namespace = new NamespaceResponseVO();
        namespace.setUniqueId("dev");
        SystemUserResponseVO response = new SystemUserResponseVO();
        response.setId(7L);
        response.setUsername("local-user");
        response.setRole(2);
        response.setNamespaceIds(List.of(namespace));
        when(service.getSystemUserByUserName("local-user")).thenReturn(response);

        IamJobLocalSessionService localSessionService =
            new IamJobLocalSessionService(properties, mapper, service);
        IamJobLocalSessionService.IssuedSession issued = localSessionService.issue(
            new IamTicketExchangeResponse(
                IamProtocolConstants.ISSUER,
                "snail-job",
                "SNAIL_JOB",
                "job-main",
                42L,
                "center-user",
                "root-1",
                "session-1",
                3,
                "WEB",
                "snail-job-1",
                "/",
                System.currentTimeMillis() + 60_000
            )
        );

        assertEquals("dev", issued.namespaceId());
        assertEquals(42L, JWT.decode(issued.accessToken())
            .getClaim(IamProtocolConstants.SUBJECT_USER_ID_CLAIM).asLong());
        assertEquals("root-1", JWT.decode(issued.accessToken())
            .getClaim(IamProtocolConstants.ROOT_SID_SESSION_KEY).asString());
        JWT.require(Algorithm.HMAC256(localUser.getPassword()))
            .build()
            .verify(issued.accessToken());
    }
}
