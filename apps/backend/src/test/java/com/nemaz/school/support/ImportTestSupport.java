package com.nemaz.school.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Drives the roster import API as the wizard does: upload through the media flow, create, map, check, commit. */
public abstract class ImportTestSupport extends MediaTestSupport {

	public static final String CSV = "text/csv";

	public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	protected static String imports(UUID schoolId) {
		return "/api/v1/schools/" + schoolId + "/imports";
	}

	protected ResultActions perform(HttpMethod method, String url, Persona as, Object body, String language) throws Exception {
		MockHttpServletRequestBuilder builder = request(method, url);
		if (body != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body));
		}
		if (as != null) {
			builder = builder.with(as.bearer());
		}
		if (language != null) {
			builder = builder.header("Accept-Language", language);
		}
		return this.mvc.perform(builder);
	}

	/** Calls and asserts the status; returns the parsed body (null when empty). */
	protected JsonNode call(HttpMethod method, String url, Persona as, Object body, int status) throws Exception {
		return call(method, url, as, body, status, null);
	}

	protected JsonNode call(HttpMethod method, String url, Persona as, Object body, int status, String language) throws Exception {
		ResultActions result = perform(method, url, as, body, language);
		var response = result.andReturn().getResponse();
		assertThat(response.getStatus()).as(method + " " + url + " -> " + response.getContentAsString()).isEqualTo(status);
		String content = response.getContentAsString(StandardCharsets.UTF_8);
		return content.isBlank() ? null : this.json.readTree(content);
	}

	protected static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	/** Uploads a file through the media flow (request, PUT to storage, complete, wait) and returns the media id. */
	protected UUID upload(Persona who, UUID schoolId, String filename, String mime, byte[] content) throws Exception {
		JsonNode media = uploadReady(who, "IMPORT_FILE", filename, mime, content, school(schoolId));
		return UUID.fromString(media.get("id").asString());
	}

	protected UUID uploadCsv(Persona who, UUID schoolId, String csv) throws Exception {
		return upload(who, schoolId, "roster.csv", CSV, bytes(csv));
	}

	/** POST /imports: returns the preview. */
	protected JsonNode createImport(Persona who, UUID schoolId, String type, UUID mediaId, Map<String, Object> options)
			throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("importType", type);
		body.put("mediaId", mediaId.toString());
		if (options != null) {
			body.put("options", options);
		}
		return call(HttpMethod.POST, imports(schoolId), who, body, 201);
	}

	/** The mapping of the preview (what the wizard sends back when the user accepts the suggestion). */
	protected static Map<String, Object> mappingOf(JsonNode preview) {
		Map<String, Object> mapping = new LinkedHashMap<>();
		preview.get("suggestedMapping").properties().forEach((e) -> mapping.put(e.getKey(), e.getValue().asInt()));
		return mapping;
	}

	protected JsonNode saveMapping(Persona who, UUID schoolId, UUID jobId, Map<String, Object> mapping, Map<String, Object> options)
			throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("columnMapping", mapping);
		if (options != null) {
			body.put("options", options);
		}
		return call(HttpMethod.PUT, imports(schoolId) + "/" + jobId + "/mapping", who, body, 200);
	}

	protected JsonNode job(Persona who, UUID schoolId, UUID jobId) throws Exception {
		return call(HttpMethod.GET, imports(schoolId) + "/" + jobId, who, null, 200);
	}

	protected JsonNode awaitJob(Persona who, UUID schoolId, UUID jobId, String... statuses) {
		Set<String> wanted = Set.of(statuses);
		JsonNode[] last = new JsonNode[1];
		await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
			last[0] = job(who, schoolId, jobId);
			assertThat(wanted).as("status of " + last[0]).contains(last[0].get("status").asString());
		});
		return last[0];
	}

	/** Create from the file, accept the suggested mapping, run the dry run until it ends; returns the VALIDATED job. */
	protected JsonNode validated(Persona who, UUID schoolId, String type, UUID mediaId, Map<String, Object> options)
			throws Exception {
		JsonNode preview = createImport(who, schoolId, type, mediaId, options);
		UUID jobId = UUID.fromString(preview.get("job").get("id").asString());
		saveMapping(who, schoolId, jobId, mappingOf(preview), options);
		call(HttpMethod.POST, imports(schoolId) + "/" + jobId + "/dry-run", who, null, 202);
		return awaitJob(who, schoolId, jobId, "VALIDATED", "FAILED");
	}

	protected static UUID id(JsonNode job) {
		return UUID.fromString(job.get("id").asString());
	}

	protected List<JsonNode> rows(Persona who, UUID schoolId, UUID jobId, String status) throws Exception {
		List<JsonNode> all = new ArrayList<>();
		String cursor = null;
		do {
			String url = imports(schoolId) + "/" + jobId + "/rows?limit=100" + ((status != null) ? "&status=" + status : "")
					+ ((cursor != null) ? "&cursor=" + cursor : "");
			JsonNode page = call(HttpMethod.GET, url, who, null, 200);
			page.get("items").forEach(all::add);
			cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
		}
		while (cursor != null);
		return all;
	}

	protected JsonNode commit(Persona who, UUID schoolId, UUID jobId, boolean skipInvalid, int status) throws Exception {
		return call(HttpMethod.POST, imports(schoolId) + "/" + jobId + "/commit", who, Map.of("skipInvalidRows", skipInvalid),
				status);
	}

	/** A workbook as bytes. */
	protected static byte[] workbook(java.util.function.Consumer<XSSFWorkbook> fill) throws IOException {
		try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			fill.accept(wb);
			wb.write(out);
			return out.toByteArray();
		}
	}

	protected static String problemCodes(JsonNode row, String list) {
		List<String> codes = new ArrayList<>();
		row.get(list).forEach((p) -> codes.add(p.get("code").asString()));
		return String.join(",", codes);
	}

}
