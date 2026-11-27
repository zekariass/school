package com.nemaz.school.attendance;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.Persona;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;

/**
 * Helpers of the QR check-in tests (M7e). The code and hash functions are written out here a second time, from the keys of
 * application-test.yaml, so that the tests check the algorithm and not the implementation against itself.
 */
public abstract class QrTestSupport extends AttendanceTestSupport {

	/** application-test.yaml: school.attendance.secrets.qr-key and the credential pepper keys (t1 is the active one). */
	protected static final String QR_KEY = "FeQupzK7Ifab2dnmv6jYZGFQ+zM9miuCaTETqNihN0I=";

	protected static final String PEPPER_T1 = "7e+HlmBvZ51mlWtawaTop83ghsWRxYWjkpvTtcRRrFQ=";

	protected static final String PEPPER_T0 = "sF1u6bCobKoRwn+Cf5LRZeF8EBa49p72ig4TlQ1M81k=";

	/** 12:00:05 in Addis Ababa on a Friday: five seconds into a 30 s window, far from midnight. */
	protected static final String NOON = "2026-11-27T09:00:05Z";

	protected static final List<String> ALL_METHODS = List.of("MANUAL", "QR_ROTATING", "QR_STATIC");

	@Autowired
	protected CacheManager caches;

	// --- the algorithms, independently -------------------------------------------------------

	public static byte[] hmac(String base64Key, String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
			return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** The code of a rotating session in a window: the first 128 bits of HMAC(qr key, "attendance-qr:" id ":" window). */
	public static String code(UUID session, long window) {
		byte[] full = hmac(QR_KEY, "attendance-qr:" + session + ":" + window);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(full, 16));
	}

	/** The stored hash of a token under a pepper key. */
	protected static String tokenHash(String pepperKey, String token) {
		return java.util.HexFormat.of().formatHex(hmac(pepperKey, "attendance-credential:" + token));
	}

	protected long window(int rotationSeconds) {
		return this.clock.instant().getEpochSecond() / rotationSeconds;
	}

	protected String currentCode(UUID session) {
		return code(session, window(30));
	}

	// --- setup ------------------------------------------------------------------------------

	protected void methods(Setup s, String... methods) {
		setting(s.schoolId(), "attendance.methods.enabled", List.of(methods));
	}

	protected Persona person(String label, UUID userId) {
		return this.personas.personaFor(label, userId);
	}

	/** A student of the setup's class (a persona is returned), in the class since long ago. */
	protected Persona newClassStudent(Setup s, String label) {
		UUID id = this.personas.newStudent(s.schoolId(), label);
		this.personas.addStudentToClass(s.schoolId(), s.classId(), id);
		backdateMemberships(s.classId());
		return person(label, id);
	}

	protected void atNoon() {
		moveClockTo(NOON);
	}

	// --- calls ------------------------------------------------------------------------------

	protected static Map<String, Object> map(Object... keyValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			m.put((String) keyValues[i], keyValues[i + 1]);
		}
		return m;
	}

	protected String qrBase(Setup s) {
		return s.api() + "/attendance/qr-sessions";
	}

	/** Opens a code for the setup's class as {@code as}; returns the session view. */
	protected JsonNode openCode(Setup s, Persona as, Map<String, Object> body, int status) throws Exception {
		return call(HttpMethod.POST, s.rollUrl() + "/qr-sessions", as, body, status);
	}

	protected UUID openCode(Setup s) throws Exception {
		return uuid(openCode(s, s.lead(), map(), 201), "id");
	}

	protected JsonNode checkIn(Setup s, UUID session, Persona as, String code, int status) throws Exception {
		return call(HttpMethod.POST, qrBase(s) + "/" + session + "/check-ins", as, map("code", code), status);
	}

	protected JsonNode sessionDetail(Setup s, UUID session, Persona as) throws Exception {
		return get(qrBase(s) + "/" + session, as);
	}

	/** Issues a credential through the API (as the school administrator) and returns its QR payload. */
	protected String issue(Setup s, UUID studentId) throws Exception {
		JsonNode issued = call(HttpMethod.POST, s.api() + "/students/" + studentId + "/attendance-credentials", s.admin(), null, 201);
		return issued.get("qrPayload").asString();
	}

	protected JsonNode scan(Setup s, Persona as, String token, int status) throws Exception {
		return scan(s, as, map("token", token), status);
	}

	protected JsonNode scan(Setup s, Persona as, Map<String, Object> body, int status) throws Exception {
		return call(HttpMethod.POST, s.rollUrl() + "/scans", as, body, status);
	}

	protected int count(String sql, Object... args) {
		return this.jdbc.queryForObject(sql, Integer.class, args);
	}

	protected List<String> ledgerResults(UUID schoolId) {
		return this.jdbc.queryForList("select result from attendance_checkins where school_id = ? order by checked_in_at, id", String.class, schoolId);
	}

	protected String recordStatus(UUID classId, UUID studentId) {
		List<String> rows = this.jdbc.queryForList("select status from attendance_records where class_id = ? and student_id = ?", String.class,
				classId, studentId);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	protected Instant now() {
		return this.clock.instant();
	}

	protected static List<Object> list(Object... items) {
		return new ArrayList<>(List.of(items));
	}

	protected static UUID newId() {
		return Ids.newId();
	}

}
