package com.nemaz.school.sync.api;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.sync.application.SyncService;
import com.nemaz.school.sync.application.SyncService.SyncBatchRequest;
import com.nemaz.school.sync.application.SyncService.SyncBatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The offline sync endpoint. It has no permission of its own: every operation is authorized by its handler exactly as
 * the online endpoint would, when the operation is applied. Not {@code @Idempotent}: every operation carries its own
 * (device, client operation id) key, which is stronger than a 24-hour header and survives the app being killed.
 */
@RestController
@Tag(name = "sync")
public class SyncController {

	private final SyncService sync;

	SyncController(SyncService sync) {
		this.sync = sync;
	}

	@PostMapping("/api/v1/sync")
	@Operation(operationId = "syncOperations",
			summary = "Applies a batch of operations queued by a device (at most 100, 1 MiB; ATTENDANCE_MARK, POINT_AWARD) in order and returns one result per operation, in the same order: APPLIED, DUPLICATE (the operation was seen before: the first outcome is returned, so replaying a whole batch is safe), CONFLICT (the data changed under the client: nothing was overwritten, state says what is there) or REJECTED (cannot succeed as sent: problem.code says why, do not resend). A failing operation never fails the batch or blocks the next one. Always 200 when the request itself is well formed (400 malformed, 413 too large, 429 rate limited). Operations are authorized exactly like their online endpoints, when applied")
	public SyncBatchResponse sync(@Valid @RequestBody SyncBatchRequest request, HttpServletRequest http) {
		long length = http.getContentLengthLong();
		if (length > this.sync.limits().maxRequestBytes()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "sync.payload_too_large");
		}
		return this.sync.sync(CurrentUser.require(), request);
	}

}
