package com.nemaz.school.reference.infrastructure;

import java.util.Collection;
import java.util.List;

import com.nemaz.school.reference.domain.CountryLanguage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryLanguageRepository extends JpaRepository<CountryLanguage, CountryLanguage.Key> {

	List<CountryLanguage> findByCountryCodeInOrderByCountryCodeAscSortOrderAsc(Collection<String> countryCodes);

}
