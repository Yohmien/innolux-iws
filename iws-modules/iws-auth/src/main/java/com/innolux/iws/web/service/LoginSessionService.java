/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 为认证中心和目标应用建立相互隔离的本地会话；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysClientVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 应用本地会话创建服务。
 */
@Service
@RequiredArgsConstructor
public class LoginSessionService {

    private final LoginSecurityConfigService loginSecurityConfigService;

    /**
     * 按原 sys_client 渠道和使用场景创建本地 Sa-Token 会话。
     *
     * @param loginUser 原系统登录用户
     * @param client    原登录渠道
     * @param rootSid   中心根会话标识
     * @return 本地应用 Token
     */
    public LocalSession login(LoginUser loginUser, SysClientVo client, String rootSid) {
        SaLoginParameter parameter = new SaLoginParameter();
        parameter.setDeviceType(client.getDeviceType());
        parameter.setTimeout(client.getTimeout());
        parameter.setActiveTimeout(client.getActiveTimeout());
        parameter.setExtra(LoginHelper.CLIENT_KEY, client.getClientId());
        parameter.setExtra(LoginHelper.CLIENT_ACCESS_PATH_KEY, client.getAccessPath());
        parameter.setExtra(LoginHelper.CLIENT_IP_WHITELIST_KEY, client.getIpWhitelist());
        loginSecurityConfigService.applyTokenTimeout(parameter);
        loginUser.setClientKey(client.getClientKey());
        loginUser.setDeviceType(client.getDeviceType());
        LoginHelper.login(loginUser, parameter);
        StpUtil.getTokenSession().set(IamProtocolConstants.ROOT_SID_SESSION_KEY, rootSid);
        return new LocalSession(StpUtil.getTokenValue(), StpUtil.getTokenTimeout(), client.getClientId());
    }

    /**
     * 本地应用会话结果。
     *
     * @param accessToken 本应用 Token
     * @param expireIn    本应用 Token 有效期秒数
     * @param clientId    原登录渠道标识
     */
    public record LocalSession(String accessToken, long expireIn, String clientId) {
    }
}
