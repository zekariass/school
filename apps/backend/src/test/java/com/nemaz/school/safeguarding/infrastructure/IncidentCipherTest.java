package com.nemaz.school.safeguarding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.nemaz.school.common.error.ApiException;

import org.junit.jupiter.api.Test;

/** Rule book S4.4: AES-GCM, a fresh nonce per record, the key id with the ciphertext, rotation, tamper evidence. */
class IncidentCipherTest {

	private static final String K1 = Base64.getEncoder().encodeToString("0123456789abcdefghijklmnopqrstuv".getBytes());

	private static final String K2 = Base64.getEncoder().encodeToString("ZYXWVUTSRQPONMLKJIHGFEDCBA987654".getBytes());

	private static IncidentCipher cipher(String active, String... idsAndKeys) {
		Map<String, String> keys = new LinkedHashMap<>();
		for (int i = 0; i < idsAndKeys.length; i += 2) {
			keys.put(idsAndKeys[i], idsAndKeys[i + 1]);
		}
		return new IncidentCipher(new EncryptionProperties(active, keys));
	}

	@Test
	void theCiphertextHidesTheTextAndEachRecordGetsAFreshNonce() {
		IncidentCipher cipher = cipher("k1", "k1", K1);
		String a = cipher.encrypt("a very private sentence", "incident:1:narrative");
		String b = cipher.encrypt("a very private sentence", "incident:1:narrative");
		assertThat(a).startsWith("v1.k1.").doesNotContain("private");
		assertThat(a).isNotEqualTo(b);
		String[] partsA = a.split("\\.");
		String[] partsB = b.split("\\.");
		assertThat(partsA[2]).isNotEqualTo(partsB[2]); // the nonces differ
		assertThat(partsA[3]).isNotEqualTo(partsB[3]);
		assertThat(Base64.getUrlDecoder().decode(partsA[2])).hasSize(12);
		assertThat(cipher.decrypt(a, "incident:1:narrative")).isEqualTo("a very private sentence");
		assertThat(cipher.decrypt(b, "incident:1:narrative")).isEqualTo("a very private sentence");
		assertThat(IncidentCipher.keyIdOf(a)).isEqualTo("k1");
	}

	@Test
	void rotationReadsOldAndNewRecordsAndNewWritesUseTheActiveKey() {
		IncidentCipher old = cipher("k1", "k1", K1);
		String written = old.encrypt("written under the first key", "note:7");
		IncidentCipher rotated = cipher("k2", "k1", K1, "k2", K2);
		assertThat(rotated.decrypt(written, "note:7")).isEqualTo("written under the first key");
		String fresh = rotated.encrypt("written under the second key", "note:8");
		assertThat(IncidentCipher.keyIdOf(fresh)).isEqualTo("k2");
		assertThat(rotated.decrypt(fresh, "note:8")).isEqualTo("written under the second key");
		// the retired key is the only thing needed to read what it wrote: without it the record cannot be read
		IncidentCipher onlyNew = cipher("k2", "k2", K2);
		assertThatThrownBy(() -> onlyNew.decrypt(written, "note:7")).isInstanceOf(ApiException.class);
	}

	@Test
	void aCiphertextCannotBeMovedToAnotherRecordOrFieldOrBeTamperedWith() {
		IncidentCipher cipher = cipher("k1", "k1", K1);
		String text = cipher.encrypt("bound to its record", "incident:1:narrative");
		assertThatThrownBy(() -> cipher.decrypt(text, "incident:2:narrative")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher.decrypt(text, "incident:1:referral")).isInstanceOf(IllegalStateException.class);
		String[] parts = text.split("\\.");
		byte[] ciphertext = Base64.getUrlDecoder().decode(parts[3]);
		ciphertext[0] ^= 1;
		String tampered = String.join(".", parts[0], parts[1], parts[2], Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
		assertThatThrownBy(() -> cipher.decrypt(tampered, "incident:1:narrative")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher.decrypt("not a ciphertext", "x")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void withoutAnActiveKeyWritesFailClosed() {
		IncidentCipher none = cipher(null);
		assertThat(none.available()).isFalse();
		assertThatThrownBy(() -> none.encrypt("text", "x")).isInstanceOf(ApiException.class)
			.hasMessageContaining("safeguarding.encryption_unavailable");
		IncidentCipher unlisted = cipher("missing", "k1", K1);
		assertThat(unlisted.available()).isFalse();
		// blank slots (an unset SCHOOL_SAFEGUARDING_ENCRYPTION_KEYS_K2) are ignored, a malformed key is an error
		assertThat(cipher("k1", "k1", K1, "k2", "").available()).isTrue();
		assertThatThrownBy(() -> cipher("k1", "k1", "not-base64!")).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-base64");
		assertThatThrownBy(() -> cipher("k1", "k1", Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class);
	}

}
