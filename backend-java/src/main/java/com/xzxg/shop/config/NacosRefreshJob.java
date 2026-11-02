package com.xzxg.shop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pulls the Nacos data id on a fixed interval and applies changed values live. */
@Component
@ConditionalOnProperty(prefix = "shop.nacos", name = "enabled", havingValue = "true")
public class NacosRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(NacosRefreshJob.class);

    private final NacosConfigClient client;
    private final ConfigService configs;

    public NacosRefreshJob(NacosConfigClient client, ConfigService configs) {
        this.client = client;
        this.configs = configs;
    }

    @Scheduled(fixedDelayString = "${shop.nacos.refresh-ms:30000}",
            initialDelayString = "${shop.nacos.refresh-ms:30000}")
    public void refresh() {
        try {
            var items = client.load();
            if (!items.isEmpty()) {
                configs.mergeRemote(items);
            }
        } catch (Exception ex) {
            log.debug("nacos refresh failed, keeping current config: {}", ex.getMessage());
        }
    }
}
