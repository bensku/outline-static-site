package fi.benjami.site.outline;

public record SiteConfig(
		/**
		 * Outline base URL, not including /api.
		 */
		String outlineUrl,
		
		/**
		 * Outline API token.
		 */
		String outlineApiToken,
		
		/**
		 * Id of the workspace to create site from.
		 */
		String collectionId,
		
		/**
		 * Cloudflare account id.
		 */
		String cfAccount,
		
		/**
		 * Cloudflare API token.
		 */
		String cfApiToken,
		
		/**
		 * Site name.
		 */
		String cfPagesSite
) {}
