package org.example.apigateway;

import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.AbstractMap;
import java.util.Map;

@Component
public class WebSocketLoadBalancerFilter implements GlobalFilter, Ordered {
    private final DiscoveryClient discoveryClient;
    private final WebClient webClient;

    public WebSocketLoadBalancerFilter(DiscoveryClient discoveryClient, WebClient.Builder webClientBuilder) {
        this.discoveryClient = discoveryClient;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestPath = exchange.getRequest().getURI().getRawPath();
        String serviceName;
        String healthEndpoint;

        // Determine target service based on the request path
        if(requestPath.startsWith("ws/chat")) {
            serviceName = "steam-chat-service";
            healthEndpoint = "/chat/health/connections";
        } else if(requestPath.startsWith("ws/match")) {
            serviceName = "steam-matchmaking-service";
            healthEndpoint = "/steam-matchmaking/health/connections";
        }
        else if(requestPath.startsWith("ws/game")) {
            serviceName = "steam-game-service";
            healthEndpoint = "steam-game/health/connections";
        }
        else {
            return chain.filter(exchange);
        }


        return Flux.fromIterable(discoveryClient.getInstances(serviceName))
                .flatMap(instance -> webClient.get()
                        .uri(instance.getUri() + healthEndpoint)
                        .retrieve()
                        .bodyToMono(Integer.class)
                        .map(connections -> new AbstractMap.SimpleEntry<>(instance, connections)))
                .sort(Map.Entry.comparingByValue()) // Sort instances by least connections
                .next()
                .flatMap(bestInstance -> {
                    URI baseUri = bestInstance.getKey().getUri();
                    String query = exchange.getRequest().getURI().getQuery(); // Get the original query parameters
                    String newPath = exchange.getRequest().getURI().getRawPath(); // Get the WebSocket path

                    URI newUri = URI.create(baseUri.toString() + newPath + (query != null ? "?" + query : "")); // Append query params
                    ServerHttpRequest request = exchange.getRequest().mutate().uri(newUri).build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    @Override
    public int getOrder() {
        return -1; // Run before routing
    }
}
