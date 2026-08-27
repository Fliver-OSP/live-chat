package net.fliver.livechat.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.fliver.livechat.LiveChatPlugin;
import net.fliver.livechat.api.FliverLiveChatApi;
import net.fliver.livechat.state.PairingState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** /live-chat auth|list|servers|select|add|remove|status|reload - see PROTOCOL.md for what each subcommand calls. */
public final class LiveChatCommand implements CommandExecutor, TabCompleter {

  private static final List<String> SUBCOMMANDS =
      List.of("auth", "list", "servers", "select", "add", "remove", "status", "reload");

  private final LiveChatPlugin plugin;

  // Single-admin-at-a-time assumption is fine for v1: these just remember
  // the numbers /live-chat list or /live-chat servers most recently printed,
  // so /live-chat select <n> and /live-chat add <n> know what "n" refers to.
  private volatile List<FliverLiveChatApi.Guild> lastGuildListing = List.of();
  private volatile List<FliverLiveChatApi.ChannelOption> lastChannelListing = List.of();
  private volatile boolean authPollInProgress = false;

  public LiveChatCommand(LiveChatPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("livechat.admin")) {
      sender.sendMessage(msg("no-permission"));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("unknown-subcommand"));
      return true;
    }

    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "auth" -> handleAuth(sender);
      case "list" -> handleList(sender);
      case "servers" -> handleServers(sender);
      case "select" -> handleSelect(sender, args);
      case "add" -> handleAdd(sender, args);
      case "remove" -> handleRemove(sender, args);
      case "status" -> handleStatus(sender);
      case "reload" -> handleReload(sender);
      default -> sender.sendMessage(msg("unknown-subcommand"));
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      String partial = args[0].toLowerCase(Locale.ROOT);
      List<String> out = new ArrayList<>();
      for (String sub : SUBCOMMANDS) {
        if (sub.startsWith(partial)) out.add(sub);
      }
      return out;
    }

    if (args.length == 2) {
      String partial = args[1].toLowerCase(Locale.ROOT);
      List<String> options =
          switch (args[0].toLowerCase(Locale.ROOT)) {
            case "select" -> indexSuggestions(lastGuildListing.size());
            case "add" -> lastChannelListing.stream().map(FliverLiveChatApi.ChannelOption::name).toList();
            case "remove" -> plugin.state().channels().stream().map(PairingState.ChannelRef::channelName).toList();
            default -> List.<String>of();
          };
      return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
    }

    return List.of();
  }

  private static List<String> indexSuggestions(int count) {
    List<String> out = new ArrayList<>();
    for (int i = 1; i <= count; i++) out.add(String.valueOf(i));
    return out;
  }

  // ---- auth ----

  private void handleAuth(CommandSender sender) {
    if (authPollInProgress) {
      reply(sender, "auth.already-pending");
      return;
    }
    authPollInProgress = true;
    runAsync(
        () -> {
          try {
            FliverLiveChatApi.PairStart start = plugin.api().pairStart();
            reply(sender, "auth.started", "url", start.authUrl());
            reply(
                sender,
                "auth.started-hint",
                "minutes",
                String.valueOf(Math.max(1, start.expiresInSeconds() / 60)));
            pollAuth(sender, start.pairCode(), start.expiresInSeconds());
          } catch (Exception e) {
            authPollInProgress = false;
            reply(sender, "auth.failed", "message", messageOf(e));
          }
        });
  }

  private void pollAuth(CommandSender sender, String pairCode, int expiresInSeconds) {
    long deadline = System.currentTimeMillis() + expiresInSeconds * 1000L;
    long intervalMs = plugin.pollIntervalSeconds() * 1000L;
    try {
      while (System.currentTimeMillis() < deadline) {
        Thread.sleep(intervalMs);
        FliverLiveChatApi.PairPollResult result = plugin.api().pairPoll(pairCode);
        if (result instanceof FliverLiveChatApi.PairLinked linked) {
          plugin.state().setToken(linked.token());
          plugin.state().save();
          authPollInProgress = false;
          reply(sender, "auth.success");
          return;
        }
      }
      authPollInProgress = false;
      reply(sender, "auth.timeout");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      authPollInProgress = false;
    } catch (Exception e) {
      authPollInProgress = false;
      reply(sender, "auth.failed", "message", messageOf(e));
    }
  }

  // ---- list ----

  private void handleList(CommandSender sender) {
    if (!plugin.state().isLinked()) {
      reply(sender, "list.not-linked");
      return;
    }
    runAsync(
        () -> {
          try {
            if (!plugin.state().hasGuild()) {
              listGuilds(sender);
            } else {
              listChannels(sender);
            }
          } catch (Exception e) {
            reply(sender, "list.failed", "message", messageOf(e));
          }
        });
  }

  /** Always lists Discord servers (even if one is already selected) so admins can switch. */
  private void handleServers(CommandSender sender) {
    if (!plugin.state().isLinked()) {
      reply(sender, "list.not-linked");
      return;
    }
    runAsync(
        () -> {
          try {
            listGuilds(sender);
          } catch (Exception e) {
            reply(sender, "list.failed", "message", messageOf(e));
          }
        });
  }

  private void listGuilds(CommandSender sender) throws Exception {
    List<FliverLiveChatApi.Guild> guilds = plugin.api().listGuilds(plugin.state().token());
    lastGuildListing = guilds;
    if (guilds.isEmpty()) {
      reply(sender, "list.no-guilds");
      return;
    }
    reply(sender, "list.guild-header");
    String currentGuildId = plugin.state().guildId();
    for (int i = 0; i < guilds.size(); i++) {
      FliverLiveChatApi.Guild guild = guilds.get(i);
      int index = i + 1;
      boolean selected = currentGuildId != null && currentGuildId.equals(guild.id());

      // Only clickable-to-select when the bot is actually there - selecting
      // a guild it hasn't been invited to just bounces off the backend.
      Component name =
          guild.botPresent()
              ? Component.text(guild.name(), NamedTextColor.WHITE)
                  .clickEvent(ClickEvent.runCommand("/live-chat select " + index))
                  .hoverEvent(HoverEvent.showText(Component.text("Click to select", NamedTextColor.GRAY)))
              : Component.text(guild.name(), NamedTextColor.WHITE);

      Component status;
      if (selected) {
        status = Component.text(" (selected)", NamedTextColor.AQUA);
      } else if (guild.botPresent()) {
        status = Component.text(" (bot in server)", NamedTextColor.GREEN);
      } else if (guild.inviteUrl() != null) {
        status =
            Component.text(" (bot not invited - click to invite)", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.openUrl(guild.inviteUrl()));
      } else {
        status = Component.text(" (bot not invited)", NamedTextColor.YELLOW);
      }

      replyComponent(sender, Component.text(index + ") ", NamedTextColor.GRAY).append(name).append(status));
    }
  }

  private void listChannels(CommandSender sender) throws Exception {
    FliverLiveChatApi.ChannelsResult result = plugin.api().listChannels(plugin.state().token());
    lastChannelListing = result.available();
    plugin.state().setChannels(toStateChannels(result.linked()));
    plugin.state().save();
    plugin.syncPollerState();

    if (result.available().isEmpty()) {
      reply(sender, "list.no-channels");
      return;
    }
    reply(sender, "list.channel-header");
    Set<String> linkedIds =
        result.linked().stream().map(FliverLiveChatApi.LinkedChannel::channelId).collect(Collectors.toSet());
    for (int i = 0; i < result.available().size(); i++) {
      FliverLiveChatApi.ChannelOption channel = result.available().get(i);
      int index = i + 1;
      boolean linked = linkedIds.contains(channel.id());

      Component name =
          linked
              ? Component.text("#" + channel.name(), NamedTextColor.GRAY)
              : Component.text("#" + channel.name(), NamedTextColor.WHITE)
                  .clickEvent(ClickEvent.runCommand("/live-chat add " + index))
                  .hoverEvent(HoverEvent.showText(Component.text("Click to add", NamedTextColor.GRAY)));
      Component suffix = linked ? Component.text(" (already linked)", NamedTextColor.GRAY) : Component.empty();

      replyComponent(sender, Component.text(index + ") ", NamedTextColor.GRAY).append(name).append(suffix));
    }
  }

  // ---- select ----

  private void handleSelect(CommandSender sender, String[] args) {
    if (args.length < 2) {
      reply(sender, "select.usage");
      return;
    }
    Integer index = tryParseInt(args[1]);
    List<FliverLiveChatApi.Guild> guilds = lastGuildListing;
    if (index == null || index < 1 || index > guilds.size()) {
      reply(sender, "select.invalid");
      return;
    }
    FliverLiveChatApi.Guild chosen = guilds.get(index - 1);
    String previousGuildId = plugin.state().guildId();
    runAsync(
        () -> {
          try {
            plugin.api().selectGuild(plugin.state().token(), chosen.id(), chosen.name());
            plugin.state().setGuild(chosen.id(), chosen.name());
            // Switching servers invalidates the previous guild's linked channels locally.
            if (previousGuildId == null || !previousGuildId.equals(chosen.id())) {
              plugin.state().setChannels(List.of());
            }
            plugin.state().save();
            plugin.syncPollerState();
            reply(sender, "select.success", "name", chosen.name());
          } catch (Exception e) {
            reply(sender, "select.failed", "message", messageOf(e));
          }
        });
  }

  // ---- add ----

  private void handleAdd(CommandSender sender, String[] args) {
    if (args.length < 2) {
      reply(sender, "add.usage");
      return;
    }
    String arg = args[1];
    Integer index = tryParseInt(arg);
    List<FliverLiveChatApi.ChannelOption> options = lastChannelListing;

    FliverLiveChatApi.ChannelOption byName = findByName(options, FliverLiveChatApi.ChannelOption::name, arg);

    String channelId;
    String channelNameHint;
    if (index != null && index >= 1 && index <= options.size()) {
      FliverLiveChatApi.ChannelOption picked = options.get(index - 1);
      channelId = picked.id();
      channelNameHint = picked.name();
    } else if (byName != null) {
      // Matches a name /live-chat list just printed - e.g. tab-completed, or
      // clicked-then-retyped. Channel names never contain spaces, so a
      // single arg is enough to match exactly.
      channelId = byName.id();
      channelNameHint = byName.name();
    } else if (index == null && isDigits(arg)) {
      channelId = arg;
      channelNameHint = arg;
    } else {
      reply(sender, "add.invalid");
      return;
    }

    runAsync(
        () -> {
          try {
            FliverLiveChatApi.LinkedChannel linked =
                plugin.api().addChannel(plugin.state().token(), channelId, channelNameHint);
            plugin.state().addChannel(new PairingState.ChannelRef(linked.rowId(), linked.channelId(), linked.channelName()));
            plugin.state().save();
            plugin.syncPollerState();
            reply(sender, "add.success", "name", linked.channelName());
          } catch (Exception e) {
            reply(sender, "add.failed", "message", messageOf(e));
          }
        });
  }

  // ---- remove ----

  private void handleRemove(CommandSender sender, String[] args) {
    if (args.length < 2) {
      reply(sender, "remove.usage");
      return;
    }
    String arg = args[1];
    List<PairingState.ChannelRef> linked = plugin.state().channels();
    Integer index = tryParseInt(arg);

    PairingState.ChannelRef target = null;
    if (index != null && index >= 1 && index <= linked.size()) {
      target = linked.get(index - 1);
    } else {
      for (PairingState.ChannelRef c : linked) {
        if (c.channelId().equals(arg) || c.id().equals(arg) || c.channelName().equalsIgnoreCase(arg)) {
          target = c;
          break;
        }
      }
    }
    if (target == null) {
      reply(sender, "remove.invalid");
      return;
    }

    PairingState.ChannelRef finalTarget = target;
    runAsync(
        () -> {
          try {
            plugin.api().removeChannel(plugin.state().token(), finalTarget.id());
            plugin.state().removeChannelByRowId(finalTarget.id());
            plugin.state().save();
            plugin.syncPollerState();
            reply(sender, "remove.success", "name", finalTarget.channelName());
          } catch (Exception e) {
            reply(sender, "remove.failed", "message", messageOf(e));
          }
        });
  }

  // ---- status / reload ----

  private void handleStatus(CommandSender sender) {
    // Everything here is local state - no network call, runs on the calling thread.
    if (!plugin.state().isLinked()) {
      sender.sendMessage(msg("status.not-linked"));
      return;
    }
    sender.sendMessage(msg("status.linked"));

    if (!plugin.state().hasGuild()) {
      sender.sendMessage(msg("status.no-guild"));
    } else {
      sender.sendMessage(msg("status.guild", "name", plugin.state().guildName()));
      List<PairingState.ChannelRef> channels = plugin.state().channels();
      if (channels.isEmpty()) {
        sender.sendMessage(msg("status.no-channels"));
      } else {
        sender.sendMessage(msg("status.channels-header"));
        for (int i = 0; i < channels.size(); i++) {
          sender.sendMessage(
              msg(
                  "status.channel-line",
                  "index",
                  String.valueOf(i + 1),
                  "name",
                  channels.get(i).channelName()));
        }
      }
    }

    sender.sendMessage(
        msg(plugin.poller().isRunning() ? "status.poller-running" : "status.poller-stopped"));
  }

  private void handleReload(CommandSender sender) {
    plugin.reloadConfigAndLang();
    sender.sendMessage(msg("reload.success"));
  }

  // ---- helpers ----

  private String msg(String key, String... placeholders) {
    return plugin.trio().messages().prefixed(key, placeholders);
  }

  private void runAsync(Runnable task) {
    plugin.trio().scheduler().async(task);
  }

  private void reply(CommandSender sender, String key, String... placeholders) {
    String message = msg(key, placeholders);
    plugin.trio().scheduler().sync(() -> sender.sendMessage(message));
  }

  // No prefix on these - they're indented sub-items under a header line
  // (reply(...) already printed the header with the prefix), and clickable
  // components need Component, not the plain colored String reply() sends.
  private void replyComponent(CommandSender sender, Component message) {
    plugin.trio().scheduler().sync(() -> sender.sendMessage(message));
  }

  private static String messageOf(Exception e) {
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }

  private static Integer tryParseInt(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean isDigits(String s) {
    return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
  }

  private static <T> T findByName(List<T> items, java.util.function.Function<T, String> nameOf, String name) {
    for (T item : items) {
      if (nameOf.apply(item).equalsIgnoreCase(name)) return item;
    }
    return null;
  }

  private static List<PairingState.ChannelRef> toStateChannels(List<FliverLiveChatApi.LinkedChannel> linked) {
    List<PairingState.ChannelRef> out = new ArrayList<>();
    for (FliverLiveChatApi.LinkedChannel c : linked) {
      out.add(new PairingState.ChannelRef(c.rowId(), c.channelId(), c.channelName()));
    }
    return out;
  }
}
