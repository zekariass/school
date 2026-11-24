package com.nemaz.school.identity.application;

import com.nemaz.school.identity.infrastructure.UserRepository;
import com.nemaz.school.media.MediaDeleted;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Forgets an avatar whose media was deleted (idempotent: clearing twice changes nothing). */
@Component
class AvatarMediaEvents {

	private final UserRepository users;

	AvatarMediaEvents(UserRepository users) {
		this.users = users;
	}

	@ApplicationModuleListener
	void on(MediaDeleted event) {
		this.users.clearAvatar(event.mediaId());
	}

}
