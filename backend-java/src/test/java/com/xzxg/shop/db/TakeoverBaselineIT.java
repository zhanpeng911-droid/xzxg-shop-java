package com.xzxg.shop.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Explicit takeover check for a database previously managed by the Go backend.
 * Not part of the default build: run it with SHOP_TAKEOVER_URL pointing at an
 * isolated copy, after verifying the schema (see docs/migration/DB_TAKEOVER.md).
 * It must never run against production.
 */
@EnabledIfEnvironmentVariable(named = "SHOP_TAKEOVER_URL", matches = ".+")
class TakeoverBaselineIT {

    @Test
    void baselineThenMigrateKeepsExistingRows() throws Exception {
        String url = System.getenv("SHOP_TAKEOVER_URL");
        String user = System.getenv().getOrDefault("SHOP_TAKEOVER_USER", "root");
        String password = System.getenv().getOrDefault("SHOP_TAKEOVER_PASSWORD", "");

        int productsBefore = count(url, user, password, "SELECT COUNT(*) FROM products");
        int accountsBefore = count(url, user, password, "SELECT COUNT(*) FROM accounts");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .baselineOnMigrate(false)
                .baselineVersion("1")
                .baselineDescription("go-baseline-cabd0a1b")
                .load();
        flyway.baseline();
        flyway.migrate();

        assertThat(count(url, user, password, "SELECT COUNT(*) FROM products"))
                .isEqualTo(productsBefore);
        assertThat(count(url, user, password, "SELECT COUNT(*) FROM accounts"))
                .isEqualTo(accountsBefore);
        assertThat(count(url, user, password,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"))
                .isEqualTo(1);
    }

    private static int count(String url, String user, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
