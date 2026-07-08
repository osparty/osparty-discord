# osparty-discord

The **Discord service** for [OSParty](https://github.com/osparty). It owns the single JDA gateway
connection and exposes a small internal REST API that the (horizontally-scaled) `osparty-api`
instances call to provision and tear down per-party voice channels. It also watches guild role
changes over the gateway and pushes Discord-role badge state (developer / content creator /
beta tester / backer) to the API, which renders as badges next to party hosts in the plugin.

## Why it exists

The API used to embed the Discord bot in-process (`DiscordBotService`). Once the API runs as **multiple
instances** behind a load balancer that stops working:

- each instance would open its own gateway connection on the same bot token, and
- voice provisioning would only succeed when a host's WebSocket happened to land on a Discord-enabled
  instance.

Extracting the bot into this single service fixes both: **one** gateway connection, reachable by **every**
API instance over HTTP. Account linking (accountHash ⇄ Discord user) and the OAuth2 callback stay in the
API (they're Redis-backed and need no bot), so this service holds no Redis and no OAuth secrets.

## Internal API

All routes require the shared secret header **`X-Internal-Token`** (see `InternalAuthFilter`), are meant to
be reachable only on the private network, and map 1:1 to the API's `VoiceChannelService` interface:

| Method | Path                               | Body                                                   | Returns |
|--------|------------------------------------|--------------------------------------------------------|---------|
| POST   | `/voice/channels`                  | `{party:{id,host,inviteCode,activity}, allowedDiscordIds:[…]}` | `200 {channelId,inviteUrl}` / `502` |
| POST   | `/voice/channels/{id}/grant`       | `{discordId}`                                          | `200` / `502` |
| POST   | `/voice/channels/{id}/revoke`      | `{discordId}`                                          | `202` |
| POST   | `/voice/channels/{id}/disconnect`  | `{discordId}`                                          | `202` |
| DELETE | `/voice/channels/{id}`             | -                                                      | `204` |

`create` and `grant` run synchronously (JDA `complete()`): the API's host thread is blocked on the reply,
and a `200` means the Discord change is actually live. `revoke`/`disconnect`/`delete` are fire-and-forget.

`/actuator/health` and `/actuator/prometheus` are exempt from the token check so Prometheus can scrape.

## Config (env)

See `.env.example`. Required: `DISCORD_TOKEN`, `DISCORD_GUILD_ID`, and (recommended) `DISCORD_INTERNAL_TOKEN`.
`DISCORD_CATEGORY_ID`, `DISCORD_INVITE_MAX_AGE_SECONDS`, `DISCORD_INVITE_MAX_USES` are optional. The service
**cannot start without a bot token**: it is the bot.

## Run (local)

```sh
export DISCORD_TOKEN=... DISCORD_GUILD_ID=... DISCORD_CATEGORY_ID=...
./gradlew bootRun                     # serves on :8090
```

The paired `osparty-api` points at it via `DISCORD_SERVICE_URL` (`http://osparty-discord:8090` in the
cluster, `http://localhost:8090` locally) and `DISCORD_INTERNAL_TOKEN`. The same shared secret works
in both directions: the API's voice calls come in, and this service pushes Discord-role badge state
back out to the API's `/internal/badges` endpoint (`OSPARTY_API_URL`).

## Deploy

The bot runs as a **singleton Deployment in the OSParty k3s cluster** (manifests in `k8s/`; replicas 1
with the `Recreate` strategy, so two gateway sessions never overlap, not even mid-rollout).
`.github/workflows/deploy-k8s.yml` (**Deploy Discord bot (Kubernetes)** in the Actions tab) builds the
image, pushes it to **`ghcr.io/osparty/osparty-discord`**, applies the manifests over one SSH to the
control plane, and tags + cuts a GitHub release only after a successful rollout. Versioning is
automatic semver (patch bump per run); pass an explicit version for a minor/major bump or to redeploy
an old one.

**Rollback**: `kubectl -n osparty rollout undo deployment/osparty-discord`, or a manual run with the
previous version.

Required repo **secrets**: `API_SERVER_ADDRESS` (control-plane host), `SSH_USER`, `SSH_KEY` (PEM, no
passphrase); optional `SSH_PORT`. Runtime env comes from the `osparty-discord-env` secret in the
`osparty` namespace; its keys are documented in `.env.example`. Remember the **Server Members Intent**
must be enabled in the Discord Developer Portal (Bot → Privileged Gateway Intents), or the gateway
rejects the session on startup.
