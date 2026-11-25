package com.nemaz.school.realtime.infrastructure;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * ERROR frames carry only our stable reason code ({@code auth.invalid_token}, {@code forbidden.destination}, ...),
 * never an exception message, so nothing internal reaches a client.
 */
class RealtimeErrorHandler extends StompSubProtocolErrorHandler {

	@Override
	public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
		String code = "error";
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof RealtimeAccessException access) {
				code = access.code();
				break;
			}
		}
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
		accessor.setMessage(code);
		accessor.setLeaveMutable(true);
		return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
	}

}
