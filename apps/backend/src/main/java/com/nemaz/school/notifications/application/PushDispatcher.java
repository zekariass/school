package com.nemaz.school.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nemaz.school.notifications.domain.PushSender;
import com.nemaz.school.notifications.domain.PushSender.PushMessage;
import com.nemaz.school.notifications.domain.PushSender.PushResult;
import com.nemaz.school.notifications.infrastructure.DeliveryStore;
import com.nemaz.school.notifications.infrastructure.DeliveryStore.DeliveryJob;
import com.nemaz.school.notifications.infrastructure.PushDeviceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Sends queued deliveries through the {@link PushSender} and records the outcome: SENT, FAILED
 * with the error (retried by {@link DeliveryRetryJob} up to {@value DeliveryStore#MAX_ATTEMPTS}
 * attempts), or, when the provider says the token is dead, FAILED and the device revoked.
 */
@Component
class PushDispatcher {

	private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

	private final PushSender sender;

	private final DeliveryStore deliveries;

	private final PushDeviceStore devices;

	private final Clock clock;

	PushDispatcher(PushSender sender, DeliveryStore deliveries, PushDeviceStore devices, Clock clock) {
		this.sender = sender;
		this.deliveries = deliveries;
		this.devices = devices;
		this.clock = clock;
	}

	void dispatch(List<DeliveryJob> jobs) {
		if (jobs.isEmpty()) {
			return;
		}
		Instant now = this.clock.instant();
		List<DeliveryJob> sendable = new ArrayList<>();
		for (DeliveryJob job : jobs) {
			if (this.sender.supports(job.provider())) {
				sendable.add(job);
			}
			else {
				this.deliveries.markFailed(job.deliveryId(), "provider_unsupported:" + job.provider(), now);
			}
		}
		if (sendable.isEmpty()) {
			return;
		}
		List<PushMessage> messages = sendable.stream().map(PushDispatcher::message).toList();
		List<PushResult> results;
		try {
			results = this.sender.send(messages);
			if (results.size() != messages.size()) {
				throw new IllegalStateException("push sender returned " + results.size() + " results for "
						+ messages.size() + " messages");
			}
		}
		catch (RuntimeException ex) {
			log.warn("Push send failed: {}", ex.getMessage());
			String error = "send_error: " + ex.getClass().getSimpleName();
			results = messages.stream().map((m) -> PushResult.failed(error)).toList();
		}
		Instant done = this.clock.instant();
		for (int i = 0; i < sendable.size(); i++) {
			DeliveryJob job = sendable.get(i);
			PushResult result = results.get(i);
			switch (result.outcome()) {
				case SENT -> this.deliveries.markSent(job.deliveryId(), result.providerMessageId(), done);
				case DEVICE_NOT_REGISTERED -> {
					this.devices.revoke(job.deviceId(), done);
					this.deliveries.markFailed(job.deliveryId(), result.error(), done);
				}
				case FAILED -> this.deliveries.markFailed(job.deliveryId(), result.error(), done);
			}
		}
	}

	private static PushMessage message(DeliveryJob job) {
		Map<String, Object> data = new LinkedHashMap<>(job.data());
		data.put("notificationId", job.notificationId().toString());
		return new PushMessage(job.provider(), job.token(), job.title(), job.body(), data, job.urgent());
	}

}
