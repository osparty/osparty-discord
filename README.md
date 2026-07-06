# osparty-discord

The **Discord voice-channel service** for [OSParty](https://github.com/osparty). It owns the single JDA
gateway connection and exposes a small internal REST API that the (horizontally-scaled) `osparty-api`
instances call to provision and tear down per-party voice channels.

## Why it exists

The API used to embed the Discord bot in-process (`DiscordBotService`). Once the API runs as **multiple
instances** behind a load balancer that stops working:

- each instance would open its own gateway connection on the same bot token, and
- voice provisioning would only succeed when a host's WebSocket happened to land on a Discord-enabled
  instance.

Extracting the bot into this single service fixes both: **one** gateway connection, reachable by **every**
API instance over HTTP. Account linking (accountHash ⇄ Discord user) and the OAuth2 callback stay in the
API — they're Redis-backed and need no bot — so this service holds no Redis and no OAuth secrets.

## Internal API

All routes require the shared secret header **`X-Internal-Token`** (see `InternalAuthFilter`), are meant to
be reachable only on the private network, and map 1:1 to the API's `VoiceChannelService` interface:

| Method | Path                               | Body                                                   | Returns |
|--------|------------------------------------|--------------------------------------------------------|---------|
| POST   | `/voice/channels`                  | `{party:{id,host,inviteCode,activity}, allowedDiscordIds:[…]}` | `200 {channelId,inviteUrl}` / `502` |
| POST   | `/voice/channels/{id}/grant`       | `{discordId}`                                          | `200` / `502` |
| POST   | `/voice/channels/{id}/revoke`      | `{discordId}`                                          | `202` |
| POST   | `/voice/channels/{id}/disconnect`  | `{discordId}`                                          | `202` |
| DELETE | `/voice/channels/{id}`             | —                                                      | `204` |

`create` and `grant` run synchronously (JDA `complete()`) — the API's host thread is blocked on the reply,
and a `200` means the Discord change is actually live. `revoke`/`disconnect`/`delete` are fire-and-forget.

`/actuator/health` and `/actuator/prometheus` are exempt from the token check so Prometheus can scrape.

## Config (env)

See `.env.example`. Required: `DISCORD_TOKEN`, `DISCORD_GUILD_ID`, and (recommended) `DISCORD_INTERNAL_TOKEN`.
`DISCORD_CATEGORY_ID`, `DISCORD_INVITE_MAX_AGE_SECONDS`, `DISCORD_INVITE_MAX_USES` are optional. The service
**cannot start without a bot token** — it is the bot.

## Run

```sh
./gradlew bootJar
docker compose up --build -d          # reads .env
```

Or locally without Docker:

```sh
export DISCORD_TOKEN=... DISCORD_GUILD_ID=... DISCORD_CATEGORY_ID=...
./gradlew bootRun                     # serves on :8090
```

The paired `osparty-api` points at it via `DISCORD_SERVICE_URL` (e.g. `http://10.0.0.1:8090` on the private
net, or `http://host.docker.internal:8090` locally) and `DISCORD_INTERNAL_TOKEN` (same secret).

## Deploy

CI is **tag-driven** (`.github/workflows/deploy.yml`): pushing a semver tag (`v1.2.3`) builds the image,
pushes it to **`ghcr.io/osparty/osparty-discord`**, then SSHes to MAIN and runs `docker compose pull &&
up -d`. Runs on the **MAIN** server only (single instance).

```sh
git tag v1.2.3 && git push origin v1.2.3   # builds + deploys 1.2.3
```

Rollback = redeploy an older tag: `IMAGE_TAG=1.2.2 docker compose up -d` on the server.

Required repo **secrets**: `API_SERVER_ADDRESS`, `SSH_USER`, `SSH_KEY` (PEM, no passphrase),
`GHCR_USERNAME` + `GHCR_TOKEN` (a PAT with `read:packages` for the server's pull login). Optional:
`SSH_PORT`. One-time on GitHub: set the GHCR package visibility to **internal** under the org's package
settings. The server must already hold the bot's `.env` next to `docker-compose.yml`. Bind the published
port to MAIN's private interface and firewall it to the private subnet.
