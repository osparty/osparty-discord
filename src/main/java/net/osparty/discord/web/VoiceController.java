package net.osparty.discord.web;

import java.util.Optional;
import net.osparty.discord.voice.VoiceChannelManager;
import net.osparty.discord.voice.VoiceChannelManager.VoiceChannelInfo;
import net.osparty.discord.web.dto.CreateChannelRequest;
import net.osparty.discord.web.dto.CreateChannelResponse;
import net.osparty.discord.web.dto.DiscordIdRequest;
import net.osparty.discord.web.dto.PartyRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST API mirroring the API's {@code VoiceChannelService} interface. Only reachable on the
 * private network + gated by the shared secret ({@link InternalAuthFilter}). All calls are best-effort:
 * create/grant block on the Discord round-trip and report success/failure; revoke/disconnect/delete return
 * immediately (202/204) and the underlying JDA work is fire-and-forget.
 */
@RestController
@RequestMapping("/voice/channels")
public class VoiceController {
	private final VoiceChannelManager voice;

	public VoiceController(VoiceChannelManager voice) {
		this.voice = voice;
	}

	/** Provision a channel + mint an invite. 200 with the channel/invite, or 502 when Discord create failed. */
	@PostMapping
	public ResponseEntity<CreateChannelResponse> create(@RequestBody CreateChannelRequest req) {
		if (req == null || req.party() == null || req.party().id() == null) {
			return ResponseEntity.badRequest().build();
		}
		Optional<VoiceChannelInfo> channel = voice.createForParty(req.party(), req.allowedDiscordIds());
		return channel
			.map(c -> ResponseEntity.ok(new CreateChannelResponse(c.channelId(), c.inviteUrl())))
			.orElseGet(() -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).build());
	}

	/** Grant a member access. Synchronous: 200 only once the override is live; 502 on failure. */
	@PostMapping("/{channelId}/grant")
	public ResponseEntity<Void> grant(@PathVariable String channelId, @RequestBody DiscordIdRequest req) {
		if (req == null || req.discordId() == null) {
			return ResponseEntity.badRequest().build();
		}
		return voice.grantAccess(channelId, req.discordId())
			? ResponseEntity.ok().build()
			: ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
	}

	/** Revoke a member's access. Fire-and-forget -> 202. */
	@PostMapping("/{channelId}/revoke")
	public ResponseEntity<Void> revoke(@PathVariable String channelId, @RequestBody DiscordIdRequest req) {
		if (req != null && req.discordId() != null) {
			voice.revokeAccess(channelId, req.discordId());
		}
		return ResponseEntity.accepted().build();
	}

	/** Disconnect a member from the channel if they're in it. Fire-and-forget -> 202. */
	@PostMapping("/{channelId}/disconnect")
	public ResponseEntity<Void> disconnect(@PathVariable String channelId, @RequestBody DiscordIdRequest req) {
		if (req != null && req.discordId() != null) {
			voice.disconnectFromChannel(channelId, req.discordId());
		}
		return ResponseEntity.accepted().build();
	}

	/** Rename a channel to match the party's current host/details. Fire-and-forget -> 202. */
	@PostMapping("/{channelId}/rename")
	public ResponseEntity<Void> rename(@PathVariable String channelId, @RequestBody PartyRef party) {
		if (party == null || party.id() == null) {
			return ResponseEntity.badRequest().build();
		}
		voice.rename(channelId, party);
		return ResponseEntity.accepted().build();
	}

	/** Tear down a channel. Idempotent, fire-and-forget -> 204. */
	@DeleteMapping("/{channelId}")
	public ResponseEntity<Void> delete(@PathVariable String channelId) {
		voice.delete(channelId);
		return ResponseEntity.noContent().build();
	}
}
