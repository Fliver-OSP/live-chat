<p align="center">
  <a href="https://github.com/Fliver-OSP/live-chat">
    <img src=".github/assets/fliver-live-chat-banner.png" alt="Fliver Live Chat" />
  </a>
</p>

A Paper plugin that relays Minecraft server chat to a Discord channel and
back, in real time. It's its own plugin - it doesn't bundle with, depend on,
or require pairing with any other Fliver product.

- **No open ports, no second host.** The plugin only makes outbound HTTPS
  calls.
- **No Discord Developer Portal setup for you.** Live Chat doesn't ask you
  to create a Discord bot, an OAuth app, or a webhook - none of that lives
  in this plugin at all. It talks to a backend (Fliver's hosted one by
  default) that holds the real Discord bot, and you just sign in with your
  own Discord account to tell it which server/channel to use.
- **Messages show up as the player**, name and Minecraft head included, not
  as a generic bot line.
- **Open source, Apache-2.0.** See [PROTOCOL.md](PROTOCOL.md) if you want to
  run your own backend instead of Fliver's - this plugin isn't locked to
  one vendor.

## Setup

1. Install the jar in your server's `plugins/` folder and start the server.
2. Run `/live-chat auth`. It prints a link - open it and sign in with
   Discord.
3. Back on the server, run `/live-chat list`. It lists the Discord servers
   you own or moderate. If the bot isn't in the one you want yet, the
   listing includes an invite link.
4. Run `/live-chat select <number>` to pick that server, then
   `/live-chat list` again to see its text channels.
5. Run `/live-chat add <number>` for each channel you want chat relayed
   to and from. You can add more than one.

That's it - chat now flows both ways. `/live-chat status` shows the current
pairing, selected server, and linked channels. `/live-chat remove <number>`
unlinks a channel; `/live-chat reload` reloads `config.yml` and the
language file.

## Supported versions

Compiled against Paper 1.20.1 with `api-version: '1.20'` set in
`plugin.yml` - Paper's own mechanism for "compile once, keep loading on
future versions" (see `pom.xml`'s comment on why). The plugin only uses API
that's been stable since that floor, so one jar keeps working release over
release without needing a rebuild for every new Minecraft version.

## Configuration

`config.yml` has no Discord credentials in it and no backend URL either -
see the file itself for what each setting does (language, poll interval,
the Discord→Minecraft message format). The only thing this plugin stores
locally is its own pairing token from `/live-chat auth`, encrypted at rest
(`pairing.key` / `pairing.dat` in the plugin's data folder) with a key
generated fresh on first run - never shipped in the jar, never the same
across two installs.

## Which backend it talks to

Hardcoded to `https://fliver.net` (see `api/FliverLiveChatApi.java`) - on
purpose, not a `config.yml` setting. A value sitting in a plain-text config
is something a server owner could be handed by a "guide" or a copied config
and never think twice about, which is enough to phish the whole
`/live-chat auth` flow through a look-alike backend. This doesn't lock out
self-hosting: [PROTOCOL.md](PROTOCOL.md) is the public contract, and anyone
can run their own compatible backend and point their own build of this
plugin at it - either change the constant and rebuild, or run with
`-Dfliver.livechat.apiBaseUrl=https://your-backend.example` for
local/testing use without a rebuild.

## Languages

Ships with `en_US`. Add another language by copying `lang/en_US.yml` inside
the plugin's data folder, translating it, and pointing `config.yml`'s
`language:` at the new file's name - no rebuild.

## Building from source

```
mvn package
```
