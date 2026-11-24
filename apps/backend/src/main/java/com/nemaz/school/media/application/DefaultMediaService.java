package com.nemaz.school.media.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaStatus;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.media.MediaVariant;
import com.nemaz.school.media.domain.Media;
import com.nemaz.school.media.domain.MediaVariantRow;
import com.nemaz.school.media.infrastructure.MediaRepository;
import com.nemaz.school.media.infrastructure.MediaVariantRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultMediaService implements MediaService {

	private final MediaRepository media;

	private final MediaVariantRepository variants;

	private final PurposePolicies policies;

	private final MediaReads reads;

	DefaultMediaService(MediaRepository media, MediaVariantRepository variants, PurposePolicies policies,
			MediaReads reads) {
		this.media = media;
		this.variants = variants;
		this.policies = policies;
		this.reads = reads;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MediaSummary> summary(UUID mediaId) {
		return Optional.ofNullable(summaries(List.of(mediaId)).get(mediaId));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, MediaSummary> summaries(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<MediaVariant>> variantsByMedia = this.variants.findByMediaIdIn(ids)
			.stream()
			.collect(Collectors.groupingBy(MediaVariantRow::getMediaId,
					Collectors.mapping(MediaVariantRow::getVariant, Collectors.toList())));
		Map<UUID, MediaSummary> result = new HashMap<>();
		for (Media m : this.media.findAllById(ids)) {
			if (m.getStatus() == MediaStatus.READY) {
				result.put(m.getId(), new MediaSummary(m.getId(), m.getMediaKind(), m.getMimeType(), m.getWidth(),
						m.getHeight(), variantsByMedia.getOrDefault(m.getId(), List.<MediaVariant>of()).stream().sorted().toList()));
			}
		}
		return result;
	}

	@Override
	public MediaInfo requireAttachable(UUID mediaId, MediaPurpose purpose, MediaContext context, CurrentUser user) {
		Media m = this.media.findById(mediaId)
			.filter((x) -> x.getStatus() != MediaStatus.DELETED)
			.orElseThrow(() -> ApiException.notFound("media.not_found"));
		MediaInfo info = m.info();
		MediaPurposePolicy policy = this.policies.find(purpose).orElseThrow(() -> ApiException.notFound("media.not_found"));
		boolean uploader = m.getOwnerId().equals(user.userId());
		// Somebody else's upload is invisible unless the caller manages the context it was made for.
		if (!uploader && !policy.canManage(user, info)) {
			throw ApiException.notFound("media.not_found");
		}
		if (m.getPurpose() != purpose) {
			throw ApiException.badRequest("media.purpose_mismatch");
		}
		if (!m.context().equals(context)) {
			throw ApiException.badRequest("media.context_mismatch");
		}
		if (!policy.canCreate(user, context)) {
			throw ApiException.forbidden("media.forbidden");
		}
		if (m.getStatus() != MediaStatus.READY) {
			throw ApiException.conflict("media.not_ready");
		}
		return info;
	}

	@Override
	public void discard(UUID mediaId) {
		this.media.lockById(mediaId)
			.filter((m) -> m.getStatus() != MediaStatus.DELETED)
			.ifPresent((m) -> this.reads.release(m, "media.discard"));
	}

}
