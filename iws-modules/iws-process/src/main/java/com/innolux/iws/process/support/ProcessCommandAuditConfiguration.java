package com.innolux.iws.process.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Set;

/** 在既有鉴权之前标记已注册写命令，异常审计不改变原异常处理与响应。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessCommandAuditConfiguration implements WebMvcConfigurer {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessCommandAuditConfiguration.class);
    private static final String MARKER = ProcessCommandAuditConfiguration.class.getName();
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final ProcessCommandRejectionAudit audit;

    public ProcessCommandAuditConfiguration(ProcessCommandRejectionAudit audit) {
        this.audit = audit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod method && WRITE_METHODS.contains(request.getMethod())
                    && method.getBeanType().getPackageName().startsWith("com.innolux.iws.process.controller")) {
                    request.setAttribute(MARKER, method.getMethod().getName());
                }
                return true;
            }
        }).addPathPatterns("/process/**").order(Integer.MIN_VALUE);
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.addFirst((request, response, handler, exception) -> {
            Object action = request.getAttribute(MARKER);
            if (action != null) {
                try {
                    String type = exception.getClass().getSimpleName();
                    String category = type.contains("Permission") || type.contains("Login")
                        ? "REJECTED_DENIED" : "REJECTED_VALIDATION";
                    audit.rejected(action.toString(), request.getRequestURI(), category);
                } catch (RuntimeException auditFailure) {
                    LOG.error("计划命令拒绝审计未保存: path={}", request.getRequestURI(), auditFailure);
                }
                request.removeAttribute(MARKER);
            }
            return (ModelAndView) null;
        });
    }
}
