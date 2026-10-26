package com.innolux.iws.process.config;

import com.innolux.iws.process.controller.PlanDefinitionController;
import com.innolux.iws.process.support.ProcessCommandAuditConfiguration;
import com.innolux.iws.process.support.ProcessCommandRejectionAudit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 拒绝审计在原异常解析之前执行，且不截断既有响应。 */
@Tag("dev")
class ProcessCommandAuditConfigurationTest {
    @Test
    void registeredWriteRejectionIsAuditedWithoutConsumingException() throws Exception {
        ProcessCommandRejectionAudit audit = mock(ProcessCommandRejectionAudit.class);
        ProcessCommandAuditConfiguration configuration = new ProcessCommandAuditConfiguration(audit);
        InterceptorRegistry registry = new InterceptorRegistry();
        configuration.addInterceptors(registry);
        // 注册的窄命令元数据由 preHandle 保存，异常解析器只消费该安全标记。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/process/definition/draft");
        request.setAttribute(ProcessCommandAuditConfiguration.class.getName(), "createDraft");
        List<HandlerExceptionResolver> resolvers = new ArrayList<>();
        configuration.extendHandlerExceptionResolvers(resolvers);
        assertThat(resolvers.getFirst().resolveException(request, new MockHttpServletResponse(), null,
            new IllegalArgumentException("不可持久化原始报文"))).isNull();
        verify(audit).rejected("createDraft", "/process/definition/draft", "REJECTED_VALIDATION");
        assertThat(request.getAttribute(ProcessCommandAuditConfiguration.class.getName())).isNull();
    }

    @Test
    void unknownOrReadOnlyPathDoesNotCreateCommandAudit() {
        ProcessCommandRejectionAudit audit = mock(ProcessCommandRejectionAudit.class);
        List<HandlerExceptionResolver> resolvers = new ArrayList<>();
        new ProcessCommandAuditConfiguration(audit).extendHandlerExceptionResolvers(resolvers);
        assertThat(resolvers.getFirst().resolveException(new MockHttpServletRequest("GET", "/process/missing"),
            new MockHttpServletResponse(), null, new IllegalArgumentException())).isNull();
        verifyNoInteractions(audit);
    }
}
