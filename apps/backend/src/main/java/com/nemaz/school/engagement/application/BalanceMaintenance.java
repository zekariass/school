package com.nemaz.school.engagement.application;

import java.util.List;

import com.nemaz.school.engagement.application.BalanceProjector.Drift;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly check of the {@code point_balances} projection against the ledger (the ledger is the truth): every balance
 * that disagrees (or is missing) is rewritten from the ledger, and the drift is counted in the
 * {@code school.points.balance.repaired} metric and logged. Events still in flight show up as drift too; repairing
 * them early is harmless, because the listener recomputes from the ledger as well.
 */
@Component
public class BalanceMaintenance {

	private static final Logger log = LoggerFactory.getLogger(BalanceMaintenance.class);

	private static final int BATCH = 500;

	private static final int MAX_BATCHES = 200;

	private final BalanceProjector projector;

	private final MeterRegistry meters;

	BalanceMaintenance(BalanceProjector projector, MeterRegistry meters) {
		this.projector = projector;
		this.meters = meters;
	}

	/** @return the number of balances repaired (an Integer: ShedLock refuses primitive return types) */
	@Scheduled(cron = "0 40 2 * * *", zone = "UTC")
	@SchedulerLock(name = "engagement.points-balance-verify", lockAtMostFor = "PT30M")
	public Integer verifyBalances() {
		return repair();
	}

	/** One pass of the nightly job; also what tests and operators call directly. */
	public Integer repair() {
		int repaired = 0;
		for (int batch = 0; batch < MAX_BATCHES; batch++) {
			List<Drift> drift = this.projector.findDrift(BATCH);
			if (drift.isEmpty()) {
				break;
			}
			for (Drift d : drift) {
				this.projector.recompute(d.schoolId(), d.studentId(), d.yearId(), d.context(), d.classId());
			}
			repaired += drift.size();
		}
		if (repaired > 0) {
			this.meters.counter("school.points.balance.repaired").increment(repaired);
			log.warn("Repaired {} point balances that differed from the ledger", repaired);
		}
		return repaired;
	}

}
