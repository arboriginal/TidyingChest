package me.arboriginal.TidyingChest;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import com.google.common.collect.ImmutableMap;
import me.arboriginal.TidyingChest.TCManager.TidyingChest;
import me.arboriginal.TidyingChest.TCManager.Types;

class TCListener implements Listener {
    private TCPlugin tc;

    // ----------------------------------------------------------------------------------------------
    // Constructor methods
    // ----------------------------------------------------------------------------------------------

    TCListener(TCPlugin plugin) {
        tc = plugin;
    }

    // ----------------------------------------------------------------------------------------------
    // Listener methods
    // ----------------------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    private void onBlockBreak(BlockBreakEvent event) {
        TidyingChest chest = null;

        if (event.getBlock().getType() == Material.CHEST)
            chest = tc.chests.get((Chest) event.getBlock().getState());
        else if (event.getBlock().getBlockData() instanceof WallSign)
            chest = tc.chests.get((Sign) event.getBlock().getState());

        if (chest != null) tc.chests.del(chest);
    }

    @EventHandler(ignoreCancelled = true)
    private void onInventoryClose(InventoryCloseEvent event) {
        if (tc.chests.transfer(event.getInventory())) return;
        event.getPlayer().sendMessage(tc.prepareText("err_transfer", "type", tc.config.getString("signs.ROOT.name")));
    }

    @EventHandler(ignoreCancelled = true)
    private void onInventoryMoveItem(InventoryMoveItemEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                tc.chests.transfer(event.getDestination().getHolder().getInventory());
            }
        }.runTaskLater(tc, 1);
    }

    @EventHandler(ignoreCancelled = true)
    private void onPlayerInteract(PlayerInteractEvent event) { // @formatter:off
        if (event.useInteractedBlock() != Result.ALLOW   ||   event.getHand() == EquipmentSlot.OFF_HAND
        ||  event.useItemInHand()      != Result.DEFAULT || !(event.getClickedBlock().getBlockData() instanceof WallSign)
        || !event.getPlayer().hasPermission("tc.create")) return;
        // @formatter:on
        TidyingChest chest = tc.chests.get((Sign) event.getClickedBlock().getState());
        if (chest == null || chest.type != Types.TARGET || chest.item != null) return;

        Player player = event.getPlayer();
        UUID   uid    = player.getUniqueId();

        if (!chest.uid.equals(uid)) return;

        Material item = player.getInventory().getItemInMainHand().getType();

        if (tc.db.count(ImmutableMap.of("owner", uid.toString(), "material", item.toString())) > 0)
            player.sendMessage(tc.prepareText("already_exists", "type",
                    tc.config.getString("signs." + chest.type + ".name")));
        else tc.chests.refresh(chest, item);
    }

    @EventHandler(ignoreCancelled = true)
    private void onSignChange(SignChangeEvent ev) {
        if (!(ev.getBlock().getBlockData() instanceof WallSign) || !ev.getPlayer().hasPermission("tc.create")) return;

        Player player = ev.getPlayer();

        new BukkitRunnable() {
            public void run() {
                Sign         sign  = (Sign) ev.getBlock().getState();
                TidyingChest chest = tc.chests.get(sign, player.getUniqueId());
                if (chest == null) return;

                String error = null;
                Sign   oSign = tc.chests.signConnected(chest.chest, sign);

                if (oSign != null) {
                    chest.type = tc.chests.signType(oSign);
                    error      = "already_connected";
                }
                else if (tc.chests.limitReached(player, chest.type)) error = "limit_reached";
                else if (!player.hasPermission("tc.create." + chest.type + ".world.*") // @formatter:off
                      && !player.hasPermission("tc.create." + chest.type + ".world." + player.getWorld().getName()))
                    error = "world_not_allowed";
                // @formatter:on
                if (error == null) tc.chests.refresh(chest);
                else {
                    sign.getBlock().breakNaturally();
                    player.sendMessage(tc.prepareText(error, "type",
                            tc.config.getString("signs." + chest.type + ".name")));
                }
            }
        }.runTaskLater(tc, 1);
    }
}
