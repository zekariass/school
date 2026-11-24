package com.nemaz.school.reference.infrastructure;

import java.util.Collection;
import java.util.List;

import com.nemaz.school.reference.domain.Currency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CurrencyRepository extends JpaRepository<Currency, String> {

	List<Currency> findByCodeIn(Collection<String> codes);

}
