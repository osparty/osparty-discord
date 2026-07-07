package net.osparty.discord.badges;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pushes Discord-role badge state to the osparty API's {@code /internal/badges} endpoint — the reverse
 * of the API→bot voice calls, authenticated with the same shared secret ({@code app.internal-token},
 * sent as {@code X-Internal-Token}). Mirrors the API's {@code HttpVoiceChannelService} idiom: all calls
 * are best-effort — failures are logged and swallowed, and the startup sweep after the next restart
 * reconciles anything a lost push left stale.
 *
 * <p>Inert (methods no-op) until {@code app.api.base-url} is configured.
 */
@Component
public class BadgePushClient {
	private static final Logger log = LoggerFactory.getLogger(BadgePushClient.class);

	private final RestClient http;

	public BadgePushClient(@Value("${app.api.base-url:}") String baseUrl,
		@Value("${app.internal-token:}") String internalToken) {
		if (baseUrl == null || baseUrl.isBlank()) {
			this.http = null;
			log.info("Badge pushes disabled (app.api.base-url not set)");
			return;
		}
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(2));
		factory.setReadTimeout(Duration.ofSeconds(10));
		RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
		if (internalToken != null && !internalToken.isBlank()) {
			builder = builder.defaultHeader("X-Internal-Token", internalToken.trim());
		}
		this.http = builder.build();
		log.info("Badge pushes -> {}", baseUrl);
	}

	public boolean isEnabled() {
		return http != null;
	}

	/** Upsert one user's badges. An empty list clears them (member left / lost all mapped roles). */
	public void set(String discordId, List<String> badges) {
		if (http == null) {
			return;
		}
		try {
			http.post()
				.uri("/internal/badges")
				.body(new BadgePush(discordId, badges))
				.retrieve()
				.toBodilessEntity();
		}
		catch (Exception e) {
			log.warn("Badge push failed for {}: {}", discordId, e.toString());
		}
	}

	/** Replace the API's complete badge state (startup sweep): users absent from the list are cleared. */
	public void replaceAll(List<BadgePush> pushes) {
		if (http == null) {
			return;
		}
		try {
			http.put()
				.uri("/internal/badges")
				.body(pushes)
				.retrieve()
				.toBodilessEntity();
			log.info("Badge sweep pushed ({} badge holders)", pushes.size());
		}
		catch (Exception e) {
			log.warn("Badge sweep push failed ({} entries): {}", pushes.size(), e.toString());
		}
	}

	/** Mirrors the API's InternalBadgeController.BadgePush DTO. */
	public record BadgePush(String discordId, List<String> badges) {
	}
}
