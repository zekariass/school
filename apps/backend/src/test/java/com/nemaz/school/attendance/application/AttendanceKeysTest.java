package com.nemaz.school.attendance.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The keyed hashing of credentials and rotating codes, without a Spring context. */
class AttendanceKeysTest {

	private static String key(String seed) {
		return Base64.getEncoder().encodeToString((seed + "-------------------------------").substring(0, 32).getBytes(StandardCharsets.UTF_8));
	}

	private static AttendanceKeys keys(String active, Map<String, String> credentialKeys, String qrKey) {
		return new AttendanceKeys(new AttendanceSecretsProperties(active, credentialKeys, qrKey));
	}

	private static final UUID SESSION = UUID.fromString("01a21e5f-82ff-7c6b-a17c-6c7e8fbdfb60");

	@Test
	void aTokenHashDependsOnTheKeyAndNeverEqualsTheToken() {
		AttendanceKeys a = keys("a", Map.of("a", key("alpha"), "b", key("beta")), key("qr"));
		Map<String, String> hashes = a.candidateHashes("AbCdEfGhIjKlMnOpQrStUv");
		assertThat(hashes).containsOnlyKeys("a", "b");
		assertThat(hashes.get("a")).isNotEqualTo(hashes.get("b")).hasSize(64).doesNotContain("AbCdEf");
		assertThat(a.hash("AbCdEfGhIjKlMnOpQrStUv")).isEqualTo(hashes.get("a"));
		assertThat(a.activeKeyId()).isEqualTo("a");
		// the purpose prefix: the same token hashed as something else would differ, and another token differs
		assertThat(a.hash("AbCdEfGhIjKlMnOpQrStUw")).isNotEqualTo(hashes.get("a"));
		// a rotated deployment (b active, a kept) hashes new tokens with b and still computes a's candidate
		AttendanceKeys b = keys("b", Map.of("a", key("alpha"), "b", key("beta")), key("qr"));
		assertThat(b.hash("AbCdEfGhIjKlMnOpQrStUv")).isEqualTo(hashes.get("b"));
		assertThat(b.candidateHashes("AbCdEfGhIjKlMnOpQrStUv").get("a")).isEqualTo(hashes.get("a"));
	}

	@Test
	void aCodeIsDeterministicPerSessionAndWindowAndDependsOnTheServerKey() {
		AttendanceKeys a = keys("a", Map.of("a", key("alpha")), key("qr-one"));
		String code = a.code(SESSION, 100);
		assertThat(code).hasSize(22).matches("^[A-Za-z0-9_-]{22}$");
		assertThat(a.code(SESSION, 100)).isEqualTo(code);
		assertThat(a.code(SESSION, 101)).isNotEqualTo(code);
		assertThat(a.code(UUID.randomUUID(), 100)).isNotEqualTo(code);
		assertThat(keys("a", Map.of("a", key("alpha")), key("qr-two")).code(SESSION, 100)).isNotEqualTo(code);
		// the credential pepper is a separate secret: changing it changes no code
		assertThat(keys("a", Map.of("a", key("other")), key("qr-one")).code(SESSION, 100)).isEqualTo(code);
	}

	@Test
	void onlyTheListedWindowsMatch() {
		AttendanceKeys a = keys("a", Map.of("a", key("alpha")), key("qr"));
		String current = a.code(SESSION, 500);
		String previous = a.code(SESSION, 499);
		assertThat(a.matches(SESSION, List.of(500L, 499L), current)).isTrue();
		assertThat(a.matches(SESSION, List.of(500L, 499L), previous)).isTrue();
		assertThat(a.matches(SESSION, List.of(500L, 499L), a.code(SESSION, 498))).isFalse();
		assertThat(a.matches(SESSION, List.of(500L, 499L), a.code(SESSION, 501))).isFalse();
		assertThat(a.matches(SESSION, List.of(500L, 499L), a.code(UUID.randomUUID(), 500))).isFalse();
		assertThat(a.matches(SESSION, List.of(500L, 499L), null)).isFalse();
		assertThat(a.matches(SESSION, List.of(500L, 499L), "")).isFalse();
	}

	@Test
	void withoutSecretsEverythingFailsClosedAndMalformedKeysAreRefused() {
		AttendanceKeys none = keys(null, Map.of(), null);
		assertThat(none.credentialsAvailable()).isFalse();
		assertThat(none.codesAvailable()).isFalse();
		assertThatThrownBy(() -> none.hash("x")).isInstanceOf(ApiException.class).hasMessageContaining("secrets_unavailable");
		assertThatThrownBy(() -> none.code(SESSION, 1)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> none.candidateHashes("x")).isInstanceOf(ApiException.class);
		// an active key id that is not configured, a slot left empty
		assertThat(keys("zzz", Map.of("a", key("alpha")), key("qr")).credentialsAvailable()).isFalse();
		assertThat(keys("a", Map.of("a", key("alpha"), "b", ""), key("qr")).credentialsAvailable()).isTrue();
		assertThatThrownBy(() -> keys("a", Map.of("a", "not base64!"), null)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not base64");
		assertThatThrownBy(() -> keys("a", Map.of("a", Base64.getEncoder().encodeToString(new byte[16])), null)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> keys("a", Map.of("a", key("alpha")), Base64.getEncoder().encodeToString(new byte[8]))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> keys("a", Map.of("bad id!", key("alpha")), null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theSecretsDoNotPrintThemselves() {
		AttendanceSecretsProperties properties = new AttendanceSecretsProperties("a", Map.of("a", key("alpha")), key("qr"));
		assertThat(properties.toString()).doesNotContain(key("alpha")).doesNotContain(key("qr"));
	}

}
