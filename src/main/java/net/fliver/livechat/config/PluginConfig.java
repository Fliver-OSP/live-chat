package net.fliver.livechat.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Typed read of config.yml - see src/main/resources/config.yml for what each key means. */
public final class PluginConfig {

  private final String language;
  private final int pollIntervalSeconds;
  private final String discordMessageFormat;

  private PluginConfig(String language, int pollIntervalSeconds, String discordMessageFormat) {
    this.language = language;
    this.pollIntervalSeconds = pollIntervalSeconds;
    this.discordMessageFormat = discordMessageFormat;
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

    return new PluginConfig(language, pollIntervalSeconds, discordMessageFormat);
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
}
