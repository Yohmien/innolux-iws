/*
 * @Date         : 2026-11-06
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 为外层已登录 IWS 签发嵌入应用一次性桥接票据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamBridgeIssueRequest;
import com.innolux.iws.common.auth.domain.IamBridgeTicketResponse;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.web.config.IamClientProperties;
import com.innolux.iws.web.service.IamCenterClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外层 IWS 嵌入应用桥接控制器。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/bridge")
public class IwsBridgeController {

    private final IamClientProperties properties;
    private final IamCenterClient centerClient;

    /**
     * 申请目标嵌入应用一次性票据。
     *
     * @param request 目标应用和返回路径
     * @return 一次性票据
     */
    @PostMapping("/issue")
    public R<IamBridgeTicketResponse> issue(@Valid @RequestBody BridgeIssueRequest request) {
        StpUtil.checkLogin();
        if (!properties.getBridgeApplications().contains(request.targetApplicationId())) {
            throw new ServiceException("目标应用未开放嵌入桥接", 403);
        }
        String rootSid = sessionValue(IamProtocolConstants.ROOT_SID_SESSION_KEY, "根会话");
        String frontendId = sessionValue(IamProtocolConstants.FRONTEND_ID_SESSION_KEY, "受信前端");
        String sessionId = sessionValue(IamProtocolConstants.APPLICATION_SESSION_ID_KEY, "应用会话");
        Object generation = StpUtil.getTokenSession().get(
            IamProtocolConstants.APPLICATION_SESSION_GENERATION_KEY
        );
        if (!(generation instanceof Number number) || number.longValue() <= 0) {
            throw new ServiceException("当前应用会话代次缺失", 403);
        }
        String terminal = sessionValue(IamProtocolConstants.TERMINAL_SESSION_KEY, "终端");
        if (!IamProtocolConstants.TERMINAL_WEB.equalsIgnoreCase(terminal)) {
            throw new ServiceException("仅 Web 应用会话可申请嵌入桥接", 403);
        }
        return R.ok(centerClient.issueBridgeTicket(new IamBridgeIssueRequest(
            request.targetApplicationId(),
            frontendId,
            sessionId,
            number.longValue(),
            terminal,
            rootSid,
            request.returnPath()
        )));
    }

    private String sessionValue(String key, String label) {
        Object value = StpUtil.getTokenSession().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ServiceException("当前" + label + "信息缺失", 403);
        }
        return value.toString();
    }

    /**
     * 嵌入应用票据申请。
     *
     * @param targetApplicationId 目标应用
     * @param returnPath 目标应用返回路径
     */
    public record BridgeIssueRequest(
        @NotBlank String targetApplicationId,
        String returnPath
    ) {
    }
}
