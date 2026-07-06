package net.osparty.discord.web.dto;

import java.util.List;

/**
 * Body of {@code POST /voice/channels}. {@code allowedDiscordIds} are the linked members granted per-user
 * view/connect on top of the {@code @everyone} deny, so the channel is locked to exactly those people. The
 * API resolves accountHash -> Discord id (it owns that mapping in Redis); the bot just consumes the ids.
 */
public record CreateChannelRequest(PartyRef party, List<String> allowedDiscordIds) {
}
