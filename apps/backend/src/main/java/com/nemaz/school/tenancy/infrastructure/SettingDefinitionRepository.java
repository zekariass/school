package com.nemaz.school.tenancy.infrastructure;

import com.nemaz.school.tenancy.domain.SettingDefinition;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingDefinitionRepository extends JpaRepository<SettingDefinition, String> {

}
