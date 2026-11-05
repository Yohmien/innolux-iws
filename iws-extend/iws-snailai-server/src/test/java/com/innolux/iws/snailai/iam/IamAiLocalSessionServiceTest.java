package com.innolux.iws.snailai.iam;

import com.aizuda.snail.ai.persistence.admin.mapper.UserMapper;
import com.aizuda.snail.ai.persistence.admin.po.UserPO;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class IamAiLocalSessionServiceTest {

    @Test
    void missingExplicitMappingDoesNotFallbackToSameName() {
        IamAiProperties properties = new IamAiProperties();
        properties.getAccounts().put("other-center-user", "local-user");
        UserMapper mapper = mock(UserMapper.class);
        IamAiLocalSessionService localSessionService =
            new IamAiLocalSessionService(properties, mapper);

        assertThrows(
            IllegalStateException.class,
            () -> localSessionService.requireMappedUser("center-user")
        );
        verifyNoInteractions(mapper);
    }

    @Test
    void issuerAddsCenterSessionClaimsToNativeCompatibleToken() {
        IamAiProperties properties = new IamAiProperties();
        properties.getAccounts().put("center-user", "local-user");
        UserMapper mapper = mock(UserMapper.class);
        UserPO localUser = new UserPO();
        localUser.setId(9L);
        localUser.setUsername("local-user");
        localUser.setPassword("native-signing-key");
        localUser.setRole(2);
        when(mapper.selectOne(any())).thenReturn(localUser);

        IamAiLocalSessionService localSessionService =
            new IamAiLocalSessionService(properties, mapper);
        IamAiLocalSessionService.IssuedSession issued = localSessionService.issue(
            new IamTicketExchangeResponse(
                IamProtocolConstants.ISSUER,
                "snail-ai",
                "SNAIL_AI",
                "ai-main",
                42L,
                "center-user",
                "root-1",
                "session-1",
                4,
                "WEB",
                "snail-ai-1",
                "/",
                System.currentTimeMillis() + 60_000
            )
        );

        assertEquals(42L, JWT.decode(issued.accessToken())
            .getClaim(IamProtocolConstants.SUBJECT_USER_ID_CLAIM).asLong());
        assertEquals("root-1", JWT.decode(issued.accessToken())
            .getClaim(IamProtocolConstants.ROOT_SID_SESSION_KEY).asString());
        JWT.require(Algorithm.HMAC256(localUser.getPassword()))
            .build()
            .verify(issued.accessToken());
    }
}
