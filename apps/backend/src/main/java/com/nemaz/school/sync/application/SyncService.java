package com.nemaz.school.sync.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.error.ProblemDetailFactory;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.tx.AdvisoryLocks;
import com.nemaz.school.sync.SyncHandler;
import com.nemaz.school.sync.SyncOperation;
import com.nemaz.school.sync.SyncOutcome;
import com.nemaz.school.sync.SyncStatus;
import com.nemaz.school.system.RateLimiter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sync endpoint's engine. For each operation, in order and in a transaction of its own:
 * <ol>
 * <li>take the advisory lock of (user, device, client operation id), so parallel replays queue up;</li>
 * <li>if the ledger knows the operation, answer DUPLICATE with the first outcome;</li>
 * <li>else check its shape, find its handler and let it apply the operation; the outcome (APPLIED, CONFLICT, or a handler
 * DUPLICATE) is stored in the same transaction as the data it wrote;</li>
 * <li>if the handler refused ({@link ApiException}), the transaction is rolled back (nothing the handler wrote or
 * published survives) and a second one stores the REJECTED outcome, unless the failure is transient (429, 5xx,
 * unexpected): then nothing is stored, the result says {@code retryable: true} and the client may send the same
 * operation again.</li>
 * </ol>
 * Time: the device time is kept as sent ({@code clientCreatedAt}) and clamped for use ({@code recordedAt}): a time in
 * the future (beyond the tolerance) becomes the receipt time and the result says {@code timeAdjusted}; an operation older
 * than the maximum age is REJECTED ({@code sync.operation_too_old}). The server's clock decides everything else.
 */
@Service
public class SyncService {

	private static final Logger log = LoggerFactory.getLogger(SyncService.class);

	private final Map<String, SyncHandler> handlers;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final Clock clock;

	private final SyncProperties props;

	private final RateLimiter limiter;

	private final TransactionTemplate tx;

	private final ProblemDetailFactory problems;

	SyncService(List<SyncHandler> handlers, JdbcClient jdbc, JsonMapper json, Clock clock, SyncProperties props,
			RateLimiter limiter, PlatformTransactionManager transactions, ProblemDetailFactory problems) {
		Map<String, SyncHandler> byType = new HashMap<>();
		for (SyncHandler handler : handlers) {
			if (byType.put(handler.operationType(), handler) != null) {
				throw new IllegalStateException("Two sync handlers for " + handler.operationType());
			}
		}
		this.handlers = Map.copyOf(byType);
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.props = props;
		this.limiter = limiter;
		this.tx = new TransactionTemplate(transactions);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.problems = problems;
	}

	public SyncProperties limits() {
		return this.props;
	}

	public SyncBatchResponse sync(CurrentUser user, SyncBatchRequest request) {
		List<SyncOperationRequest> operations = request.operations();
		if (operations.size() > this.props.maxOperations()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "sync.too_many_operations", this.props.maxOperations());
		}
		long bytes = 0;
		for (SyncOperationRequest op : operations) {
			bytes += size(op.payload());
		}
		if (bytes > this.props.maxRequestBytes()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "sync.payload_too_large");
		}
		this.limiter.consume("sync", user.userId().toString(), this.props.requestsPerMinute(), Duration.ofMinutes(1),
				"sync.rate_limited");
		List<SyncOperationResult> results = new ArrayList<>(operations.size());
		for (SyncOperationRequest op : operations) {
			results.add(processOne(user, op));
		}
		return new SyncBatchResponse(results);
	}

	// --- one operation ----------------------------------------------------------------------

	private SyncOperationResult processOne(CurrentUser user, SyncOperationRequest op) {
		Instant received = this.clock.instant();
		Instant recorded = op.clientCreatedAt();
		boolean adjusted = false;
		if (recorded.isAfter(received.plus(this.props.futureTolerance()))) {
			recorded = received;
			adjusted = true;
		}
		Instant effective = recorded;
		boolean timeAdjusted = adjusted;
		try {
			return this.tx.execute((status) -> apply(user, op, received, effective, timeAdjusted));
		}
		catch (ApiException ex) {
			if (isTransient(ex)) {
				return transientFailure(op, ex);
			}
			return this.tx.execute((status) -> reject(user, op, received, effective, timeAdjusted, ex));
		}
		catch (RuntimeException ex) {
			log.error("Sync operation {} of type {} failed unexpectedly", op.clientOperationId(), op.operationType(), ex);
			return transientFailure(op, new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "sync.operation_failed"));
		}
	}

	private SyncOperationResult apply(CurrentUser user, SyncOperationRequest op, Instant received, Instant recorded,
			boolean adjusted) {
		lock(user, op);
		Optional<SyncOperationResult> known = known(user, op);
		if (known.isPresent()) {
			return known.get();
		}
		if (size(op.payload()) > this.props.maxOperationBytes()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "sync.payload_too_large");
		}
		if (recorded.isBefore(received.minus(this.props.maxOperationAge()))) {
			throw ApiException.unprocessable("sync.operation_too_old");
		}
		SyncHandler handler = this.handlers.get(op.operationType());
		if (handler == null) {
			throw ApiException.unprocessable("sync.unknown_operation");
		}
		if (!handler.entityTypes().contains(op.entityType())) {
			throw ApiException.unprocessable("sync.entity_type_mismatch");
		}
		SyncOutcome outcome = handler.apply(new SyncOperation(op.deviceId(), op.clientOperationId(), op.operationType(),
				op.entityType(), op.baseVersion(), op.payload(), op.clientCreatedAt(), recorded, received), user);
		SyncStatus status = switch (outcome.kind()) {
			case APPLIED -> SyncStatus.APPLIED;
			case CONFLICT -> SyncStatus.CONFLICT;
			case DUPLICATE -> SyncStatus.DUPLICATE;
		};
		ObjectNode stored = this.json.createObjectNode();
		if (outcome.state() != null) {
			stored.set("state", outcome.state());
		}
		if (outcome.version() != null) {
			stored.put("version", outcome.version());
		}
		if (adjusted) {
			stored.put("timeAdjusted", true);
		}
		insert(user, op, outcome.schoolId(), outcome.entityId(), status, null, stored, op.payload(), recorded, received);
		return new SyncOperationResult(op.deviceId(), op.clientOperationId(), status, null, op.operationType(),
				op.entityType(), outcome.entityId(), outcome.version(), outcome.state(), null, adjusted, false);
	}

	private SyncOperationResult reject(CurrentUser user, SyncOperationRequest op, Instant received, Instant recorded,
			boolean adjusted, ApiException ex) {
		lock(user, op);
		Optional<SyncOperationResult> known = known(user, op);
		if (known.isPresent()) {
			return known.get();
		}
		ObjectNode stored = this.json.createObjectNode();
		ObjectNode problem = stored.putObject("problem");
		problem.put("code", ex.code());
		problem.put("status", ex.status().value());
		var args = problem.putArray("args");
		for (Object arg : ex.args()) {
			args.add(String.valueOf(arg));
		}
		if (adjusted) {
			stored.put("timeAdjusted", true);
		}
		JsonNode payload = (size(op.payload()) > this.props.maxOperationBytes()) ? this.json.createObjectNode() : op.payload();
		insert(user, op, null, null, SyncStatus.REJECTED, ex.code(), stored, payload, recorded, received);
		return new SyncOperationResult(op.deviceId(), op.clientOperationId(), SyncStatus.REJECTED, null,
				op.operationType(), op.entityType(), null, null, null, problem(ex.code(), ex.status().value(), ex.args()),
				adjusted, false);
	}

	private SyncOperationResult transientFailure(SyncOperationRequest op, ApiException ex) {
		return new SyncOperationResult(op.deviceId(), op.clientOperationId(), SyncStatus.REJECTED, null,
				op.operationType(), op.entityType(), null, null, null, problem(ex.code(), ex.status().value(), ex.args()),
				false, true);
	}

	/** 429 and 5xx are not verdicts on the operation: nothing is stored and the client may retry it. */
	private static boolean isTransient(ApiException ex) {
		return ex.status().value() == 429 || ex.status().is5xxServerError();
	}

	private SyncProblemView problem(String code, int status, Object[] args) {
		Locale locale = LocaleContextHolder.getLocale();
		String detail = this.problems.create(HttpStatus.valueOf(status), code, args, locale).getDetail();
		return new SyncProblemView(code, status, detail);
	}

	private void lock(CurrentUser user, SyncOperationRequest op) {
		AdvisoryLocks.acquire(this.jdbc, "sync-op:" + user.userId() + ":" + op.deviceId() + ":" + op.clientOperationId());
	}

	/** The stored outcome of an operation seen before, as a DUPLICATE. */
	private Optional<SyncOperationResult> known(CurrentUser user, SyncOperationRequest op) {
		return this.jdbc.sql("""
				select status, operation_type, entity_type, entity_id, cast(result as text) as result
				from sync_operations where user_id = :u and device_id = :d and client_operation_id = :c""")
			.param("u", user.userId())
			.param("d", op.deviceId())
			.param("c", op.clientOperationId())
			.query((rs, i) -> {
				JsonNode result = rs.getString("result") != null ? this.json.readTree(rs.getString("result")) : null;
				SyncProblemView problem = null;
				if (result != null && result.has("problem")) {
					JsonNode p = result.get("problem");
					List<Object> args = new ArrayList<>();
					p.path("args").forEach((a) -> args.add(a.asString()));
					problem = problem(p.path("code").asString(), p.path("status").asInt(400), args.toArray());
				}
				return new SyncOperationResult(op.deviceId(), op.clientOperationId(), SyncStatus.DUPLICATE,
						SyncStatus.valueOf(rs.getString("status")), rs.getString("operation_type"),
						rs.getString("entity_type"), Rows.uuid(rs, "entity_id"),
						(result != null && result.has("version")) ? result.get("version").asInt() : null,
						(result != null && result.has("state")) ? result.get("state") : null, problem,
						result != null && result.path("timeAdjusted").asBoolean(false), false);
			})
			.optional();
	}

	private void insert(CurrentUser user, SyncOperationRequest op, UUID schoolId, UUID entityId, SyncStatus status,
			String problemCode, ObjectNode result, JsonNode payload, Instant recorded, Instant received) {
		this.jdbc.sql("""
				insert into sync_operations (id, user_id, device_id, client_operation_id, school_id, operation_type,
				                             entity_type, entity_id, base_version, payload, status, problem_code, result,
				                             client_created_at, recorded_at, received_at)
				values (:id, :u, :d, :c, :school, :type, :entity, :eid, :base, cast(:payload as jsonb), :status, :problem,
				        cast(:result as jsonb), :created, :recorded, :received)""")
			.param("id", Ids.newId())
			.param("u", user.userId())
			.param("d", op.deviceId())
			.param("c", op.clientOperationId())
			.param("school", schoolId)
			.param("type", op.operationType())
			.param("entity", op.entityType())
			.param("eid", entityId)
			.param("base", op.baseVersion())
			.param("payload", this.json.writeValueAsString(payload))
			.param("status", status.name())
			.param("problem", problemCode)
			.param("result", this.json.writeValueAsString(result))
			.param("created", Timestamp.from(op.clientCreatedAt()))
			.param("recorded", Timestamp.from(recorded))
			.param("received", Timestamp.from(received))
			.update();
	}

	private int size(JsonNode payload) {
		return (payload == null) ? 0 : this.json.writeValueAsString(payload).length();
	}

	/** The handlers' operation types, for the OpenAPI description and tests. */
	public String supportedOperations() {
		return this.handlers.keySet().stream().sorted().collect(Collectors.joining(", "));
	}

	// --- shapes -----------------------------------------------------------------------------

	@Schema(description = "A batch of queued operations, applied in order")
	public record SyncBatchRequest(
			@NotNull(message = "validation.required") @Size(max = 1000, message = "validation.tooLong") @Valid List<@NotNull @Valid SyncOperationRequest> operations) {

		@Override
		public String toString() {
			return "SyncBatchRequest[" + ((this.operations != null) ? this.operations.size() : 0) + " operations]";
		}

	}

	public record SyncOperationRequest(
			@NotNull(message = "validation.required") @Schema(description = "A UUID the app makes once at install and keeps; with the user and clientOperationId it identifies the operation") UUID deviceId,
			@NotNull(message = "validation.required") @Schema(description = "A UUID the app makes once per queued operation and keeps until it has the result") UUID clientOperationId,
			@NotNull(message = "validation.required") @Size(min = 1, max = 40, message = "validation.invalid") @Schema(description = "ATTENDANCE_MARK or POINT_AWARD") String operationType,
			@NotNull(message = "validation.required") @Size(min = 1, max = 40, message = "validation.invalid") @Schema(description = "ATTENDANCE_SESSION for ATTENDANCE_MARK, POINT_TRANSACTION for POINT_AWARD") String entityType,
			@Schema(description = "The version of the entity the client edited from (ATTENDANCE_MARK: the roll's session version, null when the client had no roll)") Integer baseVersion,
			@NotNull(message = "validation.required") @Schema(description = "The operation's data; its shape depends on operationType") JsonNode payload,
			@NotNull(message = "validation.required") @Schema(description = "Device time of the user's action") Instant clientCreatedAt) {

		@Override
		public String toString() {
			return "SyncOperationRequest[" + this.operationType + " " + this.clientOperationId + "]";
		}

	}

	public record SyncBatchResponse(@NotNull List<SyncOperationResult> results) {
	}

	@Schema(description = "The outcome of one operation, in the order of the request")
	public record SyncOperationResult(@NotNull UUID deviceId, @NotNull UUID clientOperationId, @NotNull SyncStatus status,
			@Schema(description = "For DUPLICATE: the status of the first time (APPLIED, CONFLICT, REJECTED)") SyncStatus originalStatus,
			@NotNull String operationType, @NotNull String entityType, UUID entityId,
			@Schema(description = "The entity's version after the operation, when it has one") Integer version,
			@Schema(description = "The server state to show: after an APPLIED, the current state with your rejected values for a CONFLICT. Replays return the first answer unchanged; the retention job removes it later") JsonNode state,
			@Schema(description = "Why a REJECTED operation can never succeed as sent") SyncProblemView problem,
			@Schema(description = "True when the device time was in the future and the receipt time was used") boolean timeAdjusted,
			@Schema(description = "True when the failure was transient (rate limit, server error): nothing was stored, send the same operation again") boolean retryable) {
	}

	public record SyncProblemView(@NotNull String code, int status, @NotNull String detail) {
	}

}
