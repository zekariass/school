package com.nemaz.school.tenancy.infrastructure;

import java.util.UUID;

import com.nemaz.school.tenancy.domain.Organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, UUID>, JpaSpecificationExecutor<Organization> {

	boolean existsBySlug(String slug);

	@Modifying
	@Query("update Organization o set o.logoMediaId = null where o.logoMediaId = :mediaId")
	int clearLogo(@Param("mediaId") UUID mediaId);

}
