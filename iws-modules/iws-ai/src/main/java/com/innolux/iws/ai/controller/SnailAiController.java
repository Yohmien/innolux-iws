/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.ai.controller;

import com.aizuda.snail.ai.common.execption.SnailAiException;
import com.aizuda.snail.ai.common.model.Result;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserAgentRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserRegisterRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserVO;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiAgentClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiUserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.common.web.core.BaseController;
import com.innolux.iws.system.api.model.LoginUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Snail AI OpenAPI 控制器
 *
 * @author YONGJIE.PENG
 * @date 2026-04-25
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/snail-ai")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "snail-ai.open-api", name = "enabled", havingValue = "true")
public class SnailAiController extends BaseController {

    /**
     * Snail AI 成功状态码。
     */
    private static final int SNAIL_AI_SUCCESS = 1;
    private final OpenApiUserClient userClient;
    private final OpenApiAgentClient agentClient;

    /**
     * 首次注册时自动订阅的默认智能体 ID，与初始化 SQL 的默认助手保持一致。
     */
    @Value("${snail-ai.default-agent-id:1}")
    private Long defaultAgentId;

    /**
     * 注册当前登录用户并返回 OpenAPI 用户信息。
     */
    @PostMapping("/user/register")
    public R<OpenApiUserVO> registerCurrentUser() {
        return R.ok(ensureOpenApiUser());
    }

    /**
     * 确保当前登录用户已注册为 OpenAPI 用户。
     */
    private OpenApiUserVO ensureOpenApiUser() {
        Long userId = LoginHelper.getUserId();
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser == null || userId == null) {
            throw new SnailAiException("当前登录用户为空");
        }

        OpenApiUserRegisterRequest registerRequest = new OpenApiUserRegisterRequest();
        registerRequest.setExternalId(String.valueOf(userId));
        registerRequest.setNickname(loginUser.getNickname());
        Result<OpenApiUserVO> registerResult = userClient.register(registerRequest);
        if (registerResult == null) {
            throw new SnailAiException("注册 OpenAPI 用户失败，返回为空");
        }
        if (registerResult.getStatus() != SNAIL_AI_SUCCESS) {
            throw new SnailAiException(registerResult.getMessage());
        }
        if (registerResult.getData() == null) {
            throw new SnailAiException("注册 OpenAPI 用户失败，返回为空");
        }
        OpenApiUserVO user = registerResult.getData();
        if (user.isCreated()) {
            subscribeDefaultAgent(user);
        }
        return user;
    }

    /**
     * 首次注册自动订阅默认助手，避免用户首次打开 AI 会话时没有可用智能体。
     * 已有用户再次进入不会重复订阅，因此不会覆盖用户后续取消订阅的选择。
     * 订阅失败只记录告警，不阻断注册和 AI 页面加载。
     *
     * @param user 已注册的 OpenAPI 用户
     */
    private void subscribeDefaultAgent(OpenApiUserVO user) {
        OpenApiUserAgentRequest request = new OpenApiUserAgentRequest();
        request.setOpenId(user.getOpenId());
        request.setAgentId(defaultAgentId);
        try {
            Result<Void> result = agentClient.subscribeAgent(request);
            if (result == null || result.getStatus() != SNAIL_AI_SUCCESS) {
                log.warn("首次注册自动订阅默认智能体失败，agentId={}, status={}",
                    defaultAgentId, result == null ? null : result.getStatus());
            }
        } catch (RuntimeException e) {
            log.warn("首次注册自动订阅默认智能体异常，agentId={}, error={}",
                defaultAgentId, e.getClass().getSimpleName());
        }
    }
}
