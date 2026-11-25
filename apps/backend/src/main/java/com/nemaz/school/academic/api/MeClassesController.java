package com.nemaz.school.academic.api;

import java.util.List;

import com.nemaz.school.academic.application.MyClassesService;
import com.nemaz.school.academic.application.MyClassesService.MyClass;
import com.nemaz.school.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/classes")
@Tag(name = "me")
public class MeClassesController {

	private final MyClassesService classes;

	MeClassesController(MyClassesService classes) {
		this.classes = classes;
	}

	@GetMapping
	@Operation(operationId = "listMyClasses",
			summary = "The caller's classes as student or teacher across schools, with school and the caller's role (at most 200, active only unless includeArchived)")
	public List<MyClass> list(@RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
		return this.classes.list(CurrentUser.require(), includeArchived);
	}

}
