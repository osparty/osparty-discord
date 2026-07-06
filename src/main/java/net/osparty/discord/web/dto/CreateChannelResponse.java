package net.osparty.discord.web.dto;

/** Reply to {@code POST /voice/channels}: the provisioned channel's Discord id and the shareable invite URL. */
public record CreateChannelResponse(String channelId, String inviteUrl) {
}
