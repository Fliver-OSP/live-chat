# Live Chat backend protocol

Live Chat never talks to Discord directly and never holds a Discord bot
token, OAuth client secret, or webhook URL. Instead it speaks plain HTTPS
JSON to a *backend* — the Fliver-hosted one at `https://fliver.net`, hardcoded
in `api/FliverLiveChatApi.java` (not a `config.yml` setting — see that
file's class comment for why: a config value is too easy to quietly
redirect through a phishing look-alike). That backend is the thing that
holds the Discord bot token and does the actual Discord API calls.

This document is the contract between the plugin and that backend. It exists
so anyone can stand up their own compatible backend and point their own copy
of the (Apache-2.0) plugin at it — you are not required to use Fliver's
hosted service. Change the constant and rebuild, or run with
`-Dfliver.livechat.apiBaseUrl=https://your-backend.example` for
local/testing use without a rebuild. If your backend implements the
endpoints below the same way, the plugin doesn't know or care who's running
it.

All responses are JSON with at least an `ok` boolean. On failure, `ok` is
`false` and `message` is a human-readable string safe to show a server admin.
Authenticated endpoints take `Authorization: Bearer <token>` and return
`401` for a missing/invalid/revoked token.

## Pairing (device-code style — no bearer token yet)

### `POST /api/live-chat/pair/start`

Called by `/livechat auth`. No auth, no body required.

Response:
```json
{ "ok": true, "pairCode": "AB12CD34", "authUrl": "https://fliver.net/live-chat/connect?code=AB12CD34", "expiresInSeconds": 600 }
```

The plugin prints `authUrl` for the admin to open in a browser, then polls
`pair/poll` every few seconds until it expires or links.

### `POST /api/live-chat/pair/poll`

Request: `{ "pairCode": "AB12CD34" }`

Response while waiting: `{ "ok": true, "status": "pending" }`

Response once the admin finishes signing in on the web page:
```json
{ "ok": true, "status": "linked", "token": "<long-lived bearer token>" }
```

`404`/`410` with `ok:false` once the code is unknown or expired — the plugin
should tell the admin to run `/livechat auth` again.

The plugin stores `token` encrypted locally (see the plugin's own
`PairingCrypto`/`PairingState`) and uses it as the Bearer token for every
call below. There is no separate "backend" concept of which Minecraft
server this is beyond that token — each install pairs independently.

## Guild + channel selection (Bearer)

### `GET /api/live-chat/guilds`

Returns the Discord servers the linked Discord account owns or moderates.

```json
{ "ok": true, "guilds": [
  { "id": "123", "name": "My Server", "botPresent": true, "inviteUrl": null },
  { "id": "456", "name": "Other Server", "botPresent": false, "inviteUrl": "https://discord.com/oauth2/authorize?..." }
]}
```

### `POST /api/live-chat/guild`

Request: `{ "guildId": "123", "guildName": "My Server" }`. Fails with
`ok:false` if the bot isn't in that guild yet. Sets the active guild for
this pairing.

### `GET /api/live-chat/channels`

```json
{ "ok": true,
  "guild": { "id": "123", "name": "My Server" },
  "available": [ { "id": "789", "name": "general" }, { "id": "790", "name": "minecraft-chat" } ],
  "linked": [ { "id": "row-abc", "channelId": "790", "channelName": "minecraft-chat" } ]
}
```

`available` is a live read of the guild's text channels. `linked` is what
`/livechat add` has already turned into a relay target (`id` here is the
backend's own row id, used to remove it again).

### `POST /api/live-chat/channels`

Request: `{ "channelId": "790", "channelName": "minecraft-chat" }`. Backend
creates a Discord webhook in that channel and stores it — the webhook
credential never reaches the plugin. Response:
`{ "ok": true, "channel": { "id": "row-abc", "channelId": "790", "channelName": "minecraft-chat" } }`

### `DELETE /api/live-chat/channels/{id}`

`{id}` is the backend row id from `linked`/the create response. Deletes the
webhook and stops relaying to that channel.

## Relay (Bearer)

### `POST /api/live-chat/relay/outbound`

Called on every in-game chat message. Request:
```json
{ "playerName": "Steve", "playerUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "message": "hello" }
```
Response: `{ "ok": true, "delivered": 2 }` (number of linked channels posted
to). The backend is responsible for posting through each channel's webhook
with `username`/`avatar_url` set to the player's name/skin head and
`allowed_mentions: { parse: [] }` so a player typing `@everyone` can't
actually ping anyone.

### `POST /api/live-chat/relay/inbound/wait`

Long-poll, empty body. The backend holds the request open (up to ~20s)
watching every linked channel for new messages, and returns as soon as it
finds any:
```json
{ "ok": true, "messages": [
  { "channelId": "790", "authorName": "SomeDiscordUser", "content": "hey", "id": "111222333" }
]}
```
or `{ "ok": true, "messages": [] }` on timeout with nothing new. The plugin
calls this in a loop from an async task. `authorName` is the Discord
member's guild nickname if set, else their username. The backend must never
return a message authored by a bot/webhook (that includes its own relay
posts — otherwise a message would echo back into the game).

How a backend actually gets these messages is an implementation detail
behind this endpoint, not part of the contract — the plugin can't tell the
difference. Fliver's hosted backend runs a small separate process holding a
real Discord gateway connection for this (instant push, and it's what
makes the bot show online) with an automatic fallback to polling Discord's
own `GET /channels/{id}/messages?after=` per linked channel when that
process isn't reachable. A minimal self-hosted backend can just do the
polling part and skip the gateway process entirely — it'll work, just with
a few extra seconds of latency on an idle channel.
