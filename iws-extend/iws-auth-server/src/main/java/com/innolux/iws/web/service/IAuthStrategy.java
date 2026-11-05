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

package com.innolux.iws.web.service;


import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.util.ObjectUtil;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.utils.SpringUtils;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.domain.vo.SysClientVo;
import com.innolux.iws.web.domain.vo.LoginVo;

import java.util.function.Consumer;

/**
 * 授权策略
 *
 * @author YONGJIE.PENG
 */
public interface IAuthStrategy {

    String BASE_NAME = "AuthStrategy";

    /**
     * 登录
     *
     * @param body      登录对象
     * @param client    授权管理视图对象
     * @param grantType 授权类型
     * @return 登录验证信息
     */
    static LoginVo login(String body, SysClientVo client, String grantType) {
        // 授权类型和客户端id
        String beanName = grantType + BASE_NAME;
        if (!SpringUtils.containsBean(beanName)) {
            throw new ServiceException("授权类型不正确!");
        }
        IAuthStrategy instance = SpringUtils.getBean(beanName);
        return instance.login(body, client);
    }

    /**
     * 按客户端配置构建统一登录参数。
     *
     * @param client 客户端配置
     * @return Sa-Token 登录参数
     */
    static SaLoginParameter buildLoginParameter(SysClientVo client) {
        return buildLoginParameter(client, null);
    }

    /**
     * 按客户端配置构建统一登录参数，并预留自定义扩展入口。
     *
     * @param client     客户端配置
     * @param customizer 自定义扩展逻辑
     * @return Sa-Token 登录参数
     */
    static SaLoginParameter buildLoginParameter(SysClientVo client, Consumer<SaLoginParameter> customizer) {
        SaLoginParameter model = new SaLoginParameter();
        model.setDeviceType(client.getDeviceType());
        model.setTimeout(client.getTimeout());
        model.setActiveTimeout(client.getActiveTimeout());
        model.setExtra(LoginHelper.CLIENT_KEY, client.getClientId());
        model.setExtra(LoginHelper.CLIENT_ACCESS_PATH_KEY, client.getAccessPath());
        model.setExtra(LoginHelper.CLIENT_IP_WHITELIST_KEY, client.getIpWhitelist());
        SpringUtils.getBean(LoginSecurityConfigService.class).applyTokenTimeout(model);
        if (ObjectUtil.isNotNull(customizer)) {
            customizer.accept(model);
        }
        return model;
    }

    /**
     * 登录
     *
     * @param body   登录对象
     * @param client 授权管理视图对象
     * @return 当前策略完成认证后的登录结果
     */
    LoginVo login(String body, SysClientVo client);

}
