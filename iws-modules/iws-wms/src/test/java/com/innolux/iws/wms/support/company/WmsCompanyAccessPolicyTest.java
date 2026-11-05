package com.innolux.iws.wms.support.company;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.domain.WmsCompanyManagementGrant;
import com.innolux.iws.wms.domain.WmsCompanyManagementScope;
import com.innolux.iws.wms.mapper.WmsCompanyManagementGrantMapper;
import com.innolux.iws.wms.mapper.WmsCompanyManagementScopeMapper;
import com.innolux.iws.wms.mapper.WmsCompanyMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("local")
class WmsCompanyAccessPolicyTest {

    private static final long USER_ID = 42L;

    private static final long COMPANY_ID = 7L;

    @Test
    void explicitInitiatorCanBeCheckedWithoutHttpSession() {
        Fixture fixture = fixture(true);

        assertThatCode(() -> fixture.policy()
            .lockAndCheckCompanyForShare(COMPANY_ID, USER_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void revokedGrantFailsClosedWithoutHttpSession() {
        Fixture fixture = fixture(false);

        assertThatThrownBy(() -> fixture.policy()
            .lockAndCheckCompanyForShare(COMPANY_ID, USER_ID))
            .isInstanceOf(ServiceException.class)
            .hasMessageStartingWith("COMPANY_SCOPE_DENIED");
    }

    private static Fixture fixture(boolean activeGrant) {
        WmsCompanyManagementScopeMapper scopes = mock(WmsCompanyManagementScopeMapper.class);
        WmsCompanyManagementGrantMapper grants = mock(WmsCompanyManagementGrantMapper.class);
        IamIdentityFacts identityFacts = mock(IamIdentityFacts.class);
        WmsCompanyMapper companies = mock(WmsCompanyMapper.class);

        WmsCompanyManagementScope scope = new WmsCompanyManagementScope();
        scope.setCompanyId(COMPANY_ID);
        when(scopes.selectByIdForShare(COMPANY_ID)).thenReturn(scope);
        when(identityFacts.superAdminForShare(USER_ID)).thenReturn(false);
        when(identityFacts.currentPermissionForShare(
            any(), any())).thenReturn(false);
        when(identityFacts.currentRoleIdsForShare(USER_ID)).thenReturn(List.of(11L));
        when(grants.selectList(any(Wrapper.class))).thenReturn(
            activeGrant
                ? List.of(new WmsCompanyManagementGrant())
                : List.of()
        );
        return new Fixture(new WmsCompanyAccessPolicy(scopes, grants, identityFacts, companies));
    }

    private record Fixture(WmsCompanyAccessPolicy policy) {
    }
}
