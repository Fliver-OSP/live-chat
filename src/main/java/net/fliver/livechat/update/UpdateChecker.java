package net.fliver.livechat.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.fliver.livechat.LiveChatPlugin;
import net.fliver.livechat.api.Json;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Polls GitHub Releases for Fliver-OSP/live-chat and announces in-game when
 * a newer version than the installed jar is published. Failures stay quiet
 * (logged at FINE/WARNING) so a GitHub outage never breaks the relay.
 *
 * <p>Also notifies ops / {@code livechat.admin} when they join if an update is
 * still pending (so late joiners are not missed).
 */
public final class UpdateChecker implements Listener {

  private static final String RELEASES_LATEST =
      "https://api.github.com/repos/Fliver-OSP/live-chat/releases/latest";
  private static final String USER_AGENT = "Fliver-LiveChat-Plugin";
  private static final String FALLBACK_TEMPLATE =
      "&8[&dLive Chat&8] &r&eNew version available: &f%latest% &7(you have &f%current%&7). GitHub: &f%url%";
  private static final long JOIN_NOTIFY_DELAY_TICKS = 40L;

  private final LiveChatPlugin plugin;
  private final HttpClient http;

  private boolean enabled;
  private long intervalTicks;

  private volatile String lastBroadcastTag;
  private volatile PendingUpdate pending;
  private final Set<UUID> notifiedThisTag = ConcurrentHashMap.newKeySet();
  private Object task;

  public UpdateChecker(LiveChatPlugin plugin) {
    this.plugin = plugin;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /** Stop any timer, apply new settings, and start again if enabled. */
  public synchronized void restart(boolean enabled, int intervalHours) {
    stopTimer();
    this.enabled = enabled;
    this.intervalTicks = Math.max(1, intervalHours) * 60L * 60L * 20L;
    if (!enabled) return;
    // First check a minute after enable so startup isn't blocked on GitHub.
    task = plugin.trio().scheduler().timerAsync(this::checkOnce, 20L * 60L, intervalTicks);
  }

  public synchronized void stop() {
    stopTimer();
  }

  private void stopTimer() {
    if (task != null) {
      plugin.trio().scheduler().cancel(task);
      task = null;
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    PendingUpdate update = pending;
    if (update == null) return;
    Player player = event.getPlayer();
    if (!isAdmin(player)) return;
    if (!notifiedThisTag.add(player.getUniqueId())) return;

    String message = formatMessage(update.current(), update.latest());
    plugin
        .trio()
        .scheduler()
        .later(
            () -> {
              if (player.isOnline()) {
                player.sendMessage(message);
              }
            },
            JOIN_NOTIFY_DELAY_TICKS);
  }

  private void checkOnce() {
    try {
      Release latest = fetchLatest();
      if (latest == null) return;

      String current = plugin.getDescription().getVersion();
      if (!isNewer(latest.tag(), current)) {
        pending = null;
        return;
      }

      PendingUpdate next = new PendingUpdate(current, latest);
      PendingUpdate prev = pending;
      if (prev == null || !prev.latest().tag().equals(latest.tag())) {
        notifiedThisTag.clear();
      }
      pending = next;

      if (latest.tag().equals(lastBroadcastTag)) {
        return;
      }
      lastBroadcastTag = latest.tag();
      broadcast(next);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      plugin.getLogger().log(Level.WARNING, "Update check failed: " + e.getMessage());
    }
  }

  private Release fetchLatest() throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(RELEASES_LATEST))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      return null;
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("GitHub releases/latest HTTP " + response.statusCode());
    }
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    String tag = Json.asString(body.get("tag_name"), "");
    if (tag.isBlank()) return null;
    String url =
        Json.asString(
            body.get("html_url"),
            "https://github.com/Fliver-OSP/live-chat/releases/latest");
    return new Release(tag, url);
  }

  private void broadcast(PendingUpdate update) {
    String message = formatMessage(update.current(), update.latest());
    plugin
        .trio()
        .scheduler()
        .sync(
            () -> {
              plugin.getLogger().info(ChatPlain.strip(message));
              Bukkit.getConsoleSender().sendMessage(message);
              for (Player player : Bukkit.getOnlinePlayers()) {
                if (!isAdmin(player)) continue;
                notifiedThisTag.add(player.getUniqueId());
                player.sendMessage(message);
              }
            });
  }

  private String formatMessage(String current, Release latest) {
    if (plugin.trio().lang() != null && plugin.trio().lang().has("update.available")) {
      return plugin
          .trio()
          .messages()
          .prefixed(
              "update.available",
              "current",
              current,
              "latest",
              latest.tag(),
              "url",
              latest.url());
    }
    return ChatColor.translateAlternateColorCodes(
        '&',
        FALLBACK_TEMPLATE
            .replace("%current%", current)
            .replace("%latest%", latest.tag())
            .replace("%url%", latest.url()));
  }

  private static boolean isAdmin(Player player) {
    return player.isOp() || player.hasPermission("livechat.admin");
  }

  /** True when {@code remote} is strictly newer than {@code installed}. */
  static boolean isNewer(String remote, String installed) {
    return compareVersions(normalize(remote), normalize(installed)) > 0;
  }

  static String normalize(String version) {
    if (version == null) return "";
    String v = version.trim();
    if (v.startsWith("v") || v.startsWith("V")) {
      v = v.substring(1);
    }
    return v;
  }

  /**
   * Compares Maven-ish versions like {@code 0.1.0-beta} / {@code 1.2.3}. Positive if a &gt; b.
   * Core numeric segments first; a release without qualifier beats the same core with one
   * ({@code 1.0.0} &gt; {@code 1.0.0-beta}).
   */
  static int compareVersions(String a, String b) {
    if (a.equalsIgnoreCase(b)) return 0;
    String[] aq = splitCoreQualifier(a);
    String[] bq = splitCoreQualifier(b);
    int core = compareNumericCore(aq[0], bq[0]);
    if (core != 0) return core;
    boolean aHasQ = !aq[1].isEmpty();
    boolean bHasQ = !bq[1].isEmpty();
    if (!aHasQ && bHasQ) return 1;
    if (aHasQ && !bHasQ) return -1;
    return aq[1].compareToIgnoreCase(bq[1]);
  }

  private static String[] splitCoreQualifier(String version) {
    int dash = version.indexOf('-');
    if (dash < 0) return new String[] {version, ""};
    return new String[] {version.substring(0, dash), version.substring(dash + 1)};
  }

  private static int compareNumericCore(String a, String b) {
    String[] as = a.split("\\.");
    String[] bs = b.split("\\.");
    int n = Math.max(as.length, bs.length);
    for (int i = 0; i < n; i++) {
      int ai = i < as.length ? parseSegment(as[i]) : 0;
      int bi = i < bs.length ? parseSegment(bs[i]) : 0;
      if (ai != bi) return Integer.compare(ai, bi);
    }
    return 0;
  }

  private static int parseSegment(String s) {
    int end = 0;
    while (end < s.length() && s.charAt(end) >= '0' && s.charAt(end) <= '9') {
      end++;
    }
    if (end == 0) return 0;
    try {
      return Integer.parseInt(s.substring(0, end));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private record Release(String tag, String url) {}

  private record PendingUpdate(String current, Release latest) {}

  /** Strips legacy color codes for the logger line. */
  private static final class ChatPlain {
    private ChatPlain() {}

    static String strip(String colored) {
      return colored.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }
  }
}
