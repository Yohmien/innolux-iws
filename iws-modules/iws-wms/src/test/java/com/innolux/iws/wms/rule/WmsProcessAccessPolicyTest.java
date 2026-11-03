package com.innolux.iws.wms.rule;

import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.runtime.ProcessAccessTarget;
import com.innolux.iws.wms.process.WmsPlanScopeResolver;
import com.innolux.iws.wms.process.WmsProcessAccessPolicy;
import com.innolux.iws.wms.process.WmsProcessMountGate;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsProcessAccessPolicyTest {

    private static final long SCHEDULE_ID = 8_301L;

    private static final long COMPANY_ID = 1L;

    private static final long USER_ID = 42L;

    @Test
    void ordinaryRoleCanMountAndReadOnlyWithinOwnCompany() {
        WmsRuleTestAuth.login(USER_ID, Set.of());
        try {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            WmsCompanyAccessPolicy access = mock(WmsCompanyAccessPolicy.class);
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("business_code")).thenReturn("SPACE_CONFIG");
                    when(rs.getString("business_schedule_code")).thenReturn("SPACE_CONFIG");
                    when(rs.getString("business_type")).thenReturn(
                        WmsSpaceChangeService.BUSINESS_TYPE);
                    when(rs.getString("plan_type")).thenReturn(
                        WmsSpaceChangeService.PLAN_TYPE_CHANGE);
                    when(rs.getLong("company_id")).thenReturn(COMPANY_ID);
                    return List.of(mapper.mapRow(rs, 0));
                });
            when(access.hasPermission(WmsSpaceChangeService.PERMISSION_CHANGE))
                .thenReturn(true);
            when(access.hasCompanyAccess(COMPANY_ID)).thenReturn(true);

            WmsProcessAccessPolicy policy = new WmsProcessAccessPolicy(jdbc, access);
            assertThat(policy.canAccess(new ProcessAccessTarget(
                0L, null, SCHEDULE_ID, null, null, null, null, "ATTACH"
            ))).isTrue();

            WmsProcessMountGate gate = new WmsProcessMountGate(jdbc, access);
            gate.requireMountAllowed(
                new AttachTaskCommand(
                    SCHEDULE_ID, WmsSpaceChangeService.PLAN_TYPE_CHANGE,
                    WmsSpaceChangeService.BUSINESS_TYPE, "123", "K", true,
                    "a".repeat(64), List.of(), "ATTACH-K"),
                new BusinessSchedule()
            );
            verify(access).lockAndCheckCompanyForShare(COMPANY_ID);
        } finally {
            WmsRuleTestAuth.clear();
        }
    }

    @Test
    void queryPermissionCannotSubstituteForChangePermission() {
        WmsRuleTestAuth.login(USER_ID, Set.of());
        try {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            WmsCompanyAccessPolicy access = mock(WmsCompanyAccessPolicy.class);
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("business_code")).thenReturn("SPACE_CONFIG");
                    return List.of(mapper.mapRow(rs, 0));
                });
            when(access.hasPermission(WmsSpaceChangeService.PERMISSION_CHANGE))
                .thenReturn(false);
            when(access.hasPermission("inventory:space:query")).thenReturn(true);

            WmsProcessAccessPolicy policy = new WmsProcessAccessPolicy(jdbc, access);
            assertThat(policy.canAccess(new ProcessAccessTarget(
                0L, null, SCHEDULE_ID, null, null, null, null, "ATTACH"
            ))).isFalse();
        } finally {
            WmsRuleTestAuth.clear();
        }
    }

    @Test
    void spaceSupervisorScopeIsIsolatedByCompany() {
        WmsRuleTestAuth.login(USER_ID, Set.of());
        try {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            WmsCompanyAccessPolicy access = mock(WmsCompanyAccessPolicy.class);
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("business_code")).thenReturn("SPACE_CONFIG");
                    when(rs.getString("business_schedule_code")).thenReturn("SPACE_CONFIG");
                    when(rs.getString("business_type")).thenReturn(null);
                    when(rs.getString("plan_type")).thenReturn(
                        WmsSpaceChangeService.PLAN_TYPE_CHANGE);
                    when(rs.getString("schedule_scope")).thenReturn("COMPANY:" + COMPANY_ID);
                    return List.of(mapper.mapRow(rs, 0));
                });
            when(access.hasPermission(WmsSpaceChangeService.PERMISSION_CHANGE))
                .thenReturn(true);
            when(access.hasCompanyAccess(COMPANY_ID)).thenReturn(false);

            WmsProcessAccessPolicy policy = new WmsProcessAccessPolicy(jdbc, access);
            assertThat(policy.canAccess(new ProcessAccessTarget(
                77L, null, SCHEDULE_ID, WmsSpaceChangeService.PLAN_TYPE_CHANGE,
                "SUPERVISOR:" + SCHEDULE_ID + ":SPACE_CHANGE:COMPANY:" + COMPANY_ID,
                null, "COMPANY:" + COMPANY_ID, "QUERY"
            ))).isFalse();
        } finally {
            WmsRuleTestAuth.clear();
        }
    }

    @Test
    void spaceScopeResolverUsesPersistedOrderCompany() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(invocation -> {
                RowMapper<?> mapper = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("company_id")).thenReturn(COMPANY_ID);
                when(rs.wasNull()).thenReturn(false);
                when(rs.getString("object_type")).thenReturn("ZONE");
                when(rs.getLong("object_id")).thenReturn(123L);
                return List.of(mapper.mapRow(rs, 0));
            });

        WmsPlanScopeResolver resolver = new WmsPlanScopeResolver(jdbc);
        assertThat(resolver.resolve(
            WmsSpaceChangeService.BUSINESS_TYPE,
            WmsSpaceChangeService.PLAN_TYPE_CHANGE,
            "123",
            "TASK"
        )).isEqualTo("COMPANY:" + COMPANY_ID);
        assertThat(resolver.resolve("OTHER", "PLAN", "123", "TASK"))
            .isEqualTo("DEFAULT");
    }
}
