package com.xzxg.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xzxg.shop.common.web.RateLimitFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ConfigServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void remoteValuesDriveLiveProperties() {
        ShopProperties properties = new ShopProperties();
        ObjectProvider<NacosConfigClient> nacos = mock(ObjectProvider.class);
        ObjectProvider<RateLimitFilter> rateLimiter = mock(ObjectProvider.class);
        when(nacos.getIfAvailable()).thenReturn(null);
        ConfigService service = new ConfigService(properties, nacos, rateLimiter);

        service.mergeRemote(Map.of(
                "http.cors.allowed_origins", "http://a.example,http://b.example",
                "http.trusted_proxy_cidrs", "10.0.0.0/8",
                "rate_limit.enabled", "true",
                "rate_limit.limit", "7",
                "rate_limit.window_seconds", "30",
                "files.max_upload_bytes", "2048"));

        assertThat(properties.getWeb().getCors().getAllowedOrigins())
                .containsExactly("http://a.example", "http://b.example");
        assertThat(properties.getWeb().getTrustedProxies()).containsExactly("10.0.0.0/8");
        assertThat(properties.getWeb().getRateLimit().isEnabled()).isTrue();
        assertThat(properties.getWeb().getRateLimit().getLimit()).isEqualTo(7);
        assertThat(properties.getWeb().getRateLimit().getWindowSeconds()).isEqualTo(30);
        assertThat(properties.getFiles().getMaxUploadBytes()).isEqualTo(2048L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void updateMasksSecretsAndKeepsMaskedValue() {
        ShopProperties properties = new ShopProperties();
        ObjectProvider<NacosConfigClient> nacos = mock(ObjectProvider.class);
        ObjectProvider<RateLimitFilter> rateLimiter = mock(ObjectProvider.class);
        when(nacos.getIfAvailable()).thenReturn(null);
        ConfigService service = new ConfigService(properties, nacos, rateLimiter);

        service.update("custom.secret_key", "real-value", "string", "", true);
        var masked = service.update("custom.secret_key", "******", "string", "", true);
        assertThat(masked.configValue()).isEqualTo("******");
    }
}
