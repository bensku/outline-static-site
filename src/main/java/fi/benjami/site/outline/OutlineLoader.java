package fi.benjami.site.outline;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.getoutline.api.AttachmentsApi;
import com.getoutline.api.DocumentsApi;
import com.getoutline.invoker.ApiClient;
import com.getoutline.invoker.ApiException;
import com.getoutline.model.AttachmentsRedirectPostRequest;
import com.getoutline.model.Document;
import com.getoutline.model.DocumentsListPostRequest;

public class OutlineLoader {
	
	private static final Logger LOG = LoggerFactory.getLogger(OutlineLoader.class);
	
	private static List<Extension> EXTENSIONS = List.of(TablesExtension.create());
	private static final Parser PARSER = Parser.builder()
			.extensions(EXTENSIONS)
			.build();
	private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
			.extensions(EXTENSIONS)
			.build();

	private final HttpClient httpClient;
	private final ApiClient client;
	private final UUID collectionId;
	
	public OutlineLoader(String apiUrl, String apiToken, String collection) {
		this.httpClient = HttpClient.newHttpClient();
		this.client = new ApiClient();
		client.updateBaseUri(apiUrl + "/api");
		client.setRequestInterceptor(builder -> {
			builder.header("User-Agent", AppMain.USER_AGENT);
			builder.header("Authorization", "Bearer " + apiToken);
		});
		this.collectionId = UUID.fromString(collection);
	}
	
	public List<Page> loadAll() {
		var api = new DocumentsApi(client);
		var documents = api.documentsListPost(new DocumentsListPostRequest()
				.collectionId(collectionId)).getData();
		LOG.info("Loaded documents from Outline");
		
		var docs = new HashMap<UUID, Document>();
		String templateText = """
				<!doctype html>
				<html>
				<head>
				<title>{title}</title>
				</head>
				<body>
				{content}
				</body>
				</html>
				""";
		for (var doc : documents) {
			if (doc.getTitle().equals("template.html")) {
				templateText = extractCode(doc.getText(), "markup");
			} else {				
				docs.put(doc.getId(), doc);
			}
		}
		String template = templateText;
				
		return docs.values().stream()
				.flatMap(doc -> {
					// Figure out parent pages
					var parents = new ArrayList<UUID>();
					var parent = doc.getParentDocumentId();
					while (parent != null) {
						parents.add(parent);
						parent = docs.get(parent).getParentDocumentId();
					}
					
					// Build path from names of all pages
					var path = new StringBuilder();
					for (int i = parents.size() - 1; i >= 0; i--) {
						// TODO should this strip file extension?
						var name = formatName(docs.get(parents.get(i)).getTitle());
						path.append('/').append(name);
					}
					
					var format = Page.Format.fromTitle(doc.getTitle());
					var pages = processContent(format, doc.getId(), doc.getTitle(), path.toString(), doc.getText(), template);
					return pages.stream();
				})
				.toList();
	}
	
	private String formatName(String title) {
		return title.toLowerCase().replace(" ", "-");
	}
	
	private List<Page> processContent(Page.Format format, UUID id, String title,
			String path, String content, String template) {
		var pages = new ArrayList<Page>();
		
		var mainData = switch (format) {
		case HTML -> template.replace("{content}", extractCode(content, "markup"))
				.getBytes(StandardCharsets.UTF_8);
		case CSS -> extractCode(content, "css").getBytes(StandardCharsets.UTF_8);
		case JAVASCRIPT -> extractCode(content, "javascript").getBytes(StandardCharsets.UTF_8);
		case MARKDOWN -> {
			var doc = PARSER.parse(content);
			pages.addAll(patchImages(doc));
			
			var html = template.replace("{content}", RENDERER.render(doc));
			html = html.replace("{title}", extractTitle(content));
			html = html.replace("<p>\\</p>", ""); // FIXME what is this???
			yield html.getBytes(StandardCharsets.UTF_8);
		}
		default -> extractMedia(content);
		};
		pages.add(new Page(format, id, title, path, mainData));
		return pages;
	}
	
	private String extractCode(String markdown, String type) {
		var doc = PARSER.parse(markdown);
		var code = new AtomicReference<String>();
		doc.accept(new AbstractVisitor() {
			public void visit(FencedCodeBlock block) {
				if (type.equals(block.getInfo())) {					
					code.setPlain(block.getLiteral());
				}
			}
		});
		return code.getPlain();
	}
	
	private String extractTitle(String markdown) {
		var doc = PARSER.parse(markdown);
		var title = new AtomicReference<String>();
		doc.accept(new AbstractVisitor() {
			public void visit(Heading block) {
				if (block.getLevel() == 1) {
					var child = block.getFirstChild();
					if (child instanceof Text text) {						
						title.setPlain(text.getLiteral());
					}
				}
			}
		});
		return title.getPlain();
	}
	
	private static final String MEDIA_PREFIX = "/api/attachments.redirect?id=";
	
	private List<Page> patchImages(Node doc) {
		var api = new AttachmentsApi(client);
		var attachments = new ArrayList<Page>();
		doc.accept(new AbstractVisitor() {
			public void visit(Image block) {
				if (block.getDestination().startsWith(MEDIA_PREFIX)) {
					// Create a page for the attachment
					var attachmentId = block.getDestination().substring(MEDIA_PREFIX.length());
					var page = loadAttachment(api, UUID.fromString(attachmentId));
					attachments.add(page);
					
					// Replace image destination with it
					block.setDestination("/" + page.path());
				}
			}
		});
		return attachments;
	}
	
	private byte[] extractMedia(String markdown) {
		var doc = PARSER.parse(markdown);
		var api = new AttachmentsApi(client);
		var attachment = new AtomicReference<byte[]>();
		doc.accept(new AbstractVisitor() {
			public void visit(Image block) {
				if (block.getDestination().startsWith(MEDIA_PREFIX)) {
					// Create a page for the attachment
					var attachmentId = block.getDestination().substring(MEDIA_PREFIX.length());
					var page = loadAttachment(api, UUID.fromString(attachmentId));
					attachment.setPlain(page.content());
				}
			}
		});
		return attachment.getPlain();
	}
	
	private Page loadAttachment(AttachmentsApi api, UUID id) {
		String attachmentUrl;
		try {			
			api.attachmentsRedirectPostWithHttpInfo(new AttachmentsRedirectPostRequest().id(id));
			throw new AssertionError();
		} catch (ApiException e) {
			// TODO OpenAPI generator bug, why is this an error?
			attachmentUrl = e.getResponseHeaders().firstValue("Location").orElseThrow();
		}
		var req = HttpRequest.newBuilder(URI.create(attachmentUrl))
				.GET()
				.build();
		try {
			var res = httpClient.send(req, BodyHandlers.ofByteArray());
			var type = res.headers().firstValue("Content-Type").orElseThrow();
			var format = Page.Format.fromMimeType(type);
			return new Page(format, id, id + format.extension(), "", res.body());
		} catch (IOException | InterruptedException e) {
			throw new ApiException(e);
		}
	}
}
