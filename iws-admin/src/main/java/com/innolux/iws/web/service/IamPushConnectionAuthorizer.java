/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 将统一身份根会话和当前应用权限接入 WebSocket 握手；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import com.innolux.iws.common.push.service.PushConnectionAuthorizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * IWS WebSocket 当前身份授权器。
 */
@Component
@RequiredArgsConstructor
public class IamPushConnectionAuthorizer implements PushConnectionAuthorizer {

    private final IamSessionValidationService validationService;

    /**
     * 票据恢复 Token 后重新校验中心根会话和应用接入权限，不能沿用出票时结果。
     *
     * @param token 本地登录 Token
     * @return 是否允许握手
     */
    @Override
    public boolean authorize(String token) {
        return validationService.validate(token, false).valid();
    }
}
