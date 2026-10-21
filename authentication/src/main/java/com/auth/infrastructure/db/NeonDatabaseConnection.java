package com.auth.infrastructure.db;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class NeonDatabaseConnection {

    public static final String DEFAULT_NEON_URL = "postgresql://user:password@ep-your-endpoint.us-east-2.aws.neon.tech/neondb?sslmode=require";

    private static volatile HikariDataSource dataSource;
    private static String customUrl;
    private static final Map<String, String> dotEnv = new HashMap<>();

    static {
        loadDotEnv();
    }

    private NeonDatabaseConnection() {}

    public static synchronized void setConnectionUrl(String url) {
        customUrl = url;
        close();
    }

    public static String getConnectionUrl() {
        return resolveConnectionUrl();
    }

    public static HikariDataSource getDataSource() {
        if (dataSource == null) {
            synchronized (NeonDatabaseConnection.class) {
                if (dataSource == null) {
                    HikariConfig config = buildHikariConfig(resolveConnectionUrl());
                    dataSource = new HikariDataSource(config);
                }
            }
        }
        return dataSource;
    }

    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    public static synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
        }
    }

    public static void importAuthTables() throws SQLException, IOException {
        Path schemaFile = findAuthSchemaFile();
        importAuthTables(schemaFile);
    }

    public static void importAuthTables(Path schemaFilePath) throws SQLException, IOException {
        if (schemaFilePath == null || !Files.exists(schemaFilePath)) {
            throw new IOException("Could not locate auth_schema.sql at: " +
                    (schemaFilePath != null ? schemaFilePath.toAbsolutePath() : "null"));
        }

        System.out.println("Reading auth schema from: " + schemaFilePath.toAbsolutePath());
        String sql = new String(Files.readAllBytes(schemaFilePath), StandardCharsets.UTF_8);

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            System.out.println("Executing auth schema against Neon Postgres database...");
            stmt.execute(sql);
            System.out.println("Auth tables successfully imported to Neon PostgreSQL!");
        }
    }

    public static void executeSql(String sql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static HikariConfig buildHikariConfig(String rawUrl) {
        String url = (rawUrl != null && !rawUrl.isBlank()) ? rawUrl.trim() : resolveConnectionUrl();

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");

        if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
            try {
                URI uri = URI.create(url);
                String userInfo = uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    config.setUsername(parts[0]);
                    config.setPassword(parts[1]);
                }

                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getPath();
                String dbName = (path != null && path.length() > 1) ? path.substring(1) : "neondb";
                String query = uri.getQuery();

                StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                        .append(host)
                        .append(":")
                        .append(port)
                        .append("/")
                        .append(dbName);

                boolean hasSsl = false;
                if (query != null && !query.isBlank()) {
                    jdbcUrl.append("?").append(query);
                    hasSsl = query.contains("sslmode=");
                }
                if (!hasSsl) {
                    jdbcUrl.append(query != null && !query.isBlank() ? "&" : "?")
                            .append("sslmode=require");
                }

                config.setJdbcUrl(jdbcUrl.toString());
            } catch (Exception e) {
                String fallbackUrl = url.replaceFirst("^(postgres|postgresql)://", "jdbc:postgresql://");
                if (!fallbackUrl.contains("sslmode=")) {
                    fallbackUrl += (fallbackUrl.contains("?") ? "&" : "?") + "sslmode=require";
                }
                config.setJdbcUrl(fallbackUrl);
            }
        } else if (url.startsWith("jdbc:postgresql://")) {
            String jdbcUrl = url;
            if (!jdbcUrl.contains("sslmode=")) {
                jdbcUrl += (jdbcUrl.contains("?") ? "&" : "?") + "sslmode=require";
            }
            config.setJdbcUrl(jdbcUrl);
        } else {
            config.setJdbcUrl(url);
        }

        config.setPoolName("NeonHikariPool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(30000);
        config.setConnectionTimeout(20000);
        config.setMaxLifetime(1800000);
        config.setKeepaliveTime(60000);

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        return config;
    }

    private static String resolveConnectionUrl() {
        if (customUrl != null && !customUrl.isBlank()) {
            return customUrl;
        }

        if (dotEnv.containsKey("DATABASE_URI") && !dotEnv.get("DATABASE_URI").isBlank()) {
            return dotEnv.get("DATABASE_URI");
        }
        if (dotEnv.containsKey("DATABASE_URL") && !dotEnv.get("DATABASE_URL").isBlank()) {
            return dotEnv.get("DATABASE_URL");
        }
        if (dotEnv.containsKey("NEON_DATABASE_URL") && !dotEnv.get("NEON_DATABASE_URL").isBlank()) {
            return dotEnv.get("NEON_DATABASE_URL");
        }
        if (dotEnv.containsKey("DB_URL") && !dotEnv.get("DB_URL").isBlank()) {
            return dotEnv.get("DB_URL");
        }

        String envUrl = System.getenv("DATABASE_URI");
        if (envUrl != null && !envUrl.isBlank()) return envUrl;

        envUrl = System.getenv("NEON_DATABASE_URL");
        if (envUrl != null && !envUrl.isBlank()) return envUrl;

        envUrl = System.getenv("DATABASE_URL");
        if (envUrl != null && !envUrl.isBlank()) return envUrl;

        envUrl = System.getenv("DB_URL");
        if (envUrl != null && !envUrl.isBlank()) return envUrl;

        return DEFAULT_NEON_URL;
    }

    private static void loadDotEnv() {
        Path[] envPaths = new Path[]{
                Paths.get(".env"),
                Paths.get("src/main/java/com/auth/.env"),
                Paths.get("../.env"),
                Paths.get(System.getProperty("user.dir", "."), ".env"),
                Paths.get(System.getProperty("user.dir", "."), "src/main/java/com/auth/.env")
        };

        for (Path path : envPaths) {
            if (Files.exists(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        int eqIdx = trimmed.indexOf('=');
                        if (eqIdx > 0) {
                            String key = trimmed.substring(0, eqIdx).trim();
                            String val = trimmed.substring(eqIdx + 1).trim();
                            if ((val.startsWith("\"") && val.endsWith("\"")) ||
                                (val.startsWith("'") && val.endsWith("'"))) {
                                val = val.substring(1, val.length() - 1);
                            }
                            dotEnv.put(key, val);
                        }
                    }
                    return;
                } catch (Exception ignored) {}
            }
        }
    }

    private static Path findAuthSchemaFile() {
        Path[] candidates = new Path[]{
                Paths.get("auth_schema.sql"),
                Paths.get("../auth_schema.sql"),
                Paths.get("../../auth_schema.sql"),
                Paths.get(System.getProperty("user.dir", "."), "auth_schema.sql")
        };

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return Paths.get("auth_schema.sql");
    }

    public static void main(String[] args) {
        System.out.println("Testing Neon PostgreSQL connection using HikariCP...");
        try (Connection conn = getConnection()) {
            System.out.println("Successfully connected to Neon PostgreSQL! Database: " + conn.getCatalog());
            System.out.println("Importing auth tables...");
            importAuthTables();
            System.out.println("Done! Tables (users, user_identities, user_tokens) are ready in Neon.");
        } catch (Exception e) {
            System.err.println("Error connecting or importing schema to Neon: " + e.getMessage());
            e.printStackTrace();
        } finally {
            close();
        }
    }
}
