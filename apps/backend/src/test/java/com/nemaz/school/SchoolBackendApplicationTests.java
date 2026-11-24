package com.nemaz.school;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchoolBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
