package com.elevator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig — Configures the STOMP-over-WebSocket message broker.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * PROTOCOL STACK: HOW THE COMMUNICATION WORKS
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   Browser ──[HTTP Upgrade]──▶ /ws ──▶ SockJS handshake
 *       │                                     │
 *       │         STOMP over WebSocket         │
 *       │◀────────────────────────────────────▶│
 *       │                                     │
 *   Client sends:  /app/hall-call  {floor:5, direction:"UP"}
 *   Server routes to: @MessageMapping("/hall-call") in ElevatorController
 *   Server broadcasts: /topic/elevator-state  [every tick]
 *   Server responds:   /topic/dispatch-result  [per request]
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * WHY STOMP OVER RAW WEBSOCKET?
 * ═══════════════════════════════════════════════════════════════════════════
 * Raw WebSocket is just a bidirectional byte stream — no routing, no
 * message types, no pub/sub. You'd have to invent all that yourself.
 *
 * STOMP (Simple Text Oriented Messaging Protocol) adds:
 *   - Message routing: SEND /app/hall-call → specific handler
 *   - Topic subscriptions: SUBSCRIBE /topic/state → broadcast to all
 *   - Frame headers: message type, content-type, correlation IDs
 *   - Built-in heartbeat: server↔client keep-alive
 *
 * SockJS adds browser fallbacks (long-polling, XHR-streaming) for
 * environments where WebSocket is blocked (corporate proxies, etc.).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * DESTINATION PREFIXES EXPLAINED
 * ═══════════════════════════════════════════════════════════════════════════
 *   /app    → Application destinations (routed to @MessageMapping handlers)
 *             Client SENDS to /app/hall-call → handled by controller
 *
 *   /topic  → Broker destinations (routed to all subscribers)
 *             Server PUBLISHes to /topic/elevator-state → all clients receive
 *
 * This separation is the pub/sub pattern:
 *   - /app: request-response channel (client → server handler)
 *   - /topic: broadcast channel (server → all clients)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CORS CONFIGURATION
 * ═══════════════════════════════════════════════════════════════════════════
 * allowedOrigins: allows the React dev server (port 5173 from Vite) and
 * any local dev port to connect. In production, restrict to your exact domain.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Registers the STOMP endpoint where clients initially connect.
     *
     * /ws is the handshake URL:
     *   const socket = new SockJS('http://localhost:8080/ws');
     *
     * withSockJS() enables the SockJS fallback transport layer,
     * which tries WebSocket first and falls back to HTTP long-polling
     * if WebSocket is unavailable.
     *
     * allowedOriginPatterns("*") allows all origins during development.
     * ⚠️ In production: replace with your exact domain ("https://myapp.com").
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /**
     * Configures the in-memory message broker.
     *
     * enableSimpleBroker("/topic"):
     *   Activates a simple in-memory pub/sub broker for /topic/* destinations.
     *   All connected clients subscribed to /topic/elevator-state receive
     *   broadcasts from convertAndSend().
     *
     *   In production with high concurrency: replace with a real broker
     *   like RabbitMQ or ActiveMQ via enableStompBrokerRelay().
     *
     * setApplicationDestinationPrefixes("/app"):
     *   Any message sent to /app/** is routed to an @MessageMapping handler.
     *   The /app prefix is stripped before matching (so /app/hall-call
     *   matches @MessageMapping("/hall-call")).
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
