package com.nemaz.school.people.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.people.application.LoginCodeService;
import com.nemaz.school.people.application.LoginCodeService.CodeType;
import com.nemaz.school.people.application.LoginCodeService.IssueRequest;
import com.nemaz.school.people.application.LoginCodeService.IssuedCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Printing a class's login-code sheet and revoking a code. Teachers of the class and {@code class.manage}. */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}/classes/{classId}/login-codes")
@Tag(name = "student-login-codes")
public class LoginCodeController {

	private final LoginCodeService codes;

	LoginCodeController(LoginCodeService codes) {
		this.codes = codes;
	}

	@PostMapping
	@Operation(operationId = "issueClassLoginCodes",
			summary = "Issue new login codes for students of the class (default: all ACTIVE students); their previous live codes are revoked. The plaintext codes are in this response only, with Cache-Control: no-store")
	public ResponseEntity<List<IssuedCode>> issue(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody(required = false) IssueCodesRequest r) {
		IssueCodesRequest request = (r != null) ? r : new IssueCodesRequest(null, null, null);
		List<IssuedCode> issued = this.codes.issue(CurrentUser.require(), schoolId, classId,
				new IssueRequest(request.studentIds(), request.type(), request.ttlDays()));
		return ResponseEntity.status(HttpStatus.OK)
			.cacheControl(CacheControl.noStore())
			.header("Pragma", "no-cache")
			.body(issued);
	}

	@DeleteMapping("/{codeId}")
	@Operation(operationId = "revokeClassLoginCode", summary = "Revoke one login code of the class's sheet")
	public ResponseEntity<Void> revoke(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@PathVariable UUID codeId) {
		this.codes.revoke(CurrentUser.require(), schoolId, classId, codeId);
		return ResponseEntity.noContent().build();
	}

	public record IssueCodesRequest(
			@Size(max = 200, message = "validation.tooLong") List<@NotNull UUID> studentIds,
			@Schema(description = "QR, TEXT or BOTH (default TEXT)") CodeType type,
			@Min(value = 1, message = "validation.invalid") @Max(value = 730, message = "validation.invalid") @Schema(description = "Days the codes stay valid (1-730); default setting students.login_code.ttl_days") Integer ttlDays) {
	}

}
