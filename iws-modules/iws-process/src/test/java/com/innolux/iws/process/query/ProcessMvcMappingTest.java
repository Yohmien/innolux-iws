/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 当前编译产物中的 process/endpoint MVC 映射冲突检查；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读取当前 main 编译产物中的控制器注解，检查同方法同路径冲突。
 *
 * <p>该检查不启动 Web 容器，也不依赖运行中的共享树；编译后的 class 即测试目标。</p>
 */
@Tag("dev")
class ProcessMvcMappingTest {

    /**
     * process/endpoint 控制器不得存在同 HTTP 方法、同归一化路径的映射。
     */
    @Test
    void noDuplicateProcessOrEndpointMappings() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.setEnvironment(new org.springframework.mock.env.MockEnvironment()
            .withProperty("iws.process.enabled", "true").withProperty("iws.endpoint.enabled", "true"));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> classNames = new ArrayList<>();
        for (String basePackage : List.of("com.innolux.iws.process.controller",
            "com.innolux.iws.endpoint.controller")) {
            for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
                classNames.add(definition.getBeanClassName());
            }
        }
        assertFalse(classNames.isEmpty(), "未扫描到任何 REST 控制器");

        Map<String, String> mappings = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (String className : classNames) {
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("无法加载控制器: " + className, ex);
            }
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type,
                RequestMapping.class);
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method,
                    RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                for (String path : combinedPaths(classMapping, methodMapping)) {
                    for (String verb : verbs(methodMapping)) {
                        String key = verb + " " + normalize(path);
                        String owner = type.getSimpleName() + "#" + method.getName();
                        String previous = mappings.putIfAbsent(key, owner);
                        if (previous != null) {
                            conflicts.add(key + " -> " + previous + " / " + owner);
                        }
                    }
                }
            }
        }
        assertTrue(conflicts.isEmpty(), "存在重复 MVC 映射: " + conflicts);
    }

    private List<String> combinedPaths(RequestMapping classMapping, RequestMapping methodMapping) {
        List<String> classPaths = classMapping == null || classMapping.path().length == 0
            ? List.of("") : List.of(classMapping.path());
        List<String> methodPaths = methodMapping.path().length == 0
            ? List.of("") : List.of(methodMapping.path());
        List<String> result = new ArrayList<>();
        for (String base : classPaths) {
            for (String path : methodPaths) {
                result.add(base + "/" + path);
            }
        }
        return result;
    }

    private List<String> verbs(RequestMapping mapping) {
        RequestMethod[] methods = mapping.method();
        if (methods.length == 0) {
            return List.of("ANY");
        }
        List<String> result = new ArrayList<>();
        for (RequestMethod method : methods) {
            result.add(method.name());
        }
        return result;
    }

    private String normalize(String path) {
        String value = ("/" + path).replaceAll("/+", "/");
        value = value.replaceAll("\\{[^/}]+}", "{}");
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
