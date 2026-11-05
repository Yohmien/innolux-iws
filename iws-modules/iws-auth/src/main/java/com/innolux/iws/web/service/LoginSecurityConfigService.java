/*
 * @Date         : 2026-09-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 登录安全配置
 * @Version      : V1.0
 * @Description  : 统一读取登录验证码、Token 活跃超时和固定超时参数；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.web.config.properties.CaptchaProperties;
import com.innolux.iws.system.service.ISysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 登录安全参数服务。
 *
 * <p>系统参数优先生效，参数不存在时回退到 YAML 或客户端表配置，避免老环境升级后
 * 因缺少参数而改变登录行为。</p>
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class LoginSecurityConfigService {

    public static final String CAPTCHA_ENABLED_KEY = "sys.login.captchaEnabled";

    public static final String ACTIVE_TIMEOUT_KEY = "sys.login.activeTimeout";

    public static final String TIMEOUT_KEY = "sys.login.timeout";

    private final CaptchaProperties captchaProperties;

    private final ISysConfigService configService;

    /**
     * 是否启用登录验证码。
     *
     * @return true 表示登录与注册需要校验图形验证码
     */
    public boolean isCaptchaEnabled() {
        return resolveBoolean(CAPTCHA_ENABLED_KEY, Boolean.TRUE.equals(captchaProperties.getEnable()));
    }

    /**
     * 将系统参数中的 token 超时配置应用到 Sa-Token 登录参数。
     *
     * @param parameter 登录参数
     */
    public void applyTokenTimeout(SaLoginParameter parameter) {
        Long activeTimeout = resolveLong(ACTIVE_TIMEOUT_KEY);
        if (activeTimeout != null && activeTimeout > 0) {
            parameter.setActiveTimeout(activeTimeout);
        }
        Long timeout = resolveLong(TIMEOUT_KEY);
        if (timeout != null && timeout > 0) {
            parameter.setTimeout(timeout);
        }
    }

    private boolean resolveBoolean(String key, boolean defaultValue) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> defaultValue;
        };
    }

    private Long resolveLong(String key) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
