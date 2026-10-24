package com.auth.infrastructure.redis;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.Duration;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI; //thread safe
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands; //multiplexing

public class RedisConnection {
    private static final RedisClient client;
    private static final StatefulRedisConnection<String, String> connection;

    static {
        // 1. Try OS Environment variable
        String uriStr = System.getenv("REDIS_URI");

        // 2. Fallback: Read REDIS_URI from your .env file
        if (uriStr == null || uriStr.isBlank()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(".env"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("REDIS_URI=")) {
                        uriStr = line.substring("REDIS_URI=".length()).trim();
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback to localhost if still not found
        if (uriStr == null || uriStr.isBlank()) {
            uriStr = "redis://localhost:6379";
        }

        RedisURI redisURI = RedisURI.create(uriStr);
        redisURI.setTimeout(Duration.ofSeconds(5));

        client = RedisClient.create(redisURI);
        connection = client.connect();
    }

    private RedisConnection() {}

    public static RedisAsyncCommands<String, String> getAsyncCommands() {
        return connection.async();
    }

    public static StatefulRedisConnection<String, String> getConnection() {
        return connection;
    }

    public static void close() {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}

// Multiplexing: ability to send many requests or actions in a single connection (TCP)

// Keys are Strings: e.g., "auth:sess:32bd1e9e-00fb...", "auth:user:priyan:sessions".
// Values are Strings: e.g., "userId", "1792857426", "priyan@example.com".