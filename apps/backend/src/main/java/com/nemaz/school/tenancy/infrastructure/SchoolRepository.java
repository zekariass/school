package com.nemaz.school.tenancy.infrastructure;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.tenancy.domain.School;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SchoolRepository extends JpaRepository<School, UUID>, JpaSpecificationExecutor<School> {

	boolean existsBySlug(String slug);

	java.util.Optional<School> findBySlug(String slug);

	@Modifying
	@Query("update School s set s.logoMediaId = null where s.logoMediaId = :mediaId")
	int clearLogo(@Param("mediaId") UUID mediaId);

	@Modifying
	@Query("update School s set s.coverMediaId = null where s.coverMediaId = :mediaId")
	int clearCover(@Param("mediaId") UUID mediaId);

	@Query("select s.id from School s where s.organizationId = :organizationId")
	List<UUID> findIdsByOrganizationId(@Param("organizationId") UUID organizationId);

}
