package com.nemaz.school.media.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.media.domain.Media;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaRepository extends JpaRepository<Media, UUID> {

	/** The row, locked until the transaction ends (state transitions of one media are serialized). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Media m where m.id = :id")
	Optional<Media> lockById(@Param("id") UUID id);

	@Query("select m from Media m where m.status = com.nemaz.school.media.MediaStatus.PENDING_UPLOAD and m.createdAt < :before order by m.createdAt")
	List<Media> findStalePending(@Param("before") Instant before, Pageable page);

}
