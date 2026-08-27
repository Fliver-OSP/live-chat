package net.fliver.livechat;

import java.util.logging.Level;
import net.fliver.livechat.api.FliverLiveChatApi;
import net.fliver.livechat.command.LiveChatCommand;
import net.fliver.livechat.minecraft.ChatListener;
import net.fliver.livechat.minecraft.ServerEventListener;
import net.fliver.livechat.relay.InboundPoller;
import net.fliver.livechat.state.PairingState;
import net.fliver.livechat.update.UpdateChecker;
import net.fliver.trio.Trio;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LiveChatPlugin extends JavaPlugin {

  private Trio trio;
  private FliverLiveChatApi api;
  private PairingState state;
  private InboundPoller poller;
  private UpdateChecker updateChecker;

  @Override
  public void onEnable() {
    trio = Trio.create(this);
    trio.configs().saveDefaults().reload();
    trio.loadLang(languageCode());
    api = new FliverLiveChatApi();

    state = new PairingState(getDataFolder());
    try {
      state.load();
    } catch (Exception e) {
      getLogger()
          .log(
              Level.SEVERE,
              "Could not read pairing.dat (corrupted, or written by a different key) - starting unlinked. Run /live-chat auth again.",
              e);
      state.reset();
    }

    poller = new InboundPoller(this, api, state, discordMessageFormat());

    LiveChatCommand command = new LiveChatCommand(this);
    PluginCommand pluginCommand = getCommand("live-chat");
    if (pluginCommand != null) {
      pluginCommand.setExecutor(command);
      pluginCommand.setTabCompleter(command);
    } else {
      getLogger().severe("The live-chat command isn't registered - check plugin.yml.");
    }

    getServer().getPluginManager().registerEvents(new ChatListener(this, api, state), this);
    getServer().getPluginManager().registerEvents(new ServerEventListener(this, api, state), this);

    syncPollerState();
    startUpdateChecker();
  }

  @Override
  public void onDisable() {
    if (updateChecker != null) {
      updateChecker.stop();
    }
    if (poller != null) {
      poller.stop();
    }
  }

  /** /live-chat reload - config.yml and lang/*.yml only; does not touch pairing state. */
  public void reloadConfigAndLang() {
    trio.configs().reload();
    trio.loadLang(languageCode());
    api = new FliverLiveChatApi();

    if (poller != null) {
      poller.stop();
    }
    poller = new InboundPoller(this, api, state, discordMessageFormat());
    syncPollerState();
    startUpdateChecker();
  }

  private void startUpdateChecker() {
    if (updateChecker == null) {
      updateChecker = new UpdateChecker(this);
      getServer().getPluginManager().registerEvents(updateChecker, this);
    }
    updateChecker.restart(updateCheckEnabled(), updateCheckIntervalHours());
  }

  /** Starts/stops the Discord -> Minecraft poller based on current pairing state - called after auth, add, remove and reload. */
  public void syncPollerState() {
    boolean shouldRun = state.isLinked() && !state.channels().isEmpty();
    if (shouldRun && !poller.isRunning()) {
      poller.start();
    } else if (!shouldRun && poller.isRunning()) {
      poller.stop();
    }
  }

  private String languageCode() {
    String lang = trio.configs().string("language", "en_US");
    if (lang == null || lang.trim().isEmpty()) {
      return "en_US";
    }
    return lang.trim();
  }

  public int pollIntervalSeconds() {
    return Math.max(1, trio.configs().integer("poll-interval-seconds", 3));
  }

  public String discordMessageFormat() {
    return trio.configs()
        .string(
            "discord-message-format",
            "<gray>[Discord]</gray> <white><player></white><gray>: </gray><white><message></white>");
  }

  public boolean updateCheckEnabled() {
    return trio.configs().bool("update-check.enabled", true);
  }

  public int updateCheckIntervalHours() {
    return Math.max(1, trio.configs().integer("update-check.interval-hours", 6));
  }

  public boolean eventEnabled(String kind) {
    return trio.configs().bool("events." + kind + ".enabled", false);
  }

  public String eventFormat(String kind) {
    return trio.configs().string("events." + kind + ".discord-format", defaultEventFormat(kind));
  }

  /** Persists events.{kind}.enabled to config.yml. */
  public void setEventEnabled(String kind, boolean enabled) {
    getConfig().set("events." + kind + ".enabled", enabled);
    saveConfig();
  }

  private static String defaultEventFormat(String kind) {
    return switch (kind) {
      case "join" -> "**%player%** joined the game";
      case "leave" -> "**%player%** left the game";
      case "death" -> "%death_message%";
      case "advancement" -> "**%player%** made the advancement **%advancement%**";
      case "gamemode" -> "**%player%** changed gamemode to **%gamemode%**";
      case "kick" -> "**%player%** was kicked: %reason%";
      default -> "%player%";
    };
  }

  public Trio trio() {
    return trio;
  }

  public FliverLiveChatApi api() {
    return api;
  }

  public PairingState state() {
    return state;
  }

  public InboundPoller poller() {
    return poller;
  }
}
