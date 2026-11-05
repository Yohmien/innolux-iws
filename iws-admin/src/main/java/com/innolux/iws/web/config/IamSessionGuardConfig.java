/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 对 IWS 受保护请求校验中心根会话和当前原权限；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.config;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.web.service.IamSessionValidationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * IWS 统一身份短租约守卫。
 */
@Configuration
@RequiredArgsConstructor
public class IamSessionGuardConfig implements WebMvcConfigurer {

    private final IamSessionValidationService validationService;

    /**
     * 注册根会话守卫；静态资源、登录与健康检查不纳入。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RootSessionInterceptor())
            .addPathPatterns("/**")
            .excludePathPatterns(
                "/auth/**",
                "/error",
                "/actuator/**",
                "/doc.html",
                "/nextdoc/**",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/*.html",
                "/**/*.css",
                "/**/*.js",
                "/favicon.ico"
            );
    }

    /**
     * 根会话和原权限校验拦截器。
     */
    private final class RootSessionInterceptor implements HandlerInterceptor {

        /**
         * 在本地登录有效时短租约校验中心会话和原权限事实。
         *
         * @param request  HTTP 请求
         * @param response HTTP 响应
         * @param handler  处理器
         * @return 是否继续
         */
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!StpUtil.isLogin()) {
                return true;
            }
            boolean readRequest = "GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod())
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
            IamSessionValidationService.ValidationResult result =
                validationService.validate(StpUtil.getTokenValue(), readRequest);
            if (result.status() == IamSessionValidationService.ValidationResult.Status.INVALID) {
                logoutAndReject(result.message());
            }
            if (result.status() == IamSessionValidationService.ValidationResult.Status.CENTER_UNAVAILABLE) {
                throw new ServiceException(result.message(), 503);
            }
            return true;
        }

        /**
         * 清理本地会话并返回认证失败。
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
