package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AuthSecretConfigurationTest {
    private String configured(Map<String,Object> variables) throws Exception {
        var properties=new MutablePropertySources();
        properties.addLast(new MapPropertySource("supplied-env",variables));
        for(var source:new YamlPropertySourceLoader().load("app",new ClassPathResource("application.yml"))) properties.addLast(source);
        return new PropertySourcesPropertyResolver(properties).getProperty("upgrade.auth-secret");
    }
    @Test void preferredVariableDoesNotRequireLegacyVariable() throws Exception {
        String secret="integration-only-example-secret-123456789";
        assertThat(configured(Map.of("APP_AUTH_SECRET",secret))).isEqualTo(secret);
        assertThat(configured(Map.of("UPGRADE_AUTH_SECRET",secret))).isEqualTo(secret);
        assertThat(configured(Map.of("APP_AUTH_SECRET",secret,"UPGRADE_AUTH_SECRET","unused"))).isEqualTo(secret);
    }
    @Test void missingSecretStillFailsValidation() throws Exception {
        assertThat(configured(Map.of())).isEmpty();
        assertThatThrownBy(()->new Auth("")).isInstanceOf(IllegalArgumentException.class);
    }
}
