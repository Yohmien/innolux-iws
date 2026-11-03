/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 验证对象授权的零、单、多策略路由与超级权限旁路；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对象范围策略路由测试。
 */
@Tag("local")
class ProcessObjectAuthorizerTest {

    private RuntimeDao runtimeDao;

    private ObjectProvider<ProcessAccessPolicy> policyProvider;

    private ProcessObjectAuthorizer authorizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ProcessTestAuth.install();
        runtimeDao = mock(RuntimeDao.class);
        policyProvider = mock(ObjectProvider.class);
        authorizer = new ProcessObjectAuthorizer(runtimeDao, policyProvider);
    }

    @AfterEach
    void tearDown() {
        ProcessTestAuth.clear();
    }

    @Test
    void zeroPoliciesDeny() {
        ProcessTestAuth.login();
        usePolicies();

        assertThat(authorizer.canAccess(target(1L, "APK"))).isFalse();
    }

    @Test
    void singlePolicyKeepsItsAllowAndDenyDecision() {
        ProcessTestAuth.login();
        usePolicies(target -> target.scheduleId() == 1L);

        assertThat(authorizer.canAccess(target(1L, "APK"))).isTrue();
        assertThat(authorizer.canAccess(target(2L, "APK"))).isFalse();
    }

    @Test
    void multiplePoliciesAllowWhenAnyBusinessScopeMatches() {
        ProcessTestAuth.login();
        usePolicies(
            target -> target.scheduleId() == 1L && "APK".equals(target.businessType()),
            target -> target.scheduleId() == 2L && "WMS".equals(target.businessType()));

        assertThat(authorizer.canAccess(target(1L, "APK"))).isTrue();
        assertThat(authorizer.canAccess(target(2L, "WMS"))).isTrue();
        assertThat(authorizer.canAccess(target(1L, "WMS"))).isFalse();
        assertThat(authorizer.canAccess(target(3L, "OTHER"))).isFalse();
    }

    @Test
    void primaryPolicyDoesNotHideAnotherBusinessPolicy() {
        ProcessTestAuth.login();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("apkPolicy", ProcessAccessPolicy.class,
                () -> target -> "APK".equals(target.businessType()));
            context.registerBean("wmsPolicy", ProcessAccessPolicy.class,
                () -> target -> "WMS".equals(target.businessType()),
                definition -> definition.setPrimary(true));
            context.refresh();
            ProcessObjectAuthorizer contextAuthorizer = new ProcessObjectAuthorizer(runtimeDao,
                context.getBeanProvider(ProcessAccessPolicy.class));

            assertThat(contextAuthorizer.canAccess(target(1L, "APK"))).isTrue();
            assertThat(contextAuthorizer.canAccess(target(1L, "WMS"))).isTrue();
        }
    }

    @Test
    void failingPolicyDoesNotBlockAnotherBusinessOrGrantAccess() {
        ProcessTestAuth.login();
        usePolicies(
            target -> {
                throw new IllegalStateException("APK scope unavailable");
            },
            target -> target.scheduleId() == 2L);

        assertThat(authorizer.canAccess(target(2L, "WMS"))).isTrue();
        assertThat(authorizer.canAccess(target(3L, "WMS"))).isFalse();
    }

    @Test
    void allPoliciesFailingOrDenyingIsDenied() {
        ProcessTestAuth.login();
        usePolicies(
            target -> {
                throw new IllegalStateException("APK scope unavailable");
            },
            target -> {
                throw new IllegalStateException("WMS scope unavailable");
            });

        assertThat(authorizer.canAccess(target(1L, "APK"))).isFalse();
    }

    @Test
    void superObjectPermissionBypassesPolicyEvaluation() {
        ProcessTestAuth.login("process:object:all");
        AtomicInteger calls = new AtomicInteger();
        usePolicies(target -> {
            calls.incrementAndGet();
            throw new IllegalStateException("Policy must not run");
        });

        assertThat(authorizer.canAccess(target(9L, "WMS"))).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void anonymousUserIsRejected() {
        ProcessTestAuth.anonymous();
        usePolicies(target -> true);

        assertThatThrownBy(() -> authorizer.canAccess(target(1L, "APK")))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("未登录");
    }

    @Test
    void queryAndCommandEntriesUseTheSamePolicyRoute() {
        ProcessTestAuth.login();
        ProcessInstance first = instance(10L, 1L, "APK");
        ProcessInstance second = instance(20L, 2L, "WMS");
        when(runtimeDao.instance(10L)).thenReturn(first);
        when(runtimeDao.instance(20L)).thenReturn(second);
        when(runtimeDao.event(100L)).thenReturn(event(100L, 10L));
        when(runtimeDao.event(200L)).thenReturn(event(200L, 20L));
        usePolicies(target -> target.scheduleId() == 1L);

        assertThatCode(() -> authorizer.authorizeInstance(10L, "COMMAND")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.authorizeInstance(20L, "COMMAND"))
            .isInstanceOf(ServiceException.class);
        assertThatCode(() -> authorizer.authorizeEvent(100L, "QUERY")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.authorizeEvent(200L, "QUERY"))
            .isInstanceOf(ServiceException.class);
        assertThatCode(() -> authorizer.authorizeSchedule(1L, "QUERY")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.authorizeSchedule(2L, "QUERY"))
            .isInstanceOf(ServiceException.class);
        assertThat(authorizer.canAccess(instanceTarget(first))).isTrue();
        assertThat(authorizer.canAccess(instanceTarget(second))).isFalse();
    }

    @Test
    void ordinaryBusinessInstanceKeepsItsOwnBusinessScopeFields() {
        ProcessTestAuth.login();
        ProcessInstance business = instance(30L, 1L, "WMS");
        business.setPlanType("SUPERVISOR_PLAN");
        business.setSupervisorKey("SUPERVISOR:1:WMS:DEFAULT");
        when(runtimeDao.instance(30L)).thenReturn(business);
        List<ProcessAccessTarget> targets = new CopyOnWriteArrayList<>();
        usePolicies(target -> {
            targets.add(target);
            return true;
        });

        authorizer.authorizeInstance(30L, "COMMAND");

        assertThat(targets).containsExactly(new ProcessAccessTarget(30L, null, 1L, "WMS", "30",
            "30", "DEFAULT", "COMMAND"));
    }

    private void usePolicies(ProcessAccessPolicy... policies) {
        List<ProcessAccessPolicy> values = List.of(policies);
        when(policyProvider.orderedStream()).thenAnswer(invocation -> values.stream());
    }

    private static ProcessAccessTarget target(long scheduleId, String businessType) {
        return new ProcessAccessTarget(0L, null, scheduleId, businessType, null, null, null, "QUERY");
    }

    private static ProcessAccessTarget instanceTarget(ProcessInstance instance) {
        return new ProcessAccessTarget(instance.getId(), null, instance.getScheduleId(),
            instance.getBusinessType(), instance.getBusinessId(), instance.getTaskKey(),
            instance.getScheduleScope(), "QUERY");
    }

    private static ProcessInstance instance(long id, long scheduleId, String businessType) {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(id);
        instance.setScheduleId(scheduleId);
        instance.setBusinessType(businessType);
        instance.setBusinessId(String.valueOf(id));
        instance.setTaskKey(String.valueOf(id));
        instance.setScheduleScope("DEFAULT");
        return instance;
    }

    private static ProcessEvent event(long id, long instanceId) {
        ProcessEvent event = new ProcessEvent();
        event.setId(id);
        event.setInstanceId(instanceId);
        return event;
    }
}
