package net.fliver.livechat.minecraft;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.fliver.livechat.LiveChatPlugin;
import net.fliver.livechat.api.FliverLiveChatApi;
import net.fliver.livechat.state.PairingState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Minecraft server events -> Discord via the same outbound relay as chat. */
public final class ServerEventListener implements Listener {

  private final LiveChatPlugin plugin;
  private final FliverLiveChatApi api;
  private final PairingState state;
  /** Players kicked this session tick — skip duplicate leave when kick relay is on. */
  private final Set<UUID> pendingKickLeaveSkip = ConcurrentHashMap.newKeySet();

  public ServerEventListener(LiveChatPlugin plugin, FliverLiveChatApi api, PairingState state) {
    this.plugin = plugin;
    this.api = api;
    this.state = state;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    if (!plugin.eventEnabled("join")) return;
    Player player = event.getPlayer();
    relay(player, format("join", player.getName(), null, null, null, null));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    if (!plugin.eventEnabled("leave")) return;
    if (pendingKickLeaveSkip.remove(event.getPlayer().getUniqueId())) {
      return;
    }
    Player player = event.getPlayer();
    relay(player, format("leave", player.getName(), null, null, null, null));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDeath(PlayerDeathEvent event) {
    if (!plugin.eventEnabled("death")) return;
    Player player = event.getEntity();
    String deathPlain = PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
    if (deathPlain.isBlank()) {
      deathPlain = player.getName() + " died";
    }
    relay(player, format("death", player.getName(), deathPlain, null, null, null));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onAdvancement(PlayerAdvancementDoneEvent event) {
    if (!plugin.eventEnabled("advancement")) return;
    Player player = event.getPlayer();
    String advancementName = advancementPlainName(event);
    relay(player, format("advancement", player.getName(), null, advancementName, null, null));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGameModeChange(PlayerGameModeChangeEvent event) {
    if (!plugin.eventEnabled("gamemode")) return;
    Player player = event.getPlayer();
    String mode = gameModeLabel(event.getNewGameMode());
    relay(player, format("gamemode", player.getName(), null, null, mode, null));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onKick(PlayerKickEvent event) {
    if (!plugin.eventEnabled("kick")) return;
    Player player = event.getPlayer();
    pendingKickLeaveSkip.add(player.getUniqueId());
    String reason = plainComponent(event.reason());
    if (reason.isBlank()) {
      reason = "No reason given";
    }
    relay(player, format("kick", player.getName(), null, null, null, reason));
  }

  private String format(
      String kind,
      String playerName,
      String deathMessage,
      String advancement,
      String gamemode,
      String reason) {
    String template = plugin.eventFormat(kind);
    String out = template.replace("%player%", playerName);
    if (deathMessage != null) {
      out = out.replace("%death_message%", deathMessage);
    }
    if (advancement != null) {
      out = out.replace("%advancement%", advancement);
    }
    if (gamemode != null) {
      out = out.replace("%gamemode%", gamemode);
    }
    if (reason != null) {
      out = out.replace("%reason%", reason);
    }
    return out;
  }

  private static String advancementPlainName(PlayerAdvancementDoneEvent event) {
    var display = event.getAdvancement().getDisplay();
    if (display != null) {
      Component title = display.title();
      if (title != null) {
        String plain = plainComponent(title);
        if (!plain.isBlank()) {
          return plain;
        }
      }
    }
    return event.getAdvancement().getKey().getKey();
  }

  private static String gameModeLabel(GameMode mode) {
    return mode.name().toLowerCase(Locale.ROOT);
  }

  private static String plainComponent(Component component) {
    if (component == null) {
      return "";
    }
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  private void relay(Player player, String content) {
    if (!state.isLinked() || state.channels().isEmpty()) return;
    if (content.isBlank()) return;

    String token = state.token();
    String playerName = player.getName();
    String playerUuid = player.getUniqueId().toString();

    plugin
        .trio()
        .scheduler()
        .async(
            () -> {
              try {
                api.relayOutbound(token, playerName, playerUuid, content);
              } catch (Exception failure) {
                plugin
                    .getLogger()
                    .log(Level.FINE, "Could not relay server event to Discord", failure);
              }
            });
  }
}
