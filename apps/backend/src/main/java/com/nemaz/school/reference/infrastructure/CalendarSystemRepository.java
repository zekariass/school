package com.nemaz.school.reference.infrastructure;

import java.util.Collection;
import java.util.List;

import com.nemaz.school.reference.domain.CalendarSystem;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarSystemRepository extends JpaRepository<CalendarSystem, String> {

	List<CalendarSystem> findByCodeIn(Collection<String> codes);

}
