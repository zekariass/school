package com.nemaz.school.tenancy.infrastructure;

import java.util.UUID;

import com.nemaz.school.tenancy.domain.Campus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CampusRepository extends JpaRepository<Campus, UUID>, JpaSpecificationExecutor<Campus> {

	java.util.List<Campus> findBySchoolIdOrderByNameAscIdAsc(UUID schoolId);

	boolean existsBySchoolIdAndCode(UUID schoolId, String code);

	boolean existsBySchoolIdAndCodeAndIdNot(UUID schoolId, String code, UUID id);

}
