package com.nemaz.school.media.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaStatus;
import com.nemaz.school.media.MediaVariant;
import com.nemaz.school.media.application.MediaDetails;
import com.nemaz.school.media.application.MediaReads;
import com.nemaz.school.media.application.MediaReads.PurposeLimits;
import com.nemaz.school.media.application.MediaReads.SignedUrl;
import com.nemaz.school.media.application.MediaUploads;
import com.nemaz.school.media.application.MediaUploads.Ticket;
import com.nemaz.school.media.application.MediaUploads.UploadRequest;
import com.nemaz.school.media.application.RequestedVariant;
import com.nemaz.school.media.domain.Media;
import com.nemaz.school.media.domain.MediaVariantRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Uploads and signed reads (Implementation Plan M2.1 and M2.2). Clients send the bytes
 * straight to object storage with the presigned {@code PUT}, then call {@code complete}.
 * Who may do what is decided per purpose by the owning module's policy (R-30); there is no
 * role check here, and media a caller may not see is 404.
 */
@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "media")
public class MediaController {

	/** A little shorter than the signed URL's life, so a cached answer never hands out an expired link. */
	private static final Duration URL_CACHE = Duration.ofMinutes(4);

	private final MediaUploads uploads;

	private final MediaReads reads;

	MediaController(MediaUploads uploads, MediaReads reads) {
		this.uploads = uploads;
		this.reads = reads;
	}

	@GetMapping("/policies")
	@Operation(operationId = "listMediaPolicies",
			summary = "The purposes that accept uploads, with allowed types and size limits")
	public PoliciesResponse policies() {
		return new PoliciesResponse(this.reads.policies().stream().map(PurposePolicy::from).toList());
	}

	@PostMapping("/uploads")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "requestMediaUpload",
			summary = "Start an upload: validates purpose, type and size, and returns a presigned PUT URL (10 minutes)")
	public UploadResponse requestUpload(@Valid @RequestBody UploadRequestBody r) {
		Ticket ticket = this.uploads.request(CurrentUser.require(),
				new UploadRequest(r.purpose(), r.filename(), r.mimeType(), r.sizeBytes(), r.sha256(),
						(r.context() != null) ? new MediaContext(r.context().type(), r.context().id()) : null));
		return new UploadResponse(ticket.mediaId(), new UploadTarget("PUT", ticket.upload().url(),
				ticket.upload().headers(), ticket.upload().expiresAt()));
	}

	@PostMapping("/{id}/complete")
	@Operation(operationId = "completeMediaUpload",
			summary = "Verify the uploaded object (size, type, magic bytes) and queue processing")
	public MediaResponse complete(@PathVariable UUID id) {
		return MediaResponse.from(this.uploads.complete(CurrentUser.require(), id));
	}

	@GetMapping("/{id}")
	@Operation(operationId = "getMedia", summary = "Status and metadata of a media file")
	public MediaResponse get(@PathVariable UUID id) {
		return MediaResponse.from(this.reads.get(CurrentUser.require(), id));
	}

	@GetMapping("/{id}/url")
	@Operation(operationId = "getMediaUrl",
			summary = "A signed GET link (5 minutes) for a variant; images need THUMB, SMALL or MEDIUM")
	public ResponseEntity<UrlResponse> url(@PathVariable UUID id, @RequestParam(required = false) RequestedVariant variant) {
		SignedUrl signed = this.reads.url(CurrentUser.require(), id, variant);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.maxAge(URL_CACHE).cachePrivate())
			.body(new UrlResponse(signed.url(), signed.expiresAt(), (variant != null) ? variant : RequestedVariant.ORIGINAL));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteMedia", summary = "Delete a media file (uploader or manager of its context)")
	public void delete(@PathVariable UUID id) {
		this.reads.delete(CurrentUser.require(), id);
	}

	// --- requests ---------------------------------------------------------------------

	public record ContextRef(@NotNull ContextType type, @NotNull UUID id) {
	}

	public record UploadRequestBody(@NotNull MediaPurpose purpose,
			@NotBlank @Size(max = 255, message = "validation.tooLong") String filename,
			@NotBlank @Size(max = 127, message = "validation.tooLong") String mimeType,
			@NotNull @Schema(description = "Exact size of the file in bytes") Long sizeBytes,
			@Schema(description = "Lower-case hex SHA-256 of the file; verified during processing") @Size(max = 64) String sha256,
			@Valid @Schema(description = "What the media belongs to; optional for AVATAR (the caller)") ContextRef context) {
	}

	// --- responses --------------------------------------------------------------------

	public record UploadTarget(@NotNull String method, @NotNull URI url,
			@NotNull @Schema(description = "Headers the PUT must carry") Map<String, String> headers,
			@NotNull Instant expiresAt) {
	}

	public record UploadResponse(@NotNull UUID mediaId, @NotNull UploadTarget upload) {
	}

	public record MediaResponse(@NotNull UUID id, @NotNull MediaPurpose purpose, @NotNull MediaStatus status,
			@NotNull MediaKind kind, @NotNull String mimeType, Long sizeBytes, Integer width, Integer height,
			Integer pageCount, Integer durationMs, @NotNull List<MediaVariant> variants,
			@Schema(description = "Why FAILED or QUARANTINED") String failureReason, @NotNull Instant createdAt) {

		static MediaResponse from(MediaDetails d) {
			Media m = d.media();
			return new MediaResponse(m.getId(), m.getPurpose(), m.getStatus(), m.getMediaKind(), m.getMimeType(),
					m.getSizeBytes(), m.getWidth(), m.getHeight(), m.getPageCount(), m.getDurationMs(),
					d.variants().stream().map(MediaVariantRow::getVariant).sorted().toList(), m.getFailureReason(),
					m.getCreatedAt());
		}

	}

	public record UrlResponse(@NotNull URI url, @NotNull Instant expiresAt, @NotNull RequestedVariant variant) {
	}

	public record PurposePolicy(@NotNull MediaPurpose purpose, @NotNull List<ContextType> contextTypes,
			@NotNull @Schema(description = "Allowed MIME types with their maximum size in bytes") Map<String, Long> maxBytesByMimeType) {

		static PurposePolicy from(PurposeLimits l) {
			return new PurposePolicy(l.purpose(), l.contextTypes(), l.maxBytesByMimeType());
		}

	}

	public record PoliciesResponse(@NotNull List<PurposePolicy> purposes) {
	}

}
