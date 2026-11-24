package com.nemaz.school.tenancy.infrastructure;

import com.nemaz.school.tenancy.domain.FeatureDefinition;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureDefinitionRepository extends JpaRepository<FeatureDefinition, String> {

}
