package com.xzxg.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xzxg.shop.common.GoTime;
import com.xzxg.shop.common.web.RateLimitFilter;
import com.xzxg.shop.domain.AppConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ConfigServiceTest {

    @SuppressWarnings("unchecked")
    private static ConfigService service(ShopProperties properties) {
        ObjectProvider<NacosConfigClient> nacos = mock(ObjectProvider.class);
        ObjectProvider<RateLimitFilter> rateLimiter = mock(ObjectProvider.class);
        when(nacos.getIfAvailable()).thenReturn(null);
        return new ConfigService(properties, nacos, rateLimiter);
    }

    @Test
    void remoteValuesDriveLiveProperties() {
        ShopProperties properties = new ShopProperties();
        ConfigService service = service(properties);

        service.mergeRemote(List.of(
                config("http.cors.allowed_origins", "http://a.example,http://b.example", false),
                config("http.trusted_proxy_cidrs", "10.0.0.0/8", false),
                config("rate_limit.enabled", "true", false),
                config("rate_limit.limit", "7", false),
                config("rate_limit.window_seconds", "30", false),
                config("files.max_upload_bytes", "2048", false)));

        assertThat(properties.getWeb().getCors().getAllowedOrigins())
                .containsExactly("http://a.example", "http://b.example");
        assertThat(properties.getWeb().getTrustedProxies()).containsExactly("10.0.0.0/8");
        assertThat(properties.getWeb().getRateLimit().isEnabled()).isTrue();
        assertThat(properties.getWeb().getRateLimit().getLimit()).isEqualTo(7);
        assertThat(properties.getWeb().getRateLimit().getWindowSeconds()).isEqualTo(30);
        assertThat(properties.getFiles().getMaxUploadBytes()).isEqualTo(2048L);
    }

    @Test
    void newRemoteSecretKeyIsMasked() {
        ConfigService service = service(new ShopProperties());

        service.mergeRemote(List.of(config("ai.qwen.api_key", "sk-real-secret", false)));

        AppConfig stored = service.list().stream()
                .filter(item -> item.configKey().equals("ai.qwen.api_key"))
                .findFirst().orElseThrow();
        assertThat(stored.isSecret()).isTrue();
        assertThat(stored.configValue()).isEqualTo("******");
        assertThat(service.snapshot().get("ai.qwen.api_key")).isEqualTo("sk-real-secret");
    }

    @Test
    void updateMasksSecretsAndKeepsMaskedValue() {
        ConfigService service = service(new ShopProperties());

        service.update("custom.secret_key", "real-value", "string", "", true);
        var masked = service.update("custom.secret_key", "******", "string", "", true);
        assertThat(masked.configValue()).isEqualTo("******");
        assertThat(service.snapshot().get("custom.secret_key")).isEqualTo("real-value");
    }

    private static AppConfig config(String key, String value, boolean secret) {
        return new AppConfig(key, value, "string", "", "app", secret,
                GoTime.format(LocalDateTime.now()));
    }
}
