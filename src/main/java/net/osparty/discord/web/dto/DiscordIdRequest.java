package net.osparty.discord.web.dto;

/** Body for grant/revoke/disconnect: the target Discord user id. */
public record DiscordIdRequest(String discordId) {
}
