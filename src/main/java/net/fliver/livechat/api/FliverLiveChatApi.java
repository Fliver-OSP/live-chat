package net.fliver.livechat.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only thing this plugin ever talks to over the network - see
 * PROTOCOL.md for the full contract. The base URL is a hardcoded constant,
 * not a config.yml setting - same reasoning as the sibling proprietary
 * plugin's FliverApi: a value sitting in a plain-text config file is
 * something a server owner could be handed by a "guide" or a look-alike
 * config and never think twice about, which would be enough to phish the
 * whole /live-chat auth handshake (and everything relayed after it) through
 * an attacker's backend instead of the real one. This does NOT lock
 * self-hosters out - PROTOCOL.md is the public contract, and pointing this
 * plugin at your own backend is a one-constant source change + rebuild
 * (below), or the -Dfliver.livechat.apiBaseUrl system property for
 * local/testing use without a rebuild.
 */
public final class FliverLiveChatApi {

  public static final class ApiException extends Exception {
    private final int statusCode;

    public ApiException(String message) {
      this(message, 0);
    }

    public ApiException(String message, int statusCode) {
      super(message);
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  private static final String DEFAULT_BASE_URL = "https://fliver.net";

  private final String baseUrl;
  private final HttpClient client;

  public FliverLiveChatApi() {
    this(resolveBaseUrl());
  }

  public FliverLiveChatApi(String baseUrl) {
    this.baseUrl = baseUrl;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  private static String resolveBaseUrl() {
    String override = System.getProperty("fliver.livechat.apiBaseUrl");
    return (override != null && !override.isBlank()) ? override.trim() : DEFAULT_BASE_URL;
  }

  // ---- Pairing (device-code flow, no bearer token yet) ----

  public record PairStart(String pairCode, String authUrl, int expiresInSeconds) {}

  public PairStart pairStart() throws ApiException, IOException, InterruptedException {
    Map<String, Object> res = post("/api/live-chat/pair/start", null, Map.of(), Duration.ofSeconds(15));
    return new PairStart(
        Json.asString(res.get("pairCode"), ""),
        Json.asString(res.get("authUrl"), ""),
        (int) doubleOr(res.get("expiresInSeconds"), 600));
  }

  public sealed interface PairPollResult permits PairPending, PairLinked {}

  public record PairPending() implements PairPollResult {}

  public record PairLinked(String token) implements PairPollResult {}

  public PairPollResult pairPoll(String pairCode) throws ApiException, IOException, InterruptedException {
    Map<String, Object> res =
        post("/api/live-chat/pair/poll", null, Map.of("pairCode", pairCode), Duration.ofSeconds(15));
    String status = Json.asString(res.get("status"), "pending");
    if ("linked".equals(status)) {
      return new PairLinked(Json.asString(res.get("token"), ""));
    }
    return new PairPending();
  }

  // ---- Guild + channel selection (Bearer) ----

  public record Guild(String id, String name, boolean botPresent, String inviteUrl) {}

  public List<Guild> listGuilds(String token) throws ApiException, IOException, InterruptedException {
    Map<String, Object> res = get("/api/live-chat/guilds", token);
    List<Guild> guilds = new ArrayList<>();
    for (Object raw : Json.asArray(res.getOrDefault("guilds", List.of()))) {
      Map<String, Object> g = Json.asObject(raw);
      guilds.add(
          new Guild(
              Json.asString(g.get("id"), ""),
              Json.asString(g.get("name"), ""),
              Json.asBoolean(g.get("botPresent"), false),
              Json.asString(g.get("inviteUrl"), null)));
    }
    return guilds;
  }

  public void selectGuild(String token, String guildId, String guildName)
      throws ApiException, IOException, InterruptedException {
    post(
        "/api/live-chat/guild",
        token,
        Map.of("guildId", guildId, "guildName", guildName),
        Duration.ofSeconds(15));
  }

  public record ChannelOption(String id, String name) {}

  public record LinkedChannel(String rowId, String channelId, String channelName) {}

  public record ChannelsResult(String guildName, List<ChannelOption> available, List<LinkedChannel> linked) {}

  public ChannelsResult listChannels(String token) throws ApiException, IOException, InterruptedException {
    Map<String, Object> res = get("/api/live-chat/channels", token);

    String guildName = null;
    Object guildRaw = res.get("guild");
    if (guildRaw instanceof Map<?, ?>) {
      guildName = Json.asString(Json.asObject(guildRaw).get("name"), null);
    }

    List<ChannelOption> available = new ArrayList<>();
    for (Object raw : Json.asArray(res.getOrDefault("available", List.of()))) {
      Map<String, Object> c = Json.asObject(raw);
      available.add(new ChannelOption(Json.asString(c.get("id"), ""), Json.asString(c.get("name"), "")));
    }

    List<LinkedChannel> linked = new ArrayList<>();
    for (Object raw : Json.asArray(res.getOrDefault("linked", List.of()))) {
      Map<String, Object> c = Json.asObject(raw);
      linked.add(
          new LinkedChannel(
              Json.asString(c.get("id"), ""),
              Json.asString(c.get("channelId"), ""),
              Json.asString(c.get("channelName"), "")));
    }

    return new ChannelsResult(guildName, available, linked);
  }

  public LinkedChannel addChannel(String token, String channelId, String channelName)
      throws ApiException, IOException, InterruptedException {
    Map<String, Object> res =
        post(
            "/api/live-chat/channels",
            token,
            Map.of("channelId", channelId, "channelName", channelName),
            Duration.ofSeconds(15));
    Map<String, Object> c = Json.asObject(res.get("channel"));
    return new LinkedChannel(
        Json.asString(c.get("id"), ""),
        Json.asString(c.get("channelId"), ""),
        Json.asString(c.get("channelName"), ""));
  }

  public void removeChannel(String token, String rowId) throws ApiException, IOException, InterruptedException {
    delete("/api/live-chat/channels/" + rowId, token);
  }

  /** Revokes this pairing, deletes all linked webhooks server-side, and invalidates the bearer token. */
  public void unlink(String token) throws ApiException, IOException, InterruptedException {
    post("/api/live-chat/unlink", token, Map.of(), Duration.ofSeconds(15));
  }

  // ---- Relay ----

  public int relayOutbound(String token, String playerName, String playerUuid, String message)
      throws ApiException, IOException, InterruptedException {
    Map<String, Object> res =
        post(
            "/api/live-chat/relay/outbound",
            token,
            Map.of("playerName", playerName, "playerUuid", playerUuid, "message", message),
            Duration.ofSeconds(15));
    return (int) doubleOr(res.get("delivered"), 0);
  }

  public record InboundMessage(String channelId, String authorName, String content, String id) {}

  /** Long-poll - the backend holds this open for up to ~20s (see PROTOCOL.md), so the client timeout here is deliberately generous. */
  public List<InboundMessage> relayInboundWait(String token)
      throws ApiException, IOException, InterruptedException {
    Map<String, Object> res =
        post("/api/live-chat/relay/inbound/wait", token, Map.of(), Duration.ofSeconds(30));
    List<InboundMessage> messages = new ArrayList<>();
    for (Object raw : Json.asArray(res.getOrDefault("messages", List.of()))) {
      Map<String, Object> m = Json.asObject(raw);
      messages.add(
          new InboundMessage(
              Json.asString(m.get("channelId"), ""),
              Json.asString(m.get("authorName"), ""),
              Json.asString(m.get("content"), ""),
              Json.asString(m.get("id"), "")));
    }
    return messages;
  }

  // ---- HTTP plumbing ----

  private URI uri(String path) {
    return URI.create(baseUrl + path);
  }

  private Map<String, Object> get(String path, String token) throws ApiException, IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET().timeout(Duration.ofSeconds(15));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return send(builder.build());
  }

  private Map<String, Object> post(String path, String token, Map<String, Object> body, Duration timeout)
      throws ApiException, IOException, InterruptedException {
    String json = Json.write(body);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .timeout(timeout);
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return send(builder.build());
  }

  private Map<String, Object> delete(String path, String token)
      throws ApiException, IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).DELETE().timeout(Duration.ofSeconds(15));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return send(builder.build());
  }

  private Map<String, Object> send(HttpRequest request)
      throws ApiException, IOException, InterruptedException {
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    Map<String, Object> json;
    try {
      json = Json.asObject(Json.parse(response.body()));
    } catch (RuntimeException malformed) {
      throw new ApiException(
          "Backend returned an unexpected response (HTTP " + response.statusCode() + ").",
          response.statusCode());
    }

    if (!Json.asBoolean(json.get("ok"), false)) {
      throw new ApiException(
          Json.asString(json.get("message"), "Request failed (HTTP " + response.statusCode() + ")."),
          response.statusCode());
    }
    return json;
  }

  private static double doubleOr(Object value, double fallback) {
    return value instanceof Number number ? number.doubleValue() : fallback;
  }
}
