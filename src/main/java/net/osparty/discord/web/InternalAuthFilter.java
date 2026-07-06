package net.osparty.discord.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defence-in-depth for the internal API. The service listens only on the private network and is
 * firewalled, but we still require a shared secret header ({@code X-Internal-Token}) on every
 * {@code /voice/**} call so a stray request on the private net can't drive the bot.
 *
 * <p>When {@code app.internal-token} is blank the check is disabled (local dev). Actuator endpoints
 * ({@code /actuator/**}) are always exempt so Prometheus can scrape without the secret.
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {
	private static final String HEADER = "X-Internal-Token";

	private final String expectedToken;

	public InternalAuthFilter(@Value("${app.internal-token:}") String expectedToken) {
		this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// No secret configured -> open (dev); actuator is always exempt for scraping/health probes.
		return expectedToken.isBlank() || request.getRequestURI().startsWith("/actuator");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String provided = request.getHeader(HEADER);
		if (expectedToken.equals(provided)) {
			chain.doFilter(request, response);
		}
		else {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
		}
	}
}
