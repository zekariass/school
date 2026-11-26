package com.nemaz.school.calendar.application;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The calendar's scheduled job: event reminders (every minute). It is idempotent (a reminder is sent once per person per
 * occurrence whatever the number of runs) and safe on several instances (ShedLock). The locked method returns
 * {@code Integer}: ShedLock refuses primitive return types.
 */
@Component
public class CalendarMaintenance {

	private final EventReminders reminders;

	CalendarMaintenance(EventReminders reminders) {
		this.reminders = reminders;
	}

	@Scheduled(cron = "20 * * * * *", zone = "UTC")
	@SchedulerLock(name = "calendar.send-reminders", lockAtMostFor = "PT10M")
	public Integer sendRemindersJob() {
		return sendReminders();
	}

	/** One pass of the reminder job (also what tests call); returns how many reminders were written. */
	public Integer sendReminders() {
		return this.reminders.run();
	}

}
