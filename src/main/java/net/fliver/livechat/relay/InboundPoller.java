package net.fliver.livechat.relay;

import java.util.List;
import java.util.logging.Level;
import net.fliver.livechat.LiveChatPlugin;
import net.fliver.livechat.api.FliverLiveChatApi;
import net.fliver.livechat.api.FliverLiveChatApi.InboundMessage;
import net.fliver.livechat.state.PairingState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;

/**
 * Discord -> Minecraft. A single async task that blocks in a loop calling
 * the long-poll endpoint back-to-back (see PROTOCOL.md) - not a Bukkit
 * timer, since the natural pacing here comes from the backend holding the
 * request open, not a fixed tick interval.
 *
 * <p>Backend outages are retried with exponential backoff. Console gets at
 * most one WARNING when the outage starts, then stays quiet until recovery.
 */
public final class InboundPoller {

  private static final long INITIAL_BACKOFF_MS = 5_000L;
  private static final long MAX_BACKOFF_MS = 60_000L;

  private final LiveChatPlugin plugin;
  private final FliverLiveChatApi api;
  private final PairingState state;
  private final String messageFormat;

  private volatile boolean running;
  private Object loopTask;
  private volatile Thread loopThread;
  private long backoffMs = INITIAL_BACKOFF_MS;
  private int suppressedFailures;
  private boolean outageActive;

  public InboundPoller(
      LiveChatPlugin plugin, FliverLiveChatApi api, PairingState state, String messageFormat) {
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
    backoffMs = INITIAL_BACKOFF_MS;
    suppressedFailures = 0;
    outageActive = false;
    loopTask =
        plugin
            .trio()
            .scheduler()
            .async(
                () -> {
                  loopThread = Thread.currentThread();
                  try {
                    loop();
                  } finally {
                    loopThread = null;
                  }
                });
  }

  public synchronized void stop() {
    running = false;
    Thread thread = loopThread;
    if (thread != null) {
      thread.interrupt();
    }
    if (loopTask != null) {
      plugin.trio().scheduler().cancel(loopTask);
      loopTask = null;
    }
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
        if (!running) return;
        onSuccess();
        for (InboundMessage message : messages) {
          if (!running) return;
          broadcast(message);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception failure) {
        if (!running) return;
        onFailure(failure);
        if (!sleepQuietly(backoffMs)) return;
        backoffMs = Math.min(MAX_BACKOFF_MS, Math.max(INITIAL_BACKOFF_MS, backoffMs * 2));
      }
    }
  }

  private void onSuccess() {
    if (outageActive) {
      String recovered =
          suppressedFailures > 0
              ? "Live Chat: backend reachable again (Discord -> Minecraft relay resumed; "
                  + suppressedFailures
                  + " failure(s) during outage)."
              : "Live Chat: backend reachable again (Discord -> Minecraft relay resumed).";
      plugin.getLogger().info(recovered);
    }
    outageActive = false;
    suppressedFailures = 0;
    backoffMs = INITIAL_BACKOFF_MS;
  }

  private void onFailure(Exception failure) {
    String detail =
        failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    if (!outageActive) {
      outageActive = true;
      suppressedFailures = 0;
      plugin
          .getLogger()
          .log(
              Level.WARNING,
              "Live Chat: Discord -> Minecraft relay unreachable — retrying quietly until the backend is back. ("
                  + detail
                  + ")");
    } else {
      suppressedFailures++;
      plugin.getLogger().log(Level.FINE, "Live Chat: relay retry failed: " + detail);
    }
  }

  private void broadcast(InboundMessage message) {
    plugin
        .trio()
        .scheduler()
        .sync(
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

  /** @return false if interrupted / stop requested */
  private boolean sleepQuietly(long millis) {
    long deadline = System.currentTimeMillis() + millis;
    while (running) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) return true;
      try {
        Thread.sleep(Math.min(remaining, 500L));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }
}
