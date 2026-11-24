package com.nemaz.school.tenancy.infrastructure;

import java.util.UUID;

import com.nemaz.school.tenancy.domain.Organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrganizationRepository extends JpaRepository<Organization, UUID>, JpaSpecificationExecutor<Organization> {

	boolean existsBySlug(String slug);

}
