package com.nemaz.school.tenancy.infrastructure;

import java.util.UUID;

import com.nemaz.school.tenancy.domain.FeatureOverride;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FeatureOverrideRepository
		extends JpaRepository<FeatureOverride, UUID>, JpaSpecificationExecutor<FeatureOverride> {

}
