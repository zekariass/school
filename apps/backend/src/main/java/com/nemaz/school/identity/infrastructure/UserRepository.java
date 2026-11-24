package com.nemaz.school.identity.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.identity.domain.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** An ACTIVE account whose phone is verified (the only accounts a code is sent to). */
	@Query("""
			select u from User u where u.phoneE164 = :phone and u.phoneVerifiedAt is not null
			and u.status = com.nemaz.school.identity.domain.UserStatus.ACTIVE and u.deletedAt is null""")
	Optional<User> findActiveByVerifiedPhone(@Param("phone") String phoneE164);

	@Query("""
			select u from User u where u.email = :email and u.emailVerifiedAt is not null
			and u.status = com.nemaz.school.identity.domain.UserStatus.ACTIVE and u.deletedAt is null""")
	Optional<User> findActiveByVerifiedEmail(@Param("email") String email);

	Optional<User> findByPhoneE164(String phoneE164);

	Optional<User> findByEmail(String email);

	Optional<User> findByUsername(String username);

}
