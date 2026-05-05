package com.sreality.pipeline.shared.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared HikariCP connection pool for all pipeline JARs.
 *
 * Env vars: PG_HOST, PG_PORT, PG_DATABASE, PG_USERNAME, PG_PASSWORD,
 *           PG_SCHEMA (default: public), PG_MAX_POOL_SIZE (default: 3)
 */
public class PostgresConnectionPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresConnectionPool.class);

    private final HikariDataSource ds;
    public  final String           schema;

    public PostgresConnectionPool() {
        String host     = env("PG_HOST",     "localhost");
        String port     = env("PG_PORT",     "5432");
        String database = env("PG_DATABASE", "sreality");
        String username = env("PG_USERNAME", "sreality");
        String password = env("PG_PASSWORD", "changeme");
        int    poolSize = Integer.parseInt(env("PG_MAX_POOL_SIZE", "3"));
        this.schema     = env("PG_SCHEMA",   "public");

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(poolSize);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(30_000);
        hc.setIdleTimeout(600_000);
        hc.setMaxLifetime(1_800_000);
        hc.setPoolName("pipeline-pool");
        hc.addDataSourceProperty("currentSchema", schema);
        this.ds = new HikariDataSource(hc);
        log.info("PG connected: {}/{} schema={}", url, database, schema);
    }

    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    /** Returns schema-qualified table name: schema.tableName */
    public String t(String tableName) {
        return schema + "." + tableName;
    }

    @Override
    public void close() {
        ds.close();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }
}
