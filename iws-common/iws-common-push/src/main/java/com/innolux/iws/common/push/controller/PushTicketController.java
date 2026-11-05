/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 为浏览器 WebSocket 握手签发短时一次性票据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.push.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.push.annotation.ConditionalOnMessageTransport;
import com.innolux.iws.common.push.service.PushTicketService;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WebSocket 握手票据控制器。
 */
@RestController
@ConditionalOnMessageTransport("websocket")
@RequiredArgsConstructor
public class PushTicketController {

    private static final long TICKET_TIMEOUT_SECONDS = 30L;
    private final PushTicketService ticketService;

    /**
     * 为当前登录上下文签发一次性、单次消费的 WebSocket 握手票据。
     *
     * @return 握手票据
     */
    @PostMapping("${message.path:/resource/message}/ticket")
    public R<PushTicket> ticket() {
        StpUtil.checkLogin();
        Object clientIdValue = StpUtil.getExtra(LoginHelper.CLIENT_KEY);
        if (clientIdValue == null || clientIdValue.toString().isBlank()) {
            throw new ServiceException("WebSocket 登录渠道上下文不完整", 401);
        }
        String clientId = clientIdValue.toString();
        String value = StpUtil.getTokenValue() + "\n" + clientId;
        return R.ok(new PushTicket(
            ticketService.issue(value, TICKET_TIMEOUT_SECONDS),
            TICKET_TIMEOUT_SECONDS
        ));
    }

    /**
     * WebSocket 握手票据。
     *
     * @param ticket           一次性票据
     * @param expiresInSeconds 有效期秒数
     */
    public record PushTicket(String ticket, long expiresInSeconds) {
    }
}
