package com.nemaz.school.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.application.AuditQueries;
import com.nemaz.school.audit.application.AuditQueries.AuditFilter;
import com.nemaz.school.audit.application.AuditQueries.AuditRecord;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit log for platform administrators (audit.read at platform scope only, for now). */
@RestController
@RequestMapping("/api/v1/platform/audit-logs")
@Tag(name = "platform-audit")
public class AuditLogController {

	private final AuditQueries queries;

	AuditLogController(AuditQueries queries) {
		this.queries = queries;
	}

	@GetMapping
	@PreAuthorize("@authz.platform('" + Permissions.AUDIT_READ + "')")
	@Operation(operationId = "listAuditLogs",
			summary = "Audit entries, newest first; filter by action prefix, actor, target, school and time range")
	public CursorPage<AuditLogResponse> list(@RequestParam(required = false) String actionPrefix,
			@RequestParam(required = false) UUID actorUserId, @RequestParam(required = false) String targetType,
			@RequestParam(required = false) UUID targetId, @RequestParam(required = false) UUID schoolId,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries
			.search(new AuditFilter(actionPrefix, actorUserId, targetType, targetId, schoolId, from, to), cursor, limit)
			.map(AuditLogResponse::from);
	}

	public record AuditLogResponse(@NotNull long id, @NotNull Instant occurredAt, UUID actorUserId,
			List<String> actorRoles, UUID impersonatorUserId, UUID schoolId, @NotNull String action, String targetType,
			UUID targetId, @NotNull @Schema(allowableValues = { "SUCCESS", "DENIED", "FAILURE" }) String outcome,
			String requestId, String ip, String userAgent,
			@Schema(description = "{before, after} of a mutation") JsonNode changes, JsonNode metadata) {

		static AuditLogResponse from(AuditRecord r) {
			return new AuditLogResponse(r.id(), r.occurredAt(), r.actorUserId(), r.actorRoles(), r.impersonatorUserId(),
					r.schoolId(), r.action(), r.targetType(), r.targetId(), r.outcome(), r.requestId(), r.ip(),
					r.userAgent(), r.changes(), r.metadata());
		}

	}

}
