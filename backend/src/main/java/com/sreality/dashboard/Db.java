package com.sreality.dashboard;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Wraps the Postgres JDBC driver in a HikariCP connection pool. Pool
 * parameters come from {@link Config} so docker-compose env_file is the
 * single source of truth.
 *
 * <p>{@code search_path} is set on each connection so unqualified table
 * names resolve to whichever schema the warehouse lives in — same logic as
 * the original {@code db.py}.</p>
 */
public final class Db {

    private final HikariDataSource pool;

    public Db() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(
            "jdbc:postgresql://" + Config.pgHost() + ":" + Config.pgPort()
                + "/" + Config.pgDatabase()
        );
        cfg.setUsername(Config.pgUsername());
        cfg.setPassword(Config.pgPassword());
        cfg.setMaximumPoolSize(Config.pgPoolSize());
        cfg.setMinimumIdle(1);
        cfg.setPoolName("sreality-dashboard-pool");

        // ``search_path`` is a *session* setting, not a connection-string
        // parameter, so we configure HikariCP to apply it on every new
        // physical connection. Hikari requires this be wrapped via
        // setConnectionInitSql.
        cfg.setConnectionInitSql(
            "SET search_path TO " + escapeIdent(Config.pgSchema()) + ", public"
        );

        // Block any query longer than 30s so a runaway SQL doesn't tie up
        // a Hikari connection forever.
        cfg.addDataSourceProperty("socketTimeout", "30");
        cfg.setConnectionTimeout(10_000);

        this.pool = new HikariDataSource(cfg);
    }

    public DataSource dataSource() {
        return pool;
    }

    /** Close the pool. Called from the JVM shutdown hook in {@link App}. */
    public void close() {
        pool.close();
    }

    /**
     * Crude Postgres identifier quoter — schema names are short and come
     * from our own config, but we still avoid blindly interpolating them
     * into ``SET search_path``. Wraps in double quotes and escapes any
     * embedded ones.
     */
    private static String escapeIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
