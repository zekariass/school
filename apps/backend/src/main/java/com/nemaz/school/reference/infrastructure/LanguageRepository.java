package com.nemaz.school.reference.infrastructure;

import java.util.Collection;
import java.util.List;

import com.nemaz.school.reference.domain.Language;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LanguageRepository extends JpaRepository<Language, String> {

	List<Language> findByEnabledTrueOrderByCode();

	List<Language> findByCodeIn(Collection<String> codes);

}
