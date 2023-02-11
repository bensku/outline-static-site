package fi.benjami.site.outline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.Javalin;

public class AppMain {
	
	private static final StringCrypto CRYPTO;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	static {
		var secretFile = System.getenv("APP_SECRET_FILE");
		try {
			var secret = secretFile != null ? Files.readString(Path.of(secretFile)) : System.getenv("APP_SECRET");
			CRYPTO = new StringCrypto(secret);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
	
	public static final String USER_AGENT = "outline-static-site/0.1 ";

	public static void main(String... args) {
		var app = Javalin.create(config -> {
			config.staticFiles.add("static");
		});
		app.post("/encrypt", ctx -> {
			// Canonicalize the input string to strip out unexpected data
			var config = MAPPER.readValue(ctx.body(), SiteConfig.class);
			ctx.result(CRYPTO.encrypt(MAPPER.writeValueAsString(config)));
		});
		app.get("/publish/{data}", ctx -> {
			var config = MAPPER.readValue(CRYPTO.decrypt(ctx.pathParam("data")), SiteConfig.class);
			var loader = new OutlineLoader(config.outlineUrl(), config.outlineApiToken(), config.collectionId());
			var pages = loader.loadAll();
			var uploader = new CloudflareUploader(config);
			uploader.deploy(pages);
			
			ctx.redirect("https://" + config.cfPagesSite() + ".pages.dev");
		});
		
		app.start(Integer.parseInt(System.getenv("PORT")));
	}
}
