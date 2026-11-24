package com.nemaz.school.media.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.media.domain.MediaVariantRow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaVariantRepository extends JpaRepository<MediaVariantRow, UUID> {

	List<MediaVariantRow> findByMediaIdOrderByVariant(UUID mediaId);

	List<MediaVariantRow> findByMediaIdIn(Collection<UUID> mediaIds);

	@Modifying
	@Query("delete from MediaVariantRow v where v.mediaId = :mediaId")
	void deleteByMediaId(@Param("mediaId") UUID mediaId);

}
