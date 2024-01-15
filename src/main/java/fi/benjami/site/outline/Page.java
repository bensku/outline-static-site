package fi.benjami.site.outline;

import java.util.UUID;

public record Page(
		Format format,
		UUID id,
		String path,
		String title,
		byte[] content
) {
	
	enum Format {
		HTML(".html", "text/html"),
		CSS(".css", "text/css"),
		JAVASCRIPT(".js", "text/javascript"),
		JPEG(".jpg", "image/jpeg"),
		PNG(".png", "image/png"),
		WEBP(".webp", "image/webp"),
		TEXT(".txt", "text/plain"),
		WOFF2(".woff2", "font/woff2"),
		MARKDOWN("", "text/html"); // Markdown converted to HTML is the default
		
		public static Format fromTitle(String title) {
			for (var format : values()) {
				if (title.endsWith(format.extension)) {
					return format;
				}
			}
			throw new AssertionError();
		}
		
		public static Format fromMimeType(String type) {
			for (var format : values()) {
				if (type.equals(format.mimeType)) {
					return format;
				}
			}
			throw new AssertionError(type);
		}
		
		private String extension;
		private String mimeType;
		
		Format(String extension, String mimeType) {
			this.extension = extension;
			this.mimeType = mimeType;
		}
		
		public String extension() {
			return extension;
		}
		
		public String mimeType() {
			return mimeType;
		}
	}
}
