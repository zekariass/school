package com.nemaz.school.realtime.api;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.realtime.application.RealtimeTickets;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/realtime")
@Tag(name = "realtime")
public class RealtimeTicketController {

	private final RealtimeTickets tickets;

	RealtimeTicketController(RealtimeTickets tickets) {
		this.tickets = tickets;
	}

	@PostMapping("/tickets")
	@Operation(operationId = "createRealtimeTicket",
			summary = "A single-use ticket (60 seconds) to open the WebSocket at /ws from a browser, sent as the STOMP CONNECT header 'ticket'")
	public ResponseEntity<TicketResponse> create() {
		String ticket = this.tickets.issue(CurrentUser.require());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
				new TicketResponse(ticket, RealtimeTickets.TTL.toSeconds()));
	}

	public record TicketResponse(@NotNull String ticket,
			@NotNull @Schema(description = "Seconds the ticket stays valid") long expiresIn) {

		@Override
		public String toString() {
			return "TicketResponse[redacted]";
		}

	}

}
