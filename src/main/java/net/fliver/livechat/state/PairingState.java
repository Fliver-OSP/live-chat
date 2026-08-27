package net.fliver.livechat.state;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fliver.livechat.api.Json;
import net.fliver.livechat.crypto.PairingCrypto;

/**
 * The plugin's own pairing token + selected guild/channels, encrypted at
 * rest via PairingCrypto and stored outside config.yml (that file is meant
 * to be hand-edited by a server admin; this is machine-managed and holds
 * the only secret this plugin ever has - see PROTOCOL.md).
 */
public final class PairingState {

  public record ChannelRef(String id, String channelId, String channelName) {}

  private final File dataFile;
  private final PairingCrypto crypto = new PairingCrypto();

  private String token;
  private String guildId;
  private String guildName;
  private final List<ChannelRef> channels = new ArrayList<>();

  public PairingState(File dataFolder) {
    this.dataFile = new File(dataFolder, "pairing.dat");
  }

  public synchronized void load() throws Exception {
    crypto.init(dataFile.getParentFile());
    if (!dataFile.isFile()) return;

    String encrypted = Files.readString(dataFile.toPath(), StandardCharsets.UTF_8).trim();
    if (encrypted.isEmpty()) return;

    Map<String, Object> data = Json.asObject(Json.parse(crypto.decrypt(encrypted)));
    token = Json.asString(data.get("token"), null);
    guildId = Json.asString(data.get("guildId"), null);
    guildName = Json.asString(data.get("guildName"), null);
    channels.clear();
    Object rawChannels = data.get("channels");
    if (rawChannels instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> c = Json.asObject(item);
        channels.add(
            new ChannelRef(
                Json.asString(c.get("id"), ""),
                Json.asString(c.get("channelId"), ""),
                Json.asString(c.get("channelName"), "")));
      }
    }
  }

  public synchronized void save() throws Exception {
    Map<String, Object> data = Json.newObject();
    data.put("token", token);
    data.put("guildId", guildId);
    data.put("guildName", guildName);
    List<Object> channelList = new ArrayList<>();
    for (ChannelRef c : channels) {
      Map<String, Object> obj = Json.newObject();
      obj.put("id", c.id());
      obj.put("channelId", c.channelId());
      obj.put("channelName", c.channelName());
      channelList.add(obj);
    }
    data.put("channels", channelList);

    String encrypted = crypto.encrypt(Json.write(data));
    Files.writeString(dataFile.toPath(), encrypted, StandardCharsets.UTF_8);
  }

  public synchronized boolean isLinked() {
    return token != null && !token.isBlank();
  }

  public synchronized String token() {
    return token;
  }

  public synchronized void setToken(String token) {
    this.token = token;
  }

  public synchronized String guildId() {
    return guildId;
  }

  public synchronized String guildName() {
    return guildName;
  }

  public synchronized boolean hasGuild() {
    return guildId != null && !guildId.isBlank();
  }

  public synchronized void setGuild(String guildId, String guildName) {
    this.guildId = guildId;
    this.guildName = guildName;
  }

  public synchronized List<ChannelRef> channels() {
    return List.copyOf(channels);
  }

  public synchronized void setChannels(List<ChannelRef> newChannels) {
    channels.clear();
    channels.addAll(newChannels);
  }

  public synchronized void addChannel(ChannelRef channel) {
    channels.removeIf(c -> c.channelId().equals(channel.channelId()));
    channels.add(channel);
  }

  public synchronized void removeChannelByRowId(String rowId) {
    channels.removeIf(c -> c.id().equals(rowId));
  }

  /** Clears everything in memory (not on disk) - used when reload sees a broken/undecryptable pairing.dat. */
  public synchronized void reset() {
    token = null;
    guildId = null;
    guildName = null;
    channels.clear();
  }

  /** Clears memory and removes pairing.dat from disk (unlink). */
  public synchronized void clearPersisted() throws Exception {
    reset();
    if (dataFile.isFile()) {
      Files.delete(dataFile.toPath());
    }
  }
}
