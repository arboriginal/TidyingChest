package me.arboriginal.TidyingChest;

import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;

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
        if (event.getBlock().getType() == Material.CHEST)
            tc.chests.del((Chest) event.getBlock().getState(), event.getPlayer());
        else if (tc.reqSign && event.getBlock().getBlockData() instanceof WallSign)
            tc.chests.del((Sign) event.getBlock().getState(), event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    private void onInventoryClose(InventoryCloseEvent event) {
        tc.chests.transfer(event.getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    private void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (tc.hoppers) tc.chests.transfer(event.getDestination());
    }

    @EventHandler(ignoreCancelled = true)
    private void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock().getBlockData() instanceof WallSign // @formatter:off
         && event.useInteractedBlock() == Result.ALLOW && event.getHand() != EquipmentSlot.OFF_HAND
         && event.useItemInHand()      == Result.DEFAULT) // @formatter:on
            tc.chests.addTarget((Sign) event.getClickedBlock().getState(), event.getPlayer());
    }

    @EventHandler
    private void onPluginEnable(PluginEnableEvent event) {
        if (tc.chests.waitingForCleanup != null && tc.chests.waitingForCleanup.remove(event.getPlugin().getName()))
            tc.chests.removeOrphans();
    }

    @EventHandler(ignoreCancelled = true)
    private void onSignChange(SignChangeEvent event) {
        if (event.getBlock().getBlockData() instanceof WallSign) new BukkitRunnable() {
            public void run() {
                tc.chests.add((Sign) event.getBlock().getState(), event.getPlayer());
            } // We need to run it next tick to be able to manipulate the sign
        }.runTask(tc);
    }
}
