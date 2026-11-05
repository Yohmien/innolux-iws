package com.innolux.iws.auth.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.core.env.Environment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class IamConfigurationValidatorTest {

    @Test
    void appFrontendDoesNotRequirePublicBaseUrl() {
        IamProperties properties = properties(frontend("APP", ""));
        IamConfigurationValidator validator = new IamConfigurationValidator(properties, environment());
        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void appFrontendRejectsPublicBaseUrl() {
        IamProperties properties = properties(frontend("APP", "http://127.0.0.1:9022"));
        IamConfigurationValidator validator = new IamConfigurationValidator(properties, environment());
        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    void browserFrontendRequiresHttpPublicBaseUrl() {
        IamProperties properties = properties(frontend("BROWSER", "http://127.0.0.1:18000"));
        IamConfigurationValidator validator = new IamConfigurationValidator(properties, environment());
        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void appFrontendPassesBeanValidationWithoutPublicBaseUrl() {
        IamProperties properties = properties(frontend("APP", ""));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertTrue(validator.validate(properties.getFrontends().get("pda-main")).isEmpty());
    }

    private IamProperties properties(IamProperties.Frontend frontend) {
        IamProperties properties = new IamProperties();
        properties.setFrontends(Map.of("pda-main", frontend));
        return properties;
    }

    private IamProperties.Frontend frontend(String clientMode, String publicBaseUrl) {
        IamProperties.Frontend frontend = new IamProperties.Frontend();
        frontend.setClientMode(clientMode);
        frontend.setPublicBaseUrl(publicBaseUrl);
        frontend.setLaunchUri("iws-pda://pages/work/index");
        frontend.setRedirectUri("iws-pda://sso/callback");
        frontend.setLogoutUri("iws-pda://pages/login");
        frontend.setTerminal("PDA");
        return frontend;
    }

    private Environment environment() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("sa-token.jwt-secret-key")).thenReturn("test-secret");
        return environment;
    }
}
