package me.arboriginal.TidyingChest;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.UnmodifiableIterator;

public class TCPlugin extends JavaPlugin implements Listener {
    boolean           hoppers, reqSign;
    FileConfiguration config;
    TCManager         chests;
    TCDatabase        db;

    // ----------------------------------------------------------------------------------------------
    // JavaPlugin methods
    // ----------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("tc-reload")) return super.onCommand(sender, command, label, args);
        reloadConfig();
        sender.sendMessage(prepareText("reloaded"));
        return true;
    }

    @Override
    public void onDisable() {
        super.onDisable();
        if (chests != null) chests.clearPendingTargets();
        if (db != null) db.close();
    }

    @Override
    public void onEnable() {
        super.onEnable();

        String er = "This plugin only works on Spigot servers!";

        try {
            getServer().spigot();
            er = "Can't read the configuration!";
            reloadConfig();
            getLogger().info(prepareText("db_connection"));
            er = prepareText("err_db");
            db = new TCDatabase(this);
        }
        catch (Exception e) {
            getServer().getPluginManager().disablePlugin(this);
            getLogger().severe(er);
            e.printStackTrace();
            // No need to go on, it will not work
            return;
        }

        if (config.getBoolean("cleanOrphans.enabled")) chests.orphansCleanup();
        getServer().getPluginManager().registerEvents(new TCListener(this), this);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();

        saveDefaultConfig();
        config = getConfig();
        config.options().copyDefaults(true);
        saveConfig();

        reqSign = config.getBoolean("signs.required");
        hoppers = config.getBoolean("hoppers_trigger_deposit");

        if (chests == null) chests = new TCManager(this);
        else chests.loadConfiguration();
    }

    // ----------------------------------------------------------------------------------------------
    // Package methods
    // ----------------------------------------------------------------------------------------------

    String cleanText(String text) {
        return ChatColor.stripColor(text.replace("&", "§")).toLowerCase();
    }

    String formatText(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    String prepareText(String key) {
        return prepareText(key, null);
    }

    String prepareText(String key, String placeholder, String replacement) {
        return prepareText(key, ImmutableMap.of(placeholder, replacement));
    }

    String prepareText(String key, ImmutableMap<String, String> placeholders) {
        String message = config.getString("messages." + key);

        if (placeholders != null) {
            for (UnmodifiableIterator<String> i = placeholders.keySet().iterator(); i.hasNext();) {
                String placeholder = i.next();
                message = message.replace("{" + placeholder + "}", placeholders.get(placeholder));
            }
        }

        return formatText(message.replace("{prefix}", config.getString("messages.prefix")));
    }
}
