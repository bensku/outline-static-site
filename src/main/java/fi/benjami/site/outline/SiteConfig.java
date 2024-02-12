package fi.benjami.site.outline;

public record SiteConfig(
		/**
		 * URL of the Outline collection, i.e. http://HOST/collection/NAME.
		 */
		String collectionUrl,
		
		/**
		 * Outline API token.
		 */
		String outlineApiToken,
		
		/**
		 * URL of Cloudflare Pages console settings page for the site.
		 */
		String pageSettingsUrl,
		
		/**
		 * Cloudflare API token.
		 */
		String cfApiToken
) {}
