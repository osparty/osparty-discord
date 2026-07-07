package net.osparty.discord.config;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the single JDA gateway connection for this service. This is the one and only gateway session on
 * the bot token — the whole point of extracting the bot out of the (now multi-instance) API.
 *
 * <p>{@code createLight}: no message cache. We add {@code GUILD_VOICE_STATES} (non-privileged) + the
 * {@code VOICE_STATE} cache and a {@code VOICE} member-cache policy so members currently in a voice
 * channel are cached — otherwise {@code getMemberById()} is null and we can't check which channel a
 * kicked member is in before disconnecting them.
 *
 * <p>{@code GUILD_MEMBERS} is a PRIVILEGED intent (must also be ticked under Bot → Privileged Gateway
 * Intents in the Developer Portal, or the gateway rejects the session). It delivers the member
 * role-change events {@code BadgeSyncService} turns into badge pushes, and permits the
 * {@code loadMembers()} startup sweep. Events carry the member payload, so no wider member cache is
 * needed.
 */
@Configuration
public class JdaConfig {
	private static final Logger log = LoggerFactory.getLogger(JdaConfig.class);

	@Bean(destroyMethod = "shutdown")
	public JDA jda(@Value("${app.discord.token:}") String token,
		@Value("${app.discord.guild-id:0}") long guildId,
		@Value("${app.discord.category-id:0}") long categoryId) throws InterruptedException {
		if (token == null || token.isBlank()) {
			throw new IllegalStateException(
				"app.discord.token is required (set DISCORD_TOKEN). This service is the Discord bot; "
					+ "it cannot start without a token.");
		}
		JDA jda = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MEMBERS)
			.enableCache(CacheFlag.VOICE_STATE)
			.setMemberCachePolicy(MemberCachePolicy.VOICE)
			.build()
			.awaitReady();
		log.info("Discord bot connected as {} (guild={}, category={})",
			jda.getSelfUser().getName(), guildId, categoryId);
		return jda;
	}
}
