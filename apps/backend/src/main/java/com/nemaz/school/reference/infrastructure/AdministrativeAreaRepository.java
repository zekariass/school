package com.nemaz.school.reference.infrastructure;

import java.util.UUID;

import com.nemaz.school.reference.domain.AdministrativeArea;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdministrativeAreaRepository
		extends JpaRepository<AdministrativeArea, UUID>, JpaSpecificationExecutor<AdministrativeArea> {

}
