package com.auth.infrastructure.db;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.auth.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class NeonDatabaseConnection {

    private static final HikariDataSource dataSource;

    static {
        Config appConfig = Config.loadConfig();
        String rawUri = appConfig.databaseConfig().dbUri();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("org.postgresql.Driver");

        if (rawUri != null && (rawUri.startsWith("postgres://") || rawUri.startsWith("postgresql://"))) {
            java.net.URI uri = java.net.URI.create(rawUri);
            String userInfo = uri.getUserInfo();
            if (userInfo != null && userInfo.contains(":")) {
                String[] parts = userInfo.split(":", 2);
                hikariConfig.setUsername(parts[0]);
                hikariConfig.setPassword(parts[1]);
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath();
            String query = uri.getQuery();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path + (query != null ? "?" + query : "");
            hikariConfig.setJdbcUrl(jdbcUrl);
        } else {
            hikariConfig.setJdbcUrl(rawUri);
        }

        if (rawUri != null && rawUri.contains("-pooler")) {
            hikariConfig.addDataSourceProperty("prepareThreshold", "0");
        }

        dataSource = new HikariDataSource(hikariConfig);
    }

    private NeonDatabaseConnection() {}

    public static DataSource getDataSource() {
        return dataSource;
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
