package com.seatvault.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.regex.Pattern;

/**
 * Configures WebSocket message brokering for real-time client updates.
 *
 * <p>The feed is <b>server-to-client only</b>: the endpoint is public (it mirrors the
 * public seat list), so the inbound channel only lets clients subscribe to an
 * event's seat topic. Without this, Spring's simple broker would relay any client
 * SEND to {@code /topic/...} straight to every subscriber, letting anyone push fake
 * seat updates into other people's seat maps.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** The only destination clients may subscribe to: {@code /topic/events/{id}/seats}. */
    private static final Pattern SEAT_TOPIC = Pattern.compile("^/topic/events/\\d+/seats$");

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-seatmap")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                StompCommand command = accessor.getCommand();
                if (command == null) {
                    return message; // heartbeat
                }
                switch (command) {
                    case CONNECT, STOMP, DISCONNECT, UNSUBSCRIBE -> {
                        return message;
                    }
                    case SUBSCRIBE -> {
                        String destination = accessor.getDestination();
                        if (destination != null && SEAT_TOPIC.matcher(destination).matches()) {
                            return message;
                        }
                        throw new MessageDeliveryException("Subscription not allowed: " + destination);
                    }
                    default -> throw new MessageDeliveryException("Clients may not send " + command + " frames");
                }
            }
        });
    }
}
