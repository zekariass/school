package com.nemaz.school.identity.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.identity.domain.PasswordCredential;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {

	/** Row-locked, so concurrent attempts are counted one after the other. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from PasswordCredential c where c.userId = :userId")
	Optional<PasswordCredential> lockByUserId(@Param("userId") UUID userId);

}
