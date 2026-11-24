package com.nemaz.school.reference.infrastructure;

import java.util.Collection;
import java.util.List;

import com.nemaz.school.reference.domain.CountryCalendar;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryCalendarRepository extends JpaRepository<CountryCalendar, CountryCalendar.Key> {

	List<CountryCalendar> findByCountryCodeInOrderByCountryCodeAscCalendarCodeAsc(Collection<String> countryCodes);

}
