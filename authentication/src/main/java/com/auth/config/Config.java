package com.auth.config;

import java.io.BufferedReader;
import java.io.FileReader;

public class Config {

    public record DatabaseConfig(String dbUri) {}

    private final DatabaseConfig databaseConfig;

    public Config(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    public DatabaseConfig databaseConfig() {
        return databaseConfig;
    }

    public static Config loadConfig() {
        String dbUri = System.getenv("DATABASE_URI");
        if (dbUri == null || dbUri.isBlank()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(".env"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("DATABASE_URI=")) {
                        dbUri = line.substring("DATABASE_URI=".length()).trim();
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        DatabaseConfig databaseConfig = new DatabaseConfig(dbUri);
        return new Config(databaseConfig);
    }
}
