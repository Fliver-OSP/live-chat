package net.fliver.livechat.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import net.fliver.livechat.LiveChatPlugin;
import net.fliver.livechat.api.Json;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Polls GitHub Releases for Fliver-OSP/live-chat and announces in-game when
 * a newer version than the installed jar is published. Failures stay quiet
 * (logged at FINE/WARNING) so a GitHub outage never breaks the relay.
 */
public final class UpdateChecker {

  private static final String RELEASES_LATEST =
      "https://api.github.com/repos/Fliver-OSP/live-chat/releases/latest";
  private static final String USER_AGENT = "Fliver-LiveChat-Plugin";

  private final LiveChatPlugin plugin;
  private final HttpClient http;
  private final boolean enabled;
  private final long intervalTicks;

  private volatile String lastAnnouncedTag;
  private Object task;

  public UpdateChecker(LiveChatPlugin plugin, boolean enabled, int intervalHours) {
    this.plugin = plugin;
    this.enabled = enabled;
    this.intervalTicks = Math.max(1, intervalHours) * 60L * 60L * 20L;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public synchronized void start() {
    stop();
    if (!enabled) return;
    // First check a minute after enable so startup isn't blocked on GitHub.
    task =
        plugin.trio().scheduler().timerAsync(this::checkOnce, 20L * 60L, intervalTicks);
  }

  public synchronized void stop() {
    if (task != null) {
      plugin.trio().scheduler().cancel(task);
      task = null;
    }
  }

  private void checkOnce() {
    try {
      Release latest = fetchLatest();
      if (latest == null) return;

      String current = plugin.getDescription().getVersion();
      if (!isNewer(latest.tag(), current)) {
        return;
      }
      if (latest.tag().equals(lastAnnouncedTag)) {
        return;
      }
      lastAnnouncedTag = latest.tag();
      announce(current, latest);
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

  private void announce(String current, Release latest) {
    String message =
        plugin
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
    plugin
        .trio()
        .scheduler()
        .sync(
            () -> {
              plugin.getLogger().info(ChatPlain.strip(message));
              Bukkit.getConsoleSender().sendMessage(message);
              for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp() || player.hasPermission("livechat.admin")) {
                  player.sendMessage(message);
                }
              }
            });
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

  /** Strips legacy color codes for the logger line. */
  private static final class ChatPlain {
    private ChatPlain() {}

    static String strip(String colored) {
      return colored.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }
  }
}
