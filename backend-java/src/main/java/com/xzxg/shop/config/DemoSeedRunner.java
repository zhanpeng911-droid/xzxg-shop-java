package com.xzxg.shop.config;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Applies the demo rows that the Go migration file writes unconditionally.
 * Java only does this when {@code shop.seed.demo-enabled=true} (local profile),
 * so a production database is never populated by accident.
 */
@Component
@ConditionalOnProperty(prefix = "shop.seed", name = "demo-enabled", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {

    private final DataSource dataSource;

    public DemoSeedRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed/local-demo.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        DatabasePopulatorUtils.execute(populator, dataSource);
    }
}
