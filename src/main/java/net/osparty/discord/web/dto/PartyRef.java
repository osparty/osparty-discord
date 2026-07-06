package net.osparty.discord.web.dto;

/**
 * The subset of an OSParty ad the bot needs to name a channel. Sent by the API in the create request;
 * deliberately independent of the API's {@code Party} model (the two repos share only this JSON shape).
 */
public record PartyRef(String id, String host, String inviteCode, String activity) {
}
