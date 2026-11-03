/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时功能权限与 ProcessAccessPolicy 对象授权验收；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.immediate;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.controller.ImmediateExecutionController;
import com.innolux.iws.process.runtime.ImmediateAdmissionService;
import com.innolux.iws.process.runtime.ImmediateCommandReceiptStore;
import com.innolux.iws.process.runtime.ImmediateExecutionDriver;
import com.innolux.iws.process.runtime.ImmediateExecutionGuard;
import com.innolux.iws.process.runtime.ImmediateExecutionService;
import com.innolux.iws.process.runtime.ImmediateOutcomeService;
import com.innolux.iws.process.runtime.ProcessAccessPolicy;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RuntimeDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 即时入口不能绕过公共对象授权。
 */
@Tag("dev")
class ImmediateObjectAuthorizationTest {

    @AfterEach
    void clearSaToken() {
        try {
            StpUtil.logout();
        } catch (RuntimeException ignored) {
            // 测试上下文可能已清理。
        }
        SaTokenContextMockUtil.clearContext();
    }

    @Test
    void ordinaryLoggedInUserWithoutPolicyIsDeniedBeforeReceiptLookup() {
        loginWithPermissions(List.of());
        ImmediateCommandReceiptStore receiptStore = mock(ImmediateCommandReceiptStore.class);
        ImmediateExecutionGuard guard = mock(ImmediateExecutionGuard.class);
        ImmediateExecutionService service = service(new ProcessObjectAuthorizer(mock(RuntimeDao.class),
            policy(target -> false)), receiptStore, guard);

        assertThrows(ServiceException.class, () -> service.execute(command()));
        verifyNoInteractions(receiptStore);
    }

    @Test
    void registeredPolicyAndSuperObjectPermissionReachTheSameGate() {
        loginWithPermissions(List.of());
        ImmediateCommandReceiptStore policyStore = mock(ImmediateCommandReceiptStore.class);
        ImmediateExecutionGuard policyGuard = mock(ImmediateExecutionGuard.class);
        when(policyGuard.ready()).thenReturn(false);
        ImmediateExecutionService policyService = service(new ProcessObjectAuthorizer(mock(RuntimeDao.class),
            policy(target -> true)), policyStore, policyGuard);
        policyService.execute(command());
        verify(policyGuard).ready();

        loginWithPermissions(List.of("process:object:all"));
        ImmediateExecutionGuard allGuard = mock(ImmediateExecutionGuard.class);
        when(allGuard.ready()).thenReturn(false);
        ImmediateExecutionService allService = service(new ProcessObjectAuthorizer(mock(RuntimeDao.class),
            policy(target -> false)), mock(ImmediateCommandReceiptStore.class), allGuard);
        allService.execute(command());
        verify(allGuard).ready();
    }

    @Test
    void controllerDeclaresDistinctFunctionPermissions() throws Exception {
        Method execute = ImmediateExecutionController.class.getMethod("execute", long.class,
            ExecuteImmediateCommand.class);
        Method query = ImmediateExecutionController.class.getMethod("command", long.class, String.class);
        assertEquals(List.of("process:instance:execute"),
            List.of(execute.getAnnotation(SaCheckPermission.class).value()));
        assertEquals(List.of("process:schedule:query"),
            List.of(query.getAnnotation(SaCheckPermission.class).value()));
    }

    private ImmediateExecutionService service(ProcessObjectAuthorizer authorizer,
                                              ImmediateCommandReceiptStore store,
                                              ImmediateExecutionGuard guard) {
        return new ImmediateExecutionService(mock(ImmediateAdmissionService.class), mock(ImmediateExecutionDriver.class),
            mock(ImmediateOutcomeService.class), guard, store, authorizer);
    }

    private ObjectProvider<ProcessAccessPolicy> policy(ProcessAccessPolicy value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProcessAccessPolicy> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private void loginWithPermissions(List<String> permissions) {
        SaTokenContextMockUtil.setMockContext();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return permissions;
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return List.of();
            }
        });
        StpUtil.login(1001L);
    }

    private ExecuteImmediateCommand command() {
        return new ExecuteImmediateCommand(8001L, "IMM", "BIZ", "BIZ-1", "t-auth", "in-auth", "c-auth");
    }
}
