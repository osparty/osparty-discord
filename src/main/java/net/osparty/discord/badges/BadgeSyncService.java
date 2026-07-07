package net.osparty.discord.badges;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Keeps the osparty API's Discord-role badge state in sync with the guild, so clients can render
 * badges (developer / content creator / beta tester / backer) next to party hosts.
 *
 * <p>Two mechanisms, per the feature's design: gateway events ({@code GUILD_MEMBER_UPDATE}, arriving
 * in JDA as role add/remove + member join/remove events) push individual changes as they happen, and a
 * full {@code loadMembers()} sweep at startup replaces the API's complete badge state — catching role
 * changes missed while the bot was offline. Requires the privileged {@code GUILD_MEMBERS} intent (see
 * {@link net.osparty.discord.config.JdaConfig}).
 *
 * <p>Role→badge mapping comes from {@code app.discord.roles.*}; unset (0) roles are simply not mapped.
 * The badge strings are the wire contract with the API and, in this order, the client's display
 * priority: {@code developer}, {@code content_creator}, {@code beta_tester}, {@code backer}.
 */
@Service
public class BadgeSyncService extends ListenerAdapter {
	private static final Logger log = LoggerFactory.getLogger(BadgeSyncService.class);

	private final BadgePushClient push;
	private final JDA jda;
	private final long guildId;
	/** Insertion order = canonical badge order, so pushed lists are already display-sorted. */
	private final Map<Long, String> badgeByRoleId = new LinkedHashMap<>();

	public BadgeSyncService(JDA jda, BadgePushClient push,
		@Value("${app.discord.guild-id:0}") long guildId,
		@Value("${app.discord.roles.developer:0}") long developerRoleId,
		@Value("${app.discord.roles.content-creator:0}") long contentCreatorRoleId,
		@Value("${app.discord.roles.beta-tester:0}") long betaTesterRoleId,
		@Value("${app.discord.roles.backer:0}") long backerRoleId) {
		this.jda = jda;
		this.push = push;
		this.guildId = guildId;
		mapRole(developerRoleId, "developer");
		mapRole(contentCreatorRoleId, "content_creator");
		mapRole(betaTesterRoleId, "beta_tester");
		mapRole(backerRoleId, "backer");
		if (isEnabled()) {
			jda.addEventListener(this);
			log.info("Badge sync enabled ({} mapped roles)", badgeByRoleId.size());
		}
		else {
			log.info("Badge sync disabled (needs app.api.base-url, app.discord.guild-id and "
				+ "at least one app.discord.roles.* id)");
		}
	}

	private void mapRole(long roleId, String badge) {
		if (roleId != 0) {
			badgeByRoleId.put(roleId, badge);
		}
	}

	private boolean isEnabled() {
		return push.isEnabled() && guildId != 0 && !badgeByRoleId.isEmpty();
	}

	/**
	 * Startup reconciliation: fetch every guild member (chunked over the gateway) and replace the API's
	 * badge state with exactly the current badge holders. Async — a large guild streams in while the
	 * service is already serving voice calls.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void sweep() {
		if (!isEnabled()) {
			return;
		}
		Guild guild = jda.getGuildById(guildId);
		if (guild == null) {
			log.warn("Badge sweep skipped: bot is not in guild {}", guildId);
			return;
		}
		guild.loadMembers()
			.onSuccess(members -> {
				List<BadgePushClient.BadgePush> holders = new ArrayList<>();
				for (Member member : members) {
					List<String> badges = badgesOf(member);
					if (!badges.isEmpty()) {
						holders.add(new BadgePushClient.BadgePush(member.getId(), badges));
					}
				}
				push.replaceAll(holders);
			})
			.onError(e -> log.warn("Badge sweep failed to load guild members: {}", e.toString()));
	}

	/**
	 * Role changes arrive as GUILD_MEMBER_UPDATE. We deliberately listen to the GENERIC member-update
	 * event rather than {@code GuildMemberRoleAdd/RemoveEvent}: JDA only fires the role-diff events for
	 * members already in its cache (see {@code GuildMemberUpdateHandler} — the uncached branch builds
	 * the member from the payload and fires only this event), and with our lean VOICE member-cache
	 * policy that's almost nobody. This event always fires and its member carries the fresh role list.
	 * The push is idempotent, so re-pushing on unrelated member updates (nickname etc.) is harmless.
	 */
	@Override
	public void onGuildMemberUpdate(GuildMemberUpdateEvent event) {
		if (event.getGuild().getIdLong() == guildId) {
			push.set(event.getMember().getId(), badgesOf(event.getMember()));
		}
	}

	/** Covers integrations that assign mapped roles at join time; a plain join pushes nothing. */
	@Override
	public void onGuildMemberJoin(GuildMemberJoinEvent event) {
		if (event.getGuild().getIdLong() == guildId && !badgesOf(event.getMember()).isEmpty()) {
			push.set(event.getMember().getId(), badgesOf(event.getMember()));
		}
	}

	/** Leaving the guild forfeits all badges. */
	@Override
	public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
		if (event.getGuild().getIdLong() == guildId) {
			push.set(event.getUser().getId(), List.of());
		}
	}

	private List<String> badgesOf(Member member) {
		if (member.getUser().isBot()) {
			return List.of();
		}
		List<String> badges = new ArrayList<>(badgeByRoleId.size());
		for (Map.Entry<Long, String> entry : badgeByRoleId.entrySet()) {
			for (Role role : member.getRoles()) {
				if (role.getIdLong() == entry.getKey()) {
					badges.add(entry.getValue());
					break;
				}
			}
		}
		return badges;
	}
}
