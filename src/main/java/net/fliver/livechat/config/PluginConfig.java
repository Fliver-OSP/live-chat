package net.fliver.livechat.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Typed read of config.yml - see src/main/resources/config.yml for what each key means. */
public final class PluginConfig {

  private final String language;
  private final int pollIntervalSeconds;
  private final String discordMessageFormat;
  private final boolean updateCheckEnabled;
  private final int updateCheckIntervalHours;

  private PluginConfig(
      String language,
      int pollIntervalSeconds,
      String discordMessageFormat,
      boolean updateCheckEnabled,
      int updateCheckIntervalHours) {
    this.language = language;
    this.pollIntervalSeconds = pollIntervalSeconds;
    this.discordMessageFormat = discordMessageFormat;
    this.updateCheckEnabled = updateCheckEnabled;
    this.updateCheckIntervalHours = updateCheckIntervalHours;
  }

  public static PluginConfig load(JavaPlugin plugin) {
    plugin.saveDefaultConfig();
    plugin.reloadConfig();
    FileConfiguration cfg = plugin.getConfig();

    String language = cfg.getString("language", "en_US");

    int pollIntervalSeconds = Math.max(1, cfg.getInt("poll-interval-seconds", 3));

    String discordMessageFormat =
        cfg.getString(
            "discord-message-format",
            "<gray>[Discord]</gray> <white><player></white><gray>: </gray><white><message></white>");

    boolean updateCheckEnabled = cfg.getBoolean("update-check.enabled", true);
    int updateCheckIntervalHours = Math.max(1, cfg.getInt("update-check.interval-hours", 6));

    return new PluginConfig(
        language,
        pollIntervalSeconds,
        discordMessageFormat,
        updateCheckEnabled,
        updateCheckIntervalHours);
  }

  public String language() {
    return language;
  }

  public int pollIntervalSeconds() {
    return pollIntervalSeconds;
  }

  public String discordMessageFormat() {
    return discordMessageFormat;
  }

  public boolean updateCheckEnabled() {
    return updateCheckEnabled;
  }

  public int updateCheckIntervalHours() {
    return updateCheckIntervalHours;
  }
}
