package com.auth;
//using layered architecture here         
import com.auth.handler.AuthHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class Main {
    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(); //connections
        EventLoopGroup workerGroup = new NioEventLoopGroup(); //all traffic

        try {
            ServerBootstrap server = new ServerBootstrap();
            server.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new AuthHandler());
                    }
                });

                System.out.println("Starting Microps Auth port 8082...");
                registeredWithBootAdmin();

                ChannelFuture f = server.bind(8082).sync();
                f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static void registeredWithBootAdmin() {
        try {
            String payload = "{" +
                "\"name\": \"auth-service\"," +
                "\"healthUrl\": \"http://localhost:8082/health\"," +
                "\"managementUrl\": \"http://localhost:8082\"," +
                "\"serviceUrl\": \"http://localhost:8082\"" +
                "}";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8081/instances"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            
             client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Registered with Spring Boot Admin!");
    } catch (Exception e) {
        System.err.println("Failed to register with Boot Admin: " + e.getMessage());
        }
    }
}