package com.rageval.workbench.persistence;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres-only Testcontainers wiring for persistence-layer integration tests. Deliberately
 * separate from {@code TestcontainersConfiguration} (which also starts an Ollama container for
 * the full application context-loads test) — these tests exercise Liquibase/JPA/JSONB mapping
 * only and gain nothing from an Ollama container, which would just slow the build down.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
    }
}
