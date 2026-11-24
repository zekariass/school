package com.nemaz.school.tenancy.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.tenancy.domain.SchoolLanguage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolLanguageRepository extends JpaRepository<SchoolLanguage, SchoolLanguage.Key> {

	List<SchoolLanguage> findBySchoolIdInOrderBySchoolIdAscLanguageCodeAsc(Collection<UUID> schoolIds);

}
