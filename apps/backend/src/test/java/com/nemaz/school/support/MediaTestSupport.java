package com.nemaz.school.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The upload flow of the media API as a client performs it: request, PUT to storage, complete, wait. */
public abstract class MediaTestSupport extends AuthTestSupport {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	protected ResultActions requestUpload(Persona who, String purpose, String filename, String mimeType, long size,
			Map<String, Object> context) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("purpose", purpose);
		body.put("filename", filename);
		body.put("mimeType", mimeType);
		body.put("sizeBytes", size);
		if (context != null) {
			body.put("context", context);
		}
		return this.mvc.perform(post("/api/v1/media/uploads").with(who.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(body)));
	}

	protected JsonNode body(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	/** Requests an upload that must succeed and returns its response. */
	protected JsonNode newUpload(Persona who, String purpose, String filename, String mimeType, byte[] content,
			Map<String, Object> context) throws Exception {
		return body(requestUpload(who, purpose, filename, mimeType, content.length, context).andExpect(status().isCreated()));
	}

	/** PUTs the bytes to the presigned URL like a browser or phone does; returns the HTTP status. */
	protected int putToStorage(JsonNode upload, byte[] content, String contentType) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.get("url").asString()))
			.timeout(Duration.ofSeconds(30))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(content));
		Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		upload.get("headers").properties().forEach((e) -> headers.put(e.getKey(), e.getValue().asString()));
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		headers.forEach(request::header);
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	protected ResultActions complete(Persona who, String mediaId) throws Exception {
		return this.mvc.perform(post("/api/v1/media/" + mediaId + "/complete").with(who.bearer()));
	}

	protected ResultActions getMedia(Persona who, String mediaId) throws Exception {
		return this.mvc.perform(get("/api/v1/media/" + mediaId).with(who.bearer()));
	}

	/** Polls until the media reaches {@code status}; returns its view. */
	protected JsonNode awaitStatus(Persona who, String mediaId, String status) {
		JsonNode[] last = new JsonNode[1];
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
			last[0] = body(getMedia(who, mediaId).andExpect(status().isOk()));
			assertThat(last[0].get("status").asString()).isEqualTo(status);
		});
		return last[0];
	}

	/** The whole happy path up to READY. Returns the media view. */
	protected JsonNode uploadReady(Persona who, String purpose, String filename, String mimeType, byte[] content,
			Map<String, Object> context) throws Exception {
		JsonNode ticket = newUpload(who, purpose, filename, mimeType, content, context);
		assertThat(putToStorage(ticket.get("upload"), content, mimeType)).isEqualTo(200);
		complete(who, ticket.get("mediaId").asString()).andExpect(status().isOk());
		return awaitStatus(who, ticket.get("mediaId").asString(), "READY");
	}

	protected static Map<String, Object> school(UUID id) {
		return Map.of("type", "SCHOOL", "id", id.toString());
	}

	protected static Map<String, Object> organization(UUID id) {
		return Map.of("type", "ORGANIZATION", "id", id.toString());
	}

	/** Downloads a signed URL (real HTTP to the storage container). */
	protected static byte[] download(String url) throws IOException, InterruptedException {
		HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).as("GET " + url).isEqualTo(200);
		return response.body();
	}

	protected static HttpResponse<byte[]> fetch(String url) throws IOException, InterruptedException {
		return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
	}

}
