package com.innolux.iws.web.service;

import com.innolux.iws.system.service.ISysConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class LoginSecurityConfigServiceTest {

    @Test
    void captchaDefaultsToDisabledWithoutSystemConfig() {
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey(LoginSecurityConfigService.CAPTCHA_ENABLED_KEY))
            .thenReturn(null);
        LoginSecurityConfigService service = new LoginSecurityConfigService(configService);

        assertFalse(service.isCaptchaEnabled());
    }

    @Test
    void systemConfigRemainsTheSingleCaptchaSwitch() {
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey(LoginSecurityConfigService.CAPTCHA_ENABLED_KEY))
            .thenReturn("true");
        LoginSecurityConfigService service = new LoginSecurityConfigService(configService);

        assertTrue(service.isCaptchaEnabled());
    }
}
