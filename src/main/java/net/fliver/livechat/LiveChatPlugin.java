package net.fliver.livechat;

import java.util.logging.Level;
import net.fliver.livechat.api.FliverLiveChatApi;
import net.fliver.livechat.command.LiveChatCommand;
import net.fliver.livechat.config.PluginConfig;
import net.fliver.livechat.lang.Lang;
import net.fliver.livechat.minecraft.ChatListener;
import net.fliver.livechat.relay.InboundPoller;
import net.fliver.livechat.state.PairingState;
import net.fliver.livechat.update.UpdateChecker;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LiveChatPlugin extends JavaPlugin {

  private PluginConfig config;
  private Lang lang;
  private FliverLiveChatApi api;
  private PairingState state;
  private InboundPoller poller;
  private UpdateChecker updateChecker;

  @Override
  public void onEnable() {
    config = PluginConfig.load(this);
    lang = Lang.load(this, config.language());
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

    poller = new InboundPoller(this, api, state, config.discordMessageFormat());

    LiveChatCommand command = new LiveChatCommand(this);
    PluginCommand pluginCommand = getCommand("live-chat");
    if (pluginCommand != null) {
      pluginCommand.setExecutor(command);
      pluginCommand.setTabCompleter(command);
    } else {
      getLogger().severe("The live-chat command isn't registered - check plugin.yml.");
    }

    getServer().getPluginManager().registerEvents(new ChatListener(this, api, state), this);

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
    config = PluginConfig.load(this);
    lang = Lang.load(this, config.language());
    api = new FliverLiveChatApi();

    if (poller != null) {
      poller.stop();
    }
    poller = new InboundPoller(this, api, state, config.discordMessageFormat());
    syncPollerState();
    startUpdateChecker();
  }

  private void startUpdateChecker() {
    if (updateChecker != null) {
      updateChecker.stop();
    }
    updateChecker =
        new UpdateChecker(this, config.updateCheckEnabled(), config.updateCheckIntervalHours());
    updateChecker.start();
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

  public PluginConfig config() {
    return config;
  }

  public Lang lang() {
    return lang;
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
