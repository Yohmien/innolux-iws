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

package com.innolux.iws.common.push.interceptor;

import com.innolux.iws.common.push.constant.MessageConstants;
import com.innolux.iws.common.push.service.PushConnectionAuthorizer;
import com.innolux.iws.common.push.service.PushTicketService;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手拦截器。
 *
 * @author YONGJIE.PENG
 */
@RequiredArgsConstructor
public class PlusWebSocketInterceptor implements HandshakeInterceptor {

    private static final String TICKET_PARAM = "ticket";
    private static final String CLIENT_ID_PARAM = "clientid";
    private final PushTicketService ticketService;
    private final ObjectProvider<PushConnectionAuthorizer> authorizer;

    /**
     * 握手前提取统一鉴权后的用户信息。
     *
     * @param attributes 用于传递到 WebSocketSession 的属性集合
     * @return 是否允许握手（true=允许，false=拒绝）
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String ticket = query.getFirst(TICKET_PARAM);
        String requestedClientId = query.getFirst(CLIENT_ID_PARAM);
        if (ticket == null || ticket.isBlank() || requestedClientId == null
            || requestedClientId.isBlank()) {
            return false;
        }
        String ticketValue = ticketService.consume(ticket);
        if (ticketValue == null) {
            return false;
        }
        String[] values = ticketValue.split("\\n", 2);
        if (values.length != 2 || !requestedClientId.equals(values[1])) {
            return false;
        }
        LoginUser loginUser = LoginHelper.getLoginUser(values[0]);
        if (loginUser == null) {
            return false;
        }
        PushConnectionAuthorizer connectionAuthorizer = authorizer.getIfAvailable();
        if (connectionAuthorizer != null && !connectionAuthorizer.authorize(values[0])) {
            return false;
        }
        attributes.put(MessageConstants.LOGIN_USER_KEY, loginUser);
        attributes.put(MessageConstants.LOGIN_TOKEN_KEY, values[0]);
        return true;
    }

    /**
     * 握手完成后触发
     * 此处无需处理，留空即可
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
    }
}
