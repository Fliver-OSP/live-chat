package net.fliver.livechat.minecraft;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.logging.Level;
import net.fliver.livechat.LiveChatPlugin;
import net.fliver.livechat.api.FliverLiveChatApi;
import net.fliver.livechat.state.PairingState;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Minecraft -> Discord. Uses Paper's Adventure-based chat event, not Bukkit's deprecated AsyncPlayerChatEvent. */
public final class ChatListener implements Listener {

  private final LiveChatPlugin plugin;
  private final FliverLiveChatApi api;
  private final PairingState state;

  public ChatListener(LiveChatPlugin plugin, FliverLiveChatApi api, PairingState state) {
    this.plugin = plugin;
    this.api = api;
    this.state = state;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    if (!state.isLinked()) return;

    String message = PlainTextComponentSerializer.plainText().serialize(event.message());
    if (message.isBlank()) return;

    String playerName = event.getPlayer().getName();
    String playerUuid = event.getPlayer().getUniqueId().toString();
    String token = state.token();

    // AsyncChatEvent already runs off the main thread, but a slow HTTP call
    // here would still hold up whatever fired this event - hop onto our own
    // async task so relaying to Discord can never delay chat delivery.
    plugin
        .trio()
        .scheduler()
        .async(
            () -> {
              try {
                api.relayOutbound(token, playerName, playerUuid, message);
              } catch (Exception failure) {
                plugin.getLogger().log(Level.FINE, "Could not relay chat message to Discord", failure);
              }
            });
  }
}
