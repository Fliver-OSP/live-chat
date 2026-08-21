package net.fliver.livechat.lang;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads a message file from plugins/FliverLiveChat/lang/{code}.yml and
 * resolves dotted keys with %placeholder% substitution and "&" color codes.
 * Ships English (en_US) only - anyone can add a language by copying
 * lang/en_US.yml, translating it, and pointing config.yml's "language" at
 * the new file's name, no rebuild required.
 */
public final class Lang {
  private static final String DEFAULT_CODE = "en_US";
  private static final Pattern SAFE_CODE = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

  private final YamlConfiguration messages;

  private Lang(YamlConfiguration messages) {
    this.messages = messages;
  }

  public static Lang load(JavaPlugin plugin, String requestedCode) {
    Logger logger = plugin.getLogger();
    File langFolder = new File(plugin.getDataFolder(), "lang");

    File defaultOnDisk = new File(langFolder, DEFAULT_CODE + ".yml");
    if (!defaultOnDisk.exists()) {
      plugin.saveResource("lang/" + DEFAULT_CODE + ".yml", false);
    }

    String code = requestedCode;
    if (code == null || !SAFE_CODE.matcher(code).matches()) {
      logger.warning("Invalid \"language\" value in config.yml - falling back to " + DEFAULT_CODE + ".");
      code = DEFAULT_CODE;
    }

    File requested = new File(langFolder, code + ".yml");
    if (!requested.isFile()) {
      if (!code.equals(DEFAULT_CODE)) {
        logger.warning("Language file \"" + code + ".yml\" not found in lang/ - falling back to " + DEFAULT_CODE + ".");
      }
      requested = new File(langFolder, DEFAULT_CODE + ".yml");
    }

    if (requested.isFile()) {
      return new Lang(YamlConfiguration.loadConfiguration(requested));
    }

    logger.warning("Could not read a language file from disk - loading bundled defaults directly.");
    InputStream bundled = plugin.getResource("lang/" + DEFAULT_CODE + ".yml");
    if (bundled == null) {
      return new Lang(new YamlConfiguration());
    }
    return new Lang(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
  }

  public String prefix() {
    return color(messages.getString("prefix", "&8[&dLive Chat&8] &r"));
  }

  /** Resolves a dotted key with alternating placeholder/value pairs, e.g. get("auth.success", "url", value). Unresolved keys return the key itself so a missing translation is visible, not silently blank. */
  public String get(String key, String... placeholders) {
    String template = messages.getString(key);
    if (template == null) {
      return key;
    }
    for (int i = 0; i + 1 < placeholders.length; i += 2) {
      template = template.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
    }
    return color(template);
  }

  private static String color(String text) {
    return ChatColor.translateAlternateColorCodes('&', text);
  }
}
