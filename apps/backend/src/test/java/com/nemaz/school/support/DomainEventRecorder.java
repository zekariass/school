package com.nemaz.school.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.event.EventListener;

/** Remembers every application event of the context, whichever thread published it (for events from background jobs). */
public class DomainEventRecorder {

	private final List<Object> events = new CopyOnWriteArrayList<>();

	@EventListener
	void on(Object event) {
		this.events.add(event);
	}

	public <T> List<T> of(Class<T> type) {
		return this.events.stream().filter(type::isInstance).map(type::cast).toList();
	}

}
