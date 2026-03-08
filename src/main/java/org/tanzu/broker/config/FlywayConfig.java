package org.tanzu.broker.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    private final DataSource dataSource;

    public FlywayConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return (Flyway flyway) -> {
            if (schemaExists()) {
                log.info("Database schema already exists — skipping Flyway migration "
                    + "(current user may not have DDL privileges)");
                return;
            }
            log.info("Database schema not found — running Flyway migration");
            flyway.migrate();
        };
    }

    private boolean schemaExists() {
        try (var conn = dataSource.getConnection();
             var rs = conn.getMetaData().getTables(null, "public", "stored_tokens", null)) {
            return rs.next();
        } catch (Exception e) {
            log.warn("Could not check schema existence: {}", e.getMessage());
            return false;
        }
    }
}
