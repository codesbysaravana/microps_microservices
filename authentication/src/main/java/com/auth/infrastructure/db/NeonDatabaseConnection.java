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
        String uri = appConfig.databaseConfig().dbUri();
        if (uri != null && !uri.startsWith("jdbc:")) {
            uri = "jdbc:" + uri;
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(uri);

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
