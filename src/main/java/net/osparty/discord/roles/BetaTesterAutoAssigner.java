package net.osparty.discord.roles;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdatePendingEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Grants every new human member the beta-tester role once they've been admitted to the guild — i.e. after
 * they accept the rules. On a Community server with Membership Screening enabled a member starts out
 * <em>pending</em> and only becomes a full member when they tick "I agree to the rules"; JDA surfaces that
 * as {@link GuildMemberUpdatePendingEvent} (pending true → false), which is our trigger. On a server without
 * screening the join itself admits them, so {@link GuildMemberJoinEvent} for a non-pending member assigns it
 * straight away.
 *
 * <p>Assigning the beta-tester role also earns the {@code beta_tester} badge for free: the role add fires a
 * {@code GUILD_MEMBER_UPDATE} that {@link net.osparty.discord.badges.BadgeSyncService} already turns into a
 * badge push.
 *
 * <p>Requires the privileged {@code GUILD_MEMBERS} intent (already enabled, see
 * {@link net.osparty.discord.config.JdaConfig}) and that the bot has {@code MANAGE_ROLES} with its own top
 * role ranked above the beta-tester role. Inert unless a guild id, the beta-tester role id, and the
 * {@code app.discord.roles.beta-tester-auto-assign} flag are all set.
 */
@Service
public class BetaTesterAutoAssigner extends ListenerAdapter {
	private static final Logger log = LoggerFactory.getLogger(BetaTesterAutoAssigner.class);

	private final long guildId;
	private final long betaTesterRoleId;

	public BetaTesterAutoAssigner(JDA jda,
		@Value("${app.discord.guild-id:0}") long guildId,
		@Value("${app.discord.roles.beta-tester:0}") long betaTesterRoleId,
		@Value("${app.discord.roles.beta-tester-auto-assign:true}") boolean enabled) {
		this.guildId = guildId;
		this.betaTesterRoleId = betaTesterRoleId;
		if (enabled && guildId != 0 && betaTesterRoleId != 0) {
			jda.addEventListener(this);
			log.info("Beta-tester auto-assign enabled (role {})", betaTesterRoleId);
		}
		else {
			log.info("Beta-tester auto-assign disabled (needs app.discord.guild-id, "
				+ "app.discord.roles.beta-tester and app.discord.roles.beta-tester-auto-assign=true)");
		}
	}

	/** Join on a server without a rules gate: the member is admitted immediately, so grant it now. */
	@Override
	public void onGuildMemberJoin(GuildMemberJoinEvent event) {
		if (event.getGuild().getIdLong() == guildId && !event.getMember().isPending()) {
			assign(event.getMember());
		}
	}

	/** Membership screening completed: pending flips to false when the member accepts the rules. */
	@Override
	public void onGuildMemberUpdatePending(GuildMemberUpdatePendingEvent event) {
		if (event.getGuild().getIdLong() == guildId && Boolean.FALSE.equals(event.getNewValue())) {
			assign(event.getMember());
		}
	}

	private void assign(Member member) {
		if (member.getUser().isBot()) {
			return;
		}
		Guild guild = member.getGuild();
		Role role = guild.getRoleById(betaTesterRoleId);
		if (role == null) {
			log.warn("Beta-tester role {} not found in guild {}; cannot auto-assign", betaTesterRoleId, guildId);
			return;
		}
		if (member.getRoles().contains(role)) {
			return;
		}
		guild.addRoleToMember(member, role).reason("OSParty: auto-assign beta tester on join").queue(
			ok -> log.info("Assigned beta-tester role to new member {}", member.getId()),
			err -> log.warn("Failed to assign beta-tester role to {} (does the bot have Manage Roles, "
				+ "and is its top role above the beta-tester role?): {}", member.getId(), err.toString()));
	}
}
