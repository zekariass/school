package com.nemaz.school.safeguarding.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.safeguarding.application.LeadService;
import com.nemaz.school.safeguarding.application.SafeguardingModels.CapabilityView;
import com.nemaz.school.safeguarding.application.SafeguardingModels.LeadRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.LeadView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Designating the school's safeguarding leads and the capability the settings screens need (decision 3 of the rule book). */
@RestController
@Tag(name = "safeguarding")
public class LeadController {

	private final LeadService leads;

	LeadController(LeadService leads) {
		this.leads = leads;
	}

	@GetMapping("/api/v1/schools/{schoolId}/safeguarding/capability")
	@Operation(operationId = "getSafeguardingCapability", summary = """
			Whether the school has a safeguarding lead and which settings need one (school.settings.manage): switching on student \
			messaging (messaging.student.*) or student contributions is refused with 409 safeguarding.lead_required until a lead is designated.""")
	public CapabilityView capability(@PathVariable UUID schoolId) {
		return this.leads.capability(CurrentUser.require(), schoolId);
	}

	@GetMapping("/api/v1/schools/{schoolId}/safeguarding/leads")
	@Operation(operationId = "listSafeguardingLeads", summary = "The school's safeguarding leads (school.staff.manage)")
	public List<LeadView> list(@PathVariable UUID schoolId) {
		return this.leads.list(CurrentUser.require(), schoolId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/safeguarding/leads")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "designateSafeguardingLead", summary = """
			Designate an ACTIVE staff member (the principal may be one) as safeguarding lead (school.staff.manage). Grants the \
			SAFEGUARDING_LEAD role at the school; audited. Repeating it changes nothing.""")
	public LeadView designate(@PathVariable UUID schoolId, @Valid @RequestBody LeadRequest request) {
		return this.leads.designate(CurrentUser.require(), schoolId, request.userId());
	}

	@DeleteMapping("/api/v1/schools/{schoolId}/safeguarding/leads/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "removeSafeguardingLead", summary = "Remove a lead (school.staff.manage). The last lead cannot be removed while the school has student messaging or contributions on (409 safeguarding.last_lead_required)")
	public void remove(@PathVariable UUID schoolId, @PathVariable UUID userId) {
		this.leads.remove(CurrentUser.require(), schoolId, userId);
	}

}
