/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : SnailAI 异常映射，输出安全的应用配对提示并限制日志内容；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.ai.handler;

import cn.hutool.http.HttpStatus;
import com.aizuda.snail.ai.common.execption.BaseSnailAiException;
import com.innolux.iws.common.core.domain.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SnailAI 异常处理器。
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SnailAiExceptionHandler {

    /**
     * SnailAI 调用失败时返回的业务错误码。
     */
    private static final int SNAIL_AI_ERROR_CODE = HttpStatus.HTTP_INTERNAL_ERROR;

    /**
     * 应用认证失败时返回的安全提示，不透传 SDK 原文或 HTTP 响应体。
     */
    private static final String AUTHENTICATION_MESSAGE = "SnailAI 应用认证失败。"
        + "请检查 SnailAI 控制台「应用管理」中的应用是否存在并启用，"
        + "并核对主应用 snail-ai.app-id 与 snail-ai.token 是否一致。";

    /**
     * 非认证错误的统一安全提示。
     */
    private static final String DEFAULT_MESSAGE = "SnailAI 调用失败，请检查 SnailAI 服务端状态和应用配置。";

    /**
     * 日志中保留的最大堆栈帧数。
     */
    private static final int MAX_LOG_STACK_FRAMES = 20;

    /**
     * SnailAI 服务端认证失败时返回的错误特征。
     */
    private static final String[] AUTHENTICATION_MARKERS = {
        "应用认证失败", "应用不存在", "应用已禁用", "Token 认证失败", "缺少认证信息"
    };

    /**
     * 处理 SnailAI SDK 异常，返回固定安全提示并记录安全诊断信息。
     * <p>响应和日志均不包含 SDK 异常 message 或 HTTP 响应体。</p>
     *
     * @param e       当前异常
     * @param request 当前请求
     * @return 统一失败响应
     */
    @ExceptionHandler(BaseSnailAiException.class)
    public R<Void> handleSnailAiException(BaseSnailAiException e, HttpServletRequest request) {
        boolean authenticationFailure = containsAuthenticationMarker(e.getMessage());
        logSafely(e, request.getRequestURI(), authenticationFailure);
        String message = authenticationFailure ? AUTHENTICATION_MESSAGE : DEFAULT_MESSAGE;
        return R.fail(SNAIL_AI_ERROR_CODE, message);
    }

    private static boolean containsAuthenticationMarker(String message) {
        if (message == null) {
            return false;
        }
        for (String marker : AUTHENTICATION_MARKERS) {
            if (message.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static void logSafely(BaseSnailAiException e, String requestUri, boolean authenticationFailure) {
        StringBuilder frames = new StringBuilder();
        StackTraceElement[] stackTrace = e.getStackTrace();
        int frameCount = Math.min(stackTrace.length, MAX_LOG_STACK_FRAMES);
        for (int i = 0; i < frameCount; i++) {
            if (i > 0) {
                frames.append(System.lineSeparator());
            }
            frames.append(stackTrace[i]);
        }
        String category = authenticationFailure ? "应用认证失败" : "其他调用失败";
        log.error("请求地址'{}', 发生 SnailAI 异常, 分类: {}, 类型: {}, 堆栈: {}",
            requestUri, category, e.getClass().getName(), frames);
    }
}
