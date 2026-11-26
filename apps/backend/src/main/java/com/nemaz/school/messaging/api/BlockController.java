package com.nemaz.school.messaging.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.messaging.application.BlockService;
import com.nemaz.school.messaging.application.MessagingModels.BlockView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Blocks (rule book R7). A person is blocked through their member id in a conversation, never by user id.
 */
@RestController
@Tag(name = "messaging")
public class BlockController {

	private final BlockService blocks;

	BlockController(BlockService blocks) {
		this.blocks = blocks;
	}

	@GetMapping("/api/v1/me/blocks")
	@Operation(operationId = "listMyBlocks", summary = "The people the caller blocked")
	public List<BlockView> list() {
		return this.blocks.list(CurrentUser.require());
	}

	@PostMapping("/api/v1/me/blocks")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "blockPerson",
			summary = "Blocks a member of a conversation the caller may use: they can no longer start or continue a DIRECT conversation with the caller (the generic refusal), and their messages are hidden from the caller in groups. Never affects announcements or what observers see; a student cannot block their guardians or anybody observing a conversation they are in (403 messaging.cannot_block). A student's block of an adult is audited and listed for school safeguarding reviewers")
	public BlockView block(@Valid @RequestBody BlockPersonRequest request) {
		return this.blocks.create(CurrentUser.require(), request.conversationId(), request.memberId());
	}

	@DeleteMapping("/api/v1/me/blocks/{blockId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "unblockPerson", summary = "Removes a block of the caller's")
	public void unblock(@PathVariable UUID blockId) {
		this.blocks.delete(CurrentUser.require(), blockId);
	}

	public record BlockPersonRequest(@NotNull(message = "validation.required") UUID conversationId,
			@NotNull(message = "validation.required") UUID memberId) {
	}

}
