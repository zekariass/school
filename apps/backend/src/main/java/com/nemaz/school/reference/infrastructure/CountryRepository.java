package com.nemaz.school.reference.infrastructure;

import java.util.List;

import com.nemaz.school.reference.domain.Country;
import com.nemaz.school.reference.domain.CountryStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryRepository extends JpaRepository<Country, String> {

	List<Country> findByStatusOrderByCode(CountryStatus status);

}
