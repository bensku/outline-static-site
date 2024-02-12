package fi.benjami.site.outline;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.codec.Hex;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.http.InternalServerErrorResponse;

public class CloudflareUploader {
	
	private static final Logger LOG = LoggerFactory.getLogger(CloudflareUploader.class);
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String API_URL = "https://api.cloudflare.com/client/v4";
	
	private final HttpClient client;
	
	private final String account;
	private final String apiToken;
	private final String site;
	
	public CloudflareUploader(SiteConfig config) {
		this.client = HttpClient.newHttpClient();
		// Path should have format like /ACCOUNT/pages/view/SITE
		var parts = URI.create(config.pageSettingsUrl()).getPath().split("/");
		this.account = parts[1];
		this.apiToken = config.cfApiToken();
		this.site = parts[4];
	}
	
	public String siteUrl() {
		return "https://" + site + ".pages.dev";
	}
	
	public void deploy(List<Page> pages) {
		try {
			var uploadToken = getUploadToken();
			var hashes = uploadFiles(uploadToken, pages);
			publish(hashes);
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e); // FIXME bad practise
		}
	}
	
	private String getUploadToken() throws IOException, InterruptedException {
		var req = HttpRequest.newBuilder(URI.create(API_URL + "/accounts/" + account + "/pages/projects/" + site + "/upload-token"))
				.GET()
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiToken)
				.build();
		var response = client.send(req, BodyHandlers.ofString());
		var tree = MAPPER.readTree(response.body());
		return tree.get("result").get("jwt").textValue();
	}
	
	private Map<Page, String> uploadFiles(String uploadToken, List<Page> pages) throws IOException, InterruptedException {
		var hashes = new HashMap<Page, String>();
		
		int uploadSize = 0;
		var nextBatch = new ArrayList<Page>();
		for (var page : pages) {
			if (uploadSize > 10_000_000) {
				hashes.putAll(uploadFiles0(uploadToken, nextBatch));
				nextBatch.clear();
				uploadSize = 0;
			}
			uploadSize += page.content().length;
			nextBatch.add(page);
		}
		hashes.putAll(uploadFiles0(uploadToken, nextBatch));
		return hashes;
	}
	
	private Map<Page, String> uploadFiles0(String uploadToken, List<Page> pages) throws IOException, InterruptedException {
		var list = MAPPER.createArrayNode();
		var hashes = new HashMap<Page, String>();
		for (var page : pages) {
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new AssertionError(); // Should always be supported
			}
			digest.update(page.content());
			var hash = new String(Hex.encode(digest.digest())).substring(0, 32);
			hashes.put(page, hash);
			
			var payload = MAPPER.createObjectNode();
			payload.put("key", hash);
			payload.put("value", Base64.getEncoder().encodeToString(page.content()));
			payload.put("base64", true);
			var metadata = MAPPER.createObjectNode();
			metadata.put("contentType", page.format().mimeType());
			payload.set("metadata", metadata);
			
			list.add(payload);
		}
		
		var req = HttpRequest.newBuilder(URI.create(API_URL + "/pages/assets/upload"))
				.POST(BodyPublishers.ofString(MAPPER.writeValueAsString(list)))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + uploadToken)
				.build();
		var response = client.send(req, BodyHandlers.ofString());
		LOG.info("Uploaded to {} pages CF: {}", pages.size(), response);
		if (response.statusCode() != 200) {
			LOG.error(response.body());
			throw new InternalServerErrorResponse();
		}
		return hashes;
	}
	
	private void publish(Map<Page, String> pages) throws IOException, InterruptedException {
		var manifest = MAPPER.createObjectNode();
		for (var entry : pages.entrySet()) {
			var path = entry.getKey().path();
			if (entry.getKey().format() == Page.Format.MARKDOWN) {
				path += ".html";
			}
			manifest.put("/" + path, entry.getValue());
		}
		
		var body = new StringBuilder("----formdataboundary\r\n");
		body.append("Content-Disposition: form-data; name=\"manifest\"\r\n\r\n");
		body.append(MAPPER.writeValueAsString(manifest)).append("\r\n");
		body.append("----formdataboundary--\r\n\r\n");
		
		var req = HttpRequest.newBuilder(URI.create(API_URL + "/accounts/" + account + "/pages/projects/" + site + "/deployments"))
				.POST(BodyPublishers.ofString(body.toString()))
				.header("Content-Type", "multipart/form-data; boundary=--formdataboundary")
				.header("Authorization", "Bearer " + apiToken)
				.build();
		LOG.info("Publishing to CF pages: {}", req);
		var response = client.send(req, BodyHandlers.ofString());
		LOG.info("Published to CF pages: {}", response);
		if (response.statusCode() != 200) {
			LOG.error(response.body());
			throw new InternalServerErrorResponse();
		}
	}
}
