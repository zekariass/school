package com.nemaz.school;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Integration tests run against throwaway PostgreSQL 18, Redis 7.4 and SeaweedFS (S3) containers,
 * the same versions as local dev (infra/docker-compose.yml).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:18");
	}

	/** Same Redis major version as infra/docker-compose.yml. */
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
	}

	/**
	 * SeaweedFS with its S3 gateway, the local stand-in for Cloudflare R2 (same image and
	 * credentials as infra/docker-compose.yml). The bucket is created by the application
	 * (school.storage.create-bucket in application-test.yaml).
	 */
	@Bean
	GenericContainer<?> seaweedContainer() {
		return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.52"))
			.withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
			.withCopyFileToContainer(MountableFile.forClasspathResource("seaweedfs-s3.json"), "/etc/seaweedfs/s3.json")
			.withExposedPorts(8333)
			.waitingFor(Wait.forHttp("/").forPort(8333).forStatusCodeMatching((code) -> code > 0 && code < 500));
	}

	@Bean
	DynamicPropertyRegistrar storageProperties(@Qualifier("seaweedContainer") GenericContainer<?> seaweed) {
		return (properties) -> properties.add("school.storage.endpoint",
				() -> "http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333));
	}

}
