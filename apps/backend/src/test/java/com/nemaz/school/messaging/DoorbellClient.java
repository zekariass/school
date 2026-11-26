package com.nemaz.school.messaging;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.nemaz.school.realtime.RealtimePublisher;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** A real STOMP-over-WebSocket client for the gateway tests. Frames are kept as strings. */
final class DoorbellClient implements AutoCloseable {

	private static final ThreadPoolTaskScheduler SCHEDULER = new ThreadPoolTaskScheduler();

	static {
		SCHEDULER.setPoolSize(1);
		SCHEDULER.setDaemon(true);
		SCHEDULER.initialize();
	}

	final WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());

	/** Payloads of MESSAGE frames. */
	final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

	/** The {@code message} header of ERROR frames (our stable reason code). */
	final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

	final CompletableFuture<StompSession> connected = new CompletableFuture<>();

	final CompletableFuture<StompHeaders> connectedHeaders = new CompletableFuture<>();

	private final CountDownLatch closed = new CountDownLatch(1);

	private StompSession session;

	private DoorbellClient() {
		this.stomp.setMessageConverter(new StringMessageConverter());
		this.stomp.setTaskScheduler(SCHEDULER);
		this.stomp.setDefaultHeartbeat(new long[] { 10_000, 10_000 });
	}

	static DoorbellClient connect(int port, WebSocketHttpHeaders handshake, StompHeaders connect) {
		DoorbellClient client = new DoorbellClient();
		client.stomp.connectAsync("ws://localhost:" + port + "/ws", handshake, connect, new StompSessionHandlerAdapter() {
			@Override
			public void afterConnected(StompSession session, StompHeaders headers) {
				client.session = session;
				client.connectedHeaders.complete(headers);
				client.connected.complete(session);
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				client.errors.add(headers.getFirst("message"));
			}

			@Override
			public void handleTransportError(StompSession session, Throwable exception) {
				client.closed.countDown();
			}

			@Override
			public void handleException(StompSession session, org.springframework.messaging.simp.stomp.StompCommand command,
					StompHeaders headers, byte[] payload, Throwable exception) {
				client.errors.add("exception: " + exception.getMessage());
			}
		}).whenComplete((s, ex) -> {
			if (ex != null) {
				client.errors.add("connect failed: " + ex);
				client.closed.countDown();
			}
		});
		return client;
	}

	static WebSocketHttpHeaders origin(String origin) {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		if (origin != null) {
			headers.setOrigin(origin);
		}
		return headers;
	}

	static StompHeaders bearer(String accessToken) {
		StompHeaders headers = new StompHeaders();
		headers.add("Authorization", "Bearer " + accessToken);
		return headers;
	}

	static StompHeaders ticket(String ticket) {
		StompHeaders headers = new StompHeaders();
		headers.add("ticket", ticket);
		return headers;
	}

	StompSession awaitConnected() throws Exception {
		return this.connected.get(10, TimeUnit.SECONDS);
	}

	private final CountDownLatch ready = new CountDownLatch(1);

	/**
	 * Subscribes and waits until the broker really delivers to the subscription: probe messages are
	 * published to {@code userId} until one arrives (subscribing is asynchronous on the server).
	 * Probes never show up in {@link #messages}.
	 */
	void subscribe(String destination, java.util.UUID userId, RealtimePublisher publisher, String queue) throws Exception {
		StompSession s = awaitConnected();
		s.subscribe(destination, new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return String.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				String text = (String) payload;
				if (text.contains("__ready__")) {
					DoorbellClient.this.ready.countDown();
				}
				else {
					DoorbellClient.this.messages.add(text);
				}
			}
		});
		for (int i = 0; i < 100 && !this.ready.await(100, TimeUnit.MILLISECONDS); i++) {
			publisher.toUser(userId, queue, java.util.Map.of("__ready__", true));
		}
		if (this.ready.getCount() > 0) {
			throw new AssertionError("Subscription to " + destination + " never became active");
		}
	}

	String nextMessage(long seconds) throws InterruptedException {
		return this.messages.poll(seconds, TimeUnit.SECONDS);
	}

	String nextError(long seconds) throws InterruptedException {
		return this.errors.poll(seconds, TimeUnit.SECONDS);
	}

	boolean isOpen() {
		return this.session != null && this.session.isConnected();
	}

	/** True once the connection ended (or never opened) within {@code seconds}. */
	boolean awaitClosed(long seconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (System.nanoTime() < deadline) {
			if (this.closed.getCount() == 0 || (this.session != null && !this.session.isConnected())) {
				return true;
			}
			Thread.sleep(50);
		}
		return false;
	}

	@Override
	public void close() {
		if (this.session != null && this.session.isConnected()) {
			this.session.disconnect();
		}
		this.stomp.stop();
	}

}
