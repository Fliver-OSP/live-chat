package net.fliver.livechat.relay;

import java.util.List;
import java.util.logging.Level;
import net.fliver.livechat.api.FliverLiveChatApi;
import net.fliver.livechat.api.FliverLiveChatApi.InboundMessage;
import net.fliver.livechat.state.PairingState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Discord -> Minecraft. A single async task that blocks in a loop calling
 * the long-poll endpoint back-to-back (see PROTOCOL.md) - not a Bukkit
 * timer, since the natural pacing here comes from the backend holding the
 * request open, not a fixed tick interval. stop() flips a flag the loop
 * checks between requests; because the in-flight HTTP call can't be
 * force-cancelled, a stop can take up to that call's timeout to actually
 * exit - acceptable for a plugin disable/reload, not something callers
 * should block on.
 */
public final class InboundPoller {

  private final JavaPlugin plugin;
  private final FliverLiveChatApi api;
  private final PairingState state;
  private final String messageFormat;

  private volatile boolean running;

  public InboundPoller(JavaPlugin plugin, FliverLiveChatApi api, PairingState state, String messageFormat) {
    this.plugin = plugin;
    this.api = api;
    this.state = state;
    this.messageFormat = messageFormat;
  }

  public boolean isRunning() {
    return running;
  }

  public synchronized void start() {
    if (running) return;
    running = true;
    Bukkit.getScheduler().runTaskAsynchronously(plugin, this::loop);
  }

  public synchronized void stop() {
    running = false;
  }

  private void loop() {
    while (running) {
      String token = state.token();
      if (token == null) {
        running = false;
        return;
      }

      try {
        List<InboundMessage> messages = api.relayInboundWait(token);
        for (InboundMessage message : messages) {
          broadcast(message);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception failure) {
        plugin
            .getLogger()
            .log(Level.WARNING, "Live Chat: could not reach the backend for Discord -> Minecraft relay, retrying shortly: " + failure.getMessage());
        sleepQuietly(5000);
      }
    }
  }

  private void broadcast(InboundMessage message) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              Component rendered =
                  MiniMessage.miniMessage()
                      .deserialize(
                          messageFormat,
                          Placeholder.unparsed("player", message.authorName()),
                          Placeholder.unparsed("message", message.content()));
              Bukkit.broadcast(rendered);
            });
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
