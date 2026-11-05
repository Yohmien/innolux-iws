/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 中心受保护接口校验根会话和账号启用状态；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.config;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.auth.service.IamSessionRegistry;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.utils.IamCredentialFingerprint;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.helper.DataPermissionHelper;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 中心根会话守卫。
 */
@Configuration
@RequiredArgsConstructor
public class IamRootSessionGuardConfig implements WebMvcConfigurer {

    private final IamSessionRegistry sessionRegistry;
    private final ISysUserService userService;

    /**
     * 注册中心受保护 API 的根会话守卫。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RootSessionInterceptor())
            .addPathPatterns(
                "/system/**",
                "/monitor/**",
                "/iam/portal/**",
                "/iam/authorize",
                "/iam/view-mapping/**"
            )
            .order(0);
    }

    /**
     * 校验登录 Token 绑定的根会话和当前账号状态。
     */
    private final class RootSessionInterceptor implements HandlerInterceptor {

        /**
         * 请求进入中心受保护接口前校验根会话。
         *
         * @param request  HTTP 请求
         * @param response HTTP 响应
         * @param handler  处理器
         * @return 是否继续
         */
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!StpUtil.isLogin()) {
                throw new ServiceException("登录状态异常，请重新登录", 401);
            }
            Object rootSid = StpUtil.getTokenSession().get(IamProtocolConstants.ROOT_SID_SESSION_KEY);
            IamSessionStatusResponse status = sessionRegistry.status(
                rootSid == null ? null : rootSid.toString()
            );
            if (!status.active()) {
                logoutAndReject("统一身份根会话已失效");
            }
            SysUserVo user = DataPermissionHelper.ignore(
                () -> userService.selectUserById(status.userId())
            );
            if (user == null || SystemConstants.DISABLE.equals(user.getStatus())) {
                logoutAndReject("统一身份账号已停用或不存在");
            }
            Object expectedFingerprint = StpUtil.getTokenSession().get(
                IamProtocolConstants.CREDENTIAL_FINGERPRINT_KEY
            );
            if (!IamCredentialFingerprint.matches(
                user.getPassword(), expectedFingerprint == null ? null : expectedFingerprint.toString()
            )) {
                logoutAndReject("统一身份账号凭据已变更，请重新登录");
            }
            return true;
        }

        /**
         * 清理中心本地会话并返回认证失败。
         *
         * @param message 失败原因
         */
        private void logoutAndReject(String message) {
            try {
                StpUtil.logout();
            } catch (Exception ignored) {
            }
            throw new ServiceException(message, 401);
        }
    }
}
