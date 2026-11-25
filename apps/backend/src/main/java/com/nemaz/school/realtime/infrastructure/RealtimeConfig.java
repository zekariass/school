package com.nemaz.school.realtime.infrastructure;

import java.util.Map;

import com.nemaz.school.identity.SessionAuthenticator;
import com.nemaz.school.realtime.application.RealtimeConnections;
import com.nemaz.school.realtime.application.RealtimeTickets;
import com.nemaz.school.system.SchoolProperties;
import jakarta.annotation.PreDestroy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * STOMP over a plain WebSocket at {@code /ws} (no SockJS), with a simple in-memory broker for
 * {@code /queue} (per instance; {@link RedisRealtimeBus} connects instances). Heartbeats are
 * 10 s each way, frames are limited to 64 KB, and a socket that sends no CONNECT within 10 s is
 * dropped. The HTTP handshake is open: authentication happens at STOMP CONNECT
 * ({@link StompAuthInterceptor}). Allowed origins are {@code school.cors.allowed-origins}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class RealtimeConfig implements WebSocketMessageBrokerConfigurer {

	static final int MESSAGE_LIMIT = 64 * 1024;

	private final SchoolProperties props;

	private final StompAuthInterceptor auth;

	private final RealtimeConnections connections;

	private final ThreadPoolTaskScheduler heartbeats = new ThreadPoolTaskScheduler();

	RealtimeConfig(SchoolProperties props, @Lazy SessionAuthenticator sessions, @Lazy RealtimeTickets tickets,
			RealtimeConnections connections) {
		this.props = props;
		this.connections = connections;
		this.auth = new StompAuthInterceptor(sessions, tickets, connections);
		this.heartbeats.setPoolSize(1);
		this.heartbeats.setThreadNamePrefix("ws-heartbeat-");
		this.heartbeats.initialize();
	}

	@PreDestroy
	void shutdown() {
		this.heartbeats.shutdown();
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.setErrorHandler(new RealtimeErrorHandler());
		registry.addEndpoint("/ws")
			.setAllowedOrigins(this.props.cors().allowedOrigins().toArray(String[]::new))
			.addInterceptors(new HandshakeInterceptor() {
				@Override
				public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
						WebSocketHandler handler, Map<String, Object> attributes) {
					attributes.put(StompAuthInterceptor.HAS_ORIGIN, request.getHeaders().getOrigin() != null);
					return true;
				}

				@Override
				public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
						WebSocketHandler handler, Exception exception) {
				}
			});
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[] { 10_000, 10_000 })
			.setTaskScheduler(this.heartbeats);
		registry.setUserDestinationPrefix("/user");
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(this.auth);
	}

	@Override
	public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
		registry.setMessageSizeLimit(MESSAGE_LIMIT).setSendBufferSizeLimit(512 * 1024).setSendTimeLimit(10_000)
			.setTimeToFirstMessage(10_000);
		registry.addDecoratorFactory((handler) -> new WebSocketHandlerDecorator(handler) {
			@Override
			public void afterConnectionEstablished(WebSocketSession session) throws Exception {
				RealtimeConfig.this.connections.opened(session);
				super.afterConnectionEstablished(session);
			}

			@Override
			public void afterConnectionClosed(WebSocketSession session,
					org.springframework.web.socket.CloseStatus closeStatus) throws Exception {
				RealtimeConfig.this.connections.closed(session.getId());
				super.afterConnectionClosed(session, closeStatus);
			}
		});
	}

	/** Tomcat's default text buffer is 8 KB; allow the 64 KB the gateway enforces itself. */
	@Bean
	static ServletServerContainerFactoryBean webSocketContainer() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxTextMessageBufferSize(MESSAGE_LIMIT);
		container.setMaxBinaryMessageBufferSize(MESSAGE_LIMIT);
		return container;
	}

	@Bean
	RedisMessageListenerContainer realtimeListenerContainer(RedisConnectionFactory factory, RedisRealtimeBus bus) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(factory);
		container.addMessageListener(bus.userEvents(), new ChannelTopic(RedisRealtimeBus.USER_EVENTS));
		container.addMessageListener(bus.sessionEvents(), new ChannelTopic(RedisRealtimeBus.SESSION_EVENTS));
		return container;
	}

}
