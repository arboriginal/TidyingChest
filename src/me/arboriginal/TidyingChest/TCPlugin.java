package me.arboriginal.TidyingChest;

import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.UnmodifiableIterator;
import me.arboriginal.TidyingChest.TCManager.TidyingChest;
import me.arboriginal.TidyingChest.TCManager.Types;

public class TCPlugin extends JavaPlugin implements Listener {
  protected FileConfiguration config;
  protected TCManager         chests;
  protected TCDatabase        db;

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
    db.close();
  }

  @Override
  public void onEnable() {
    super.onEnable();

    chests = new TCManager(this);
    String er = "This plugin only works on Spigot servers!";

    try {
      getServer().spigot();
      reloadConfig();
      er = prepareText("err_db");
      db = new TCDatabase(this);
    }
    catch (Exception e) {
      getServer().getPluginManager().disablePlugin(this);
      getLogger().severe(er);
      // No need to go on, it will not work
      return;
    }

    chests.removeOrphans();
    getServer().getPluginManager().registerEvents(this, this);
  }

  @Override
  public void reloadConfig() {
    super.reloadConfig();
    saveDefaultConfig();
    config = getConfig();
    config.options().copyDefaults(true);
    chests.signRowsInit();
    saveConfig();
  }

  // ----------------------------------------------------------------------------------------------
  // Listener methods
  // ----------------------------------------------------------------------------------------------

  @EventHandler
  public void onBlockBreak(BlockBreakEvent event) {
    if (event.isCancelled()) return;

    TidyingChest chest = null;

    if (event.getBlock().getType().equals(Material.CHEST))
      chest = chests.get((Chest) event.getBlock().getState());
    else if (event.getBlock().getType().equals(Material.WALL_SIGN))
      chest = chests.get((Sign) event.getBlock().getState());

    if (chest != null) chests.del(chest);
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent event) {
    if (chests.transfer(event.getInventory())) return;
    event.getPlayer().sendMessage(prepareText("err_transfer", "type", config.getString("signs.ROOT.name")));
  }

  @EventHandler
  public void onInventoryMoveItem(InventoryMoveItemEvent event) {
    if (event.isCancelled()) return;
    new BukkitRunnable() {
      @Override
      public void run() {
        chests.transfer(event.getDestination().getHolder().getInventory());
      }
    }.runTaskLater(this, 1);
  }

  @EventHandler
  public void onPlayerInteract(PlayerInteractEvent event) { // @formatter:off
    if (    event.isCancelled() || event.getHand() == EquipmentSlot.OFF_HAND
        || !event.getClickedBlock().getType().equals(Material.WALL_SIGN)
        || !event.getPlayer().hasPermission("tc.create")) return;

    TidyingChest chest  = chests.get((Sign) event.getClickedBlock().getState());
    Player       player = event.getPlayer();
    UUID         uid    = player.getUniqueId();

    if (   chest      == null || !chest.type.equals(Types.TARGET)
        || chest.item != null || !chest.uid.equals(uid)) return;

    Material item = player.getInventory().getItemInMainHand().getType();
    
    if (db.count(ImmutableMap.of("owner", uid.toString(), "material", item.toString())) > 0)
      player.sendMessage(prepareText("already_exists", "type", config.getString("signs." + chest.type + ".name")));
    else
      chests.refresh(chest, item);
  } // @formatter:on

  @EventHandler
  public void onSignChange(SignChangeEvent event) { // @formatter:off
    if (    event.isCancelled()
        || !event.getBlock().getType().equals(Material.WALL_SIGN)
        || !event.getPlayer().hasPermission("tc.create")) return;

    Player player = event.getPlayer();
    UUID   uid    = player.getUniqueId();

    new BukkitRunnable() {
      public void run() {
        Sign         sign  = (Sign) event.getBlock().getState();
        TidyingChest chest = chests.get(sign, uid); if (chest == null) return;
        Sign         oSign = (chest == null)? null: chests.signConnected(chest.chest, sign);
        String       error = null;
        
        if (oSign != null) { chest.type = chests.signType(oSign); error = "already_connected"; }
        else if (chests.limitReached(player, chest.type))         error = "limit_reached";
        
        if (error == null) chests.refresh(chest); else {
          player.sendMessage(prepareText(error, "type", config.getString("signs." + chest.type + ".name")));
          sign.getBlock().breakNaturally();
        }
      }
    }.runTaskLater(this, 1);
  } // @formatter:on

  // ----------------------------------------------------------------------------------------------
  // Protected methods
  // ----------------------------------------------------------------------------------------------

  protected String cleanText(String text) {
    return ChatColor.stripColor(text.replace("&", "§")).toLowerCase();
  }

  protected String formatText(String text) {
    return ChatColor.translateAlternateColorCodes('&', text);
  }

  protected String prepareText(String key) {
    return prepareText(key, null);
  }

  protected String prepareText(String key, String placeholder, String replacement) {
    return prepareText(key, ImmutableMap.of(placeholder, replacement));
  }

  protected String prepareText(String key, ImmutableMap<String, String> placeholders) {
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
