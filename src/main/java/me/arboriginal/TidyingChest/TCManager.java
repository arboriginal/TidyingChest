package me.arboriginal.TidyingChest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;

class TCManager {
    private TCPlugin tc;

    private int    LOCMAXSHOWN, TARGET_delay;
    private String LOCSEPARATOR, ROOT_name, ROOT_type, TARGET_catch, TARGET_name, TARGET_type;

    private List<String>         ROOT_alias, TARGET_alias;
    private ConfigurationSection ROOT_perms, TARGET_perms, WORLD_ALIAS;

    private HashMap<String, TidyingChest>              locationCache;
    private HashMap<String, BukkitRunnable>            pendingTargets;
    private HashMap<UUID, ArrayList<String>>           rootCache;
    private HashMap<Types, HashMap<Rows, Integer>>     signRows;
    private HashMap<UUID, HashBiMap<Material, String>> targetCache;

    // @formatter:off
    static enum Rows  { FREE, ITEM, OWNER, TYPE }
    static enum Types { ROOT, TARGET }
    // @formatter:on
    List<String> waitingForCleanup = null;

    // ----------------------------------------------------------------------------------------------
    // Private classes                                                                 @formatter:off
    // ----------------------------------------------------------------------------------------------

    private abstract class ReSyncAction implements Callable<Void> { Object result = null;
        @Override public Void call() { execute(); return null; } abstract void execute();
    }

    private class TidyingChest { UUID uid; Types type;
        TidyingChest(UUID uid, Types type) { this.uid = uid; this.type = type; }
    }

    // ----------------------------------------------------------------------------------------------
    // Constructor methods
    // ----------------------------------------------------------------------------------------------

    public TCManager(TCPlugin plugin) {
        tc = plugin;

        locationCache  = new HashMap<String, TidyingChest>();
        pendingTargets = new HashMap<String, BukkitRunnable>();
        rootCache      = new HashMap<UUID, ArrayList<String>>();
        targetCache    = new HashMap<UUID, HashBiMap<Material, String>>();

        loadConfiguration();
    }

    // ----------------------------------------------------------------------------------------------
    // Package methods > Player interactions                                            @formatter:on
    // ----------------------------------------------------------------------------------------------

    void add(Sign sign, Player player) {
        Types type = signType(sign);
        if (type == null || !allowed(sign, player, type)) return;

        if (type == Types.ROOT) create(player, sign, type, null);
        else {
            String message = tc.prepareText("TARGET_pending");
            if (!message.isEmpty()) player.sendMessage(message);

            signRefresh(sign, player, type, null);
            signBreakIncompleteLater(sign);
        }
    }

    void addTarget(Sign sign, Player player) {
        if (!signIsIncomplete(sign)) return;

        Types type = signType(sign);
        if (type != Types.TARGET || !allowed(sign, player, type)) return;

        Material item = player.getInventory().getItemInMainHand().getType();
        create(player, sign, type, (item == null) ? Material.AIR : item);
    }

    void del(Chest chest, Player player) {
        String[] sides = getChestSides(chest.getInventory());
        if (sides == null) return;

        asyncGet(sides, new ReSyncAction() {
            @Override
            void execute() {
                String location = (String) result;

                if (location != null) asyncDel(location, new ReSyncAction() {
                    @Override
                    void execute() {
                        if (!result.equals(true)) return;

                        TidyingChest tcChest = cacheDel(location);
                        if (tcChest == null) return;

                        String message = tc.prepareText(tcChest.type + "_unlinked");
                        if (!message.isEmpty()) player.sendMessage(message);
                    }
                });
            }
        });
    }

    void del(Sign sign, Player player) {
        if (signType(sign) == null) return;
        Chest chest = getConnectedChest(sign);
        if (chest != null) del(chest, player);
    }

    void transfer(Inventory inventory) {
        String[] sides = getChestSides(inventory);
        if (sides != null) asyncGet(sides, new ReSyncAction() {
            @Override
            void execute() {
                if (result == null) return;
                TidyingChest tcChest = locationCache.get(result);
                if (tcChest != null && tcChest.type == Types.ROOT) transfer(inventory, tcChest.uid);
            }
        });
    }

    // ----------------------------------------------------------------------------------------------
    // Package methods > Global management (called by the plugin)
    // ----------------------------------------------------------------------------------------------

    void clearPendingTargets() {
        Iterator<String> it = pendingTargets.keySet().iterator();

        while (it.hasNext()) {
            BukkitRunnable task = pendingTargets.get(it.next());
            if (task != null && !task.isCancelled()) task.run();
            it.remove();
        }
    }

    void loadConfiguration() {
        ROOT_alias   = tc.config.getStringList("signs.ROOT.aliases");
        ROOT_name    = tc.config.getString("signs.ROOT.name");
        ROOT_perms   = tc.config.getConfigurationSection("signs.ROOT.limits");
        ROOT_type    = tc.config.getString("signs.ROOT.type");
        TARGET_alias = tc.config.getStringList("signs.TARGET.aliases");
        TARGET_name  = tc.config.getString("signs.TARGET.name");
        TARGET_perms = tc.config.getConfigurationSection("signs.TARGET.limits");
        TARGET_type  = tc.config.getString("signs.TARGET.type");
        TARGET_catch = tc.config.getString("signs.TARGET.catchall");
        TARGET_delay = tc.config.getInt("signs.TARGET.delay") * 20;
        LOCMAXSHOWN  = tc.config.getInt("max_locations_shown");
        LOCSEPARATOR = tc.formatText(tc.config.getString("messages.exists_location_chests_separator"));
        WORLD_ALIAS  = tc.config.getConfigurationSection("world_aliases");

        signRowsPopulate();
    }

    void orphansCleanup() {
        waitingForCleanup = new ArrayList<String>();
        List<String> plugins = tc.config.getStringList("world_plugins");

        if (plugins != null && !plugins.isEmpty()) for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            String name = plugin.getName();

            if (plugins.contains(name) && !Bukkit.getPluginManager().isPluginEnabled(plugin))
                waitingForCleanup.add(name);
        }

        removeOrphans();
    }

    void removeOrphans() {
        if (waitingForCleanup == null) return;

        if (waitingForCleanup.isEmpty()) {
            waitingForCleanup = null;

            removeOrphans(tc.config.getInt("cleanOrphans.maxRows"), tc.config.getBoolean("cleanOrphans.checkTypes"),
                    tc.config.getBoolean("cleanOrphans.delete"), tc.config.getBoolean("cleanOrphans.verbose"));
        }
        else tc.getLogger().info(tc.prepareText("orphan_wait", "plugins", String.join(", ", waitingForCleanup)));
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods > Global management (helpers for methods called by the plugin)
    // ----------------------------------------------------------------------------------------------

    private boolean isOrphan(String location, String type) {
        Chest chest = getChestAt(location);
        if (chest == null) return true;

        Sign sign = getConnectedSign(chest, null);
        if (sign == null && tc.reqSign) return true;
        if (type == null) return false;

        try {
            if (signType(sign, (Types.valueOf(type) == Types.ROOT))) return false;
            tc.getLogger().warning(tc.prepareText("orphan_wrong_type", "key", location));
        }
        catch (Exception e) {
            tc.getLogger().warning(tc.prepareText("orphan_unknown_type", "key", location));
        }

        return true;
    }

    private void removeOrphans(int maxRows, boolean checkTypes, boolean delete, boolean verbose) {
        tc.getLogger().info(tc.prepareText("orphan_search"));
        checkTypes &= tc.reqSign;

        int total = 0, removed = 0;

        for (int pass = 0;; pass++) {
            ArrayList<ImmutableMap<String, String>> rows = tc.db.getAll(pass * maxRows, maxRows);

            int count = rows.size();
            if (count == 0) break;

            if (verbose) tc.getLogger().info(
                    tc.prepareText("orphan_pass", ImmutableMap.of("pass", (pass + 1) + "", "maxRows", maxRows + "")));

            ArrayList<String> orphans = new ArrayList<String>();

            for (ImmutableMap<String, String> datas : rows) {
                String location = datas.get("location");
                if (isOrphan(location, checkTypes ? datas.get("type") : null)) orphans.add(location);
            }

            total += count;

            if (orphans.isEmpty()) {
                if (verbose) tc.getLogger().info(tc.prepareText("orphan_finish", "number", count + ""));
            }
            else if (!delete) {
                removed += orphans.size();
                tc.getLogger().warning(tc.prepareText("orphan_warning", "number", orphans.size() + "")
                        + "\n\t- " + String.join("\n\t- ", orphans));
            }
            else if (tc.db.del(orphans, "orphans")) {
                removed += orphans.size();
                tc.getLogger().warning(tc.prepareText("orphan_removed", "number", orphans.size() + ""));
            }
        }

        if (verbose || removed > 0) tc.getLogger().info(delete
                ? tc.prepareText("orphan_complete", ImmutableMap.of("verified", total + "", "removed", removed + ""))
                : tc.prepareText("orphans_not_del", ImmutableMap.of("verified", total + "", "orphans", removed + "")));
    }

    private HashMap<Rows, Integer> signRowsImport(List<String> list) {
        HashMap<Rows, Integer> map = new HashMap<Rows, Integer>();

        try {
            for (int i = 0; i < list.size(); i++) map.put(Rows.valueOf(list.get(i)), i);
        }
        catch (Exception e) {}

        return map;
    }

    private void signRowsPopulate() {
        signRows = new HashMap<Types, HashMap<Rows, Integer>>();
        signRowsPopulate(Types.ROOT);
        signRowsPopulate(Types.TARGET);
    }

    private void signRowsPopulate(Types type) {
        String key = "signs." + type + ".rows";
        signRows.put(type, signRowsImport(tc.config.getStringList(key)));

        if (signRows.get(type).size() != Rows.values().length) {
            tc.getLogger().warning(tc.prepareText("err_rows", ImmutableMap.of("type", type.toString())));
            signRows.put(type, signRowsImport(tc.config.getDefaults().getStringList(key)));
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods > Location from / to String (format used in cache and database)
    // ----------------------------------------------------------------------------------------------

    private ImmutableMap<String, String> locationDecode(String location) {
        Matcher m = Pattern.compile("^(.*):([-\\d]+)/([-\\d]+)/([-\\d]+)$").matcher(location);
        return (m.find() && m.groupCount() != 4) ? null
                : ImmutableMap.of("world", m.group(1), "x", m.group(2), "y", m.group(3), "z", m.group(4));
    }

    private String locationEncode(Location loc) {
        if (loc == null) return null;
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "/" + loc.getBlockY() + "/" + loc.getBlockZ();
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods > Cache helpers
    // ----------------------------------------------------------------------------------------------

    private TidyingChest cacheDel(String location) {
        TidyingChest tcChest = locationCache.remove(location);
        if (tcChest == null) return null;

        if (tcChest.type == Types.TARGET) {
            Material item = targetCache.get(tcChest.uid).inverse().get(location);
            if (item != null) targetCache.get(tcChest.uid).remove(item);
        }
        else rootCache.get(tcChest.uid).remove(location);

        return tcChest;
    }

    private void cacheSet(UUID owner, ArrayList<ImmutableMap<String, String>> list) {
        ArrayList<String>           roots   = new ArrayList<String>();
        HashBiMap<Material, String> targets = HashBiMap.create();

        list.forEach(row -> {
            try {
                Types    type = Types.valueOf(row.get("type"));
                boolean  root = (type == Types.ROOT);
                Material item = root ? null : Material.valueOf(row.get("material"));
                String   loc  = row.get("location");
                // @formatter:off
                if (root) roots.add(loc); else targets.put(item, loc);
                // @formatter:on
                locationCache.put(loc, new TidyingChest(owner, type));
            }
            catch (Exception e) {
                tc.getLogger().warning(tc.prepareText("err_row", "key", row + ""));
            }
        });

        rootCache.put(owner, roots);
        targetCache.put(owner, targets);
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods > Player permissions
    // ----------------------------------------------------------------------------------------------

    private boolean allowed(Sign sign, Player player, Types type) {
        String error = null;
        // @formatter:off
             if (!player.hasPermission("tc.create")) error = "no_permission";
        else if (!player.hasPermission("tc.create." + type + ".world.*") 
              && !player.hasPermission("tc.create." + type + ".world." + sign.getWorld().getName()))
            error = "world_not_allowed"; // @formatter:on
        else {
            Chest chest = getConnectedChest(sign);
            if (chest == null) error = "not_connected";
            else {
                Sign already = getConnectedSign(chest, sign);
                if (already != null) {
                    error = "already_connected";
                    type  = signType(already);
                }
            }
        }

        if (error == null) return true;

        cancelCreation(sign, player, type, error);
        return false;
    }

    private ArrayList<String> limitReached(Player player, Types type) {
        if (player.hasPermission("tc.create." + type + ".unlimited")) return null;

        ArrayList<String> locations = new ArrayList<String>();

        if (type == Types.ROOT) locations = rootCache.get(player.getUniqueId());
        else locations.addAll(targetCache.get(player.getUniqueId()).values());

        int count = locations.size();
        if (count == 0) return null;

        ConfigurationSection perms = (type == Types.ROOT) ? ROOT_perms : TARGET_perms;

        for (String key : perms.getKeys(false))
            if (player.hasPermission("tc.create." + type + "." + key) && perms.getInt(key) > count) return null;

        return locations;
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods > Sign helpers
    // ----------------------------------------------------------------------------------------------

    private void signBreakIncompleteLater(Sign sign) {
        String key = signIncompleteKey(sign);
        if (key == null) return;

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (isCancelled()) return;
                pendingTargets.remove(key);
                sign.getBlock().breakNaturally();
                cancel();
            }
        };

        pendingTargets.put(key, task);
        task.runTaskLater(tc, TARGET_delay);
    }

    private String signIncompleteKey(Sign sign) {
        return locationEncode(sign.getLocation());
    }

    private boolean signIsIncomplete(Sign sign) {
        return pendingTargets.containsKey(signIncompleteKey(sign));
    }

    private boolean signRefresh(Sign sign, Player player, Types type, Material item) {
        signRow(sign, type, Rows.TYPE, "type");
        signRow(sign, type, Rows.OWNER, "owner", "{owner}", player.getName());

        if (type == Types.TARGET) {
            if (item == null) signRow(sign, type, Rows.ITEM, "not_set");
            else signRow(sign, type, Rows.ITEM, "item", "{item}", (item == Material.AIR) ? TARGET_catch : item.name());
        }

        return sign.update();
    }

    private void signRow(Sign sign, Types type, Rows row, String key) {
        signRow(sign, type, row, key, null, null);
    }

    private void signRow(Sign sign, Types type, Rows row, String key, String from, String replace) {
        String text = tc.config.getString("signs." + type + "." + key);
        if (from != null) text = text.replace(from, replace);
        sign.setLine(signRows.get(type).get(row), tc.formatText(text));
    }

    private Types signType(Sign sign) {
        if (!(sign.getBlockData() instanceof WallSign)) return null;
        if (signType(sign, false)) return Types.TARGET;
        if (signType(sign, true)) return Types.ROOT;
        return null;
    }

    private boolean signType(Sign sign, boolean root) {
        String typeRow = tc.cleanText(sign.getLine(signRows.get(root ? Types.ROOT : Types.TARGET).get(Rows.TYPE)));
        if (typeRow.isEmpty()) return false;
        if (typeRow.equals(tc.cleanText(root ? ROOT_type : TARGET_type))) return true;
        for (String alias : root ? ROOT_alias : TARGET_alias) if (typeRow.equals(tc.cleanText(alias))) return true;
        return false;
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods > Block getters
    // ----------------------------------------------------------------------------------------------

    private String[] getChestSides(Inventory inventory) {
        if (inventory.getType() != InventoryType.CHEST) return null;

        InventoryHolder holder = inventory.getHolder();
        if (holder == null) return null;

        if (holder instanceof DoubleChest) return new String[] {
                locationEncode(((Chest) ((DoubleChest) holder).getLeftSide()).getBlock().getLocation()),
                locationEncode(((Chest) ((DoubleChest) holder).getRightSide()).getBlock().getLocation())
        };

        String side = locationEncode(inventory.getLocation());
        return (side == null) ? null : new String[] { side };
    }

    private Chest getChestAt(String location) {
        ImmutableMap<String, String> coords = locationDecode(location);
        if (coords == null) return null;

        try {
            Block block = tc.getServer().getWorld(coords.get("world")).getBlockAt(
                    Integer.parseInt(coords.get("x")),
                    Integer.parseInt(coords.get("y")),
                    Integer.parseInt(coords.get("z")));

            if (block.getType() == Material.CHEST) return (Chest) block.getState();
        }
        catch (Exception e) {}

        return null;
    }

    private Chest getConnectedChest(Sign sign) {
        Block block = sign.getBlock().getRelative(((WallSign) sign.getBlockData()).getFacing().getOppositeFace());
        return (block != null && block.getType() == Material.CHEST) ? (Chest) block.getState() : null;
    }

    private Sign getConnectedSign(Block origin, Sign sign) {
        for (BlockFace face : Arrays.asList(BlockFace.NORTH, BlockFace.WEST, BlockFace.SOUTH, BlockFace.EAST)) {
            Block block = origin.getRelative(face);
            if (!(block.getBlockData() instanceof WallSign)) continue;

            Sign thisSign = (Sign) block.getState();
            if ((sign != null && sign.equals(thisSign)) || signType(thisSign) == null) continue;

            Chest chest = getConnectedChest(thisSign);
            if (chest != null && origin.equals(chest.getBlock())) return thisSign;
        }

        return null;
    }

    private Sign getConnectedSign(Chest chest, Sign sign) {
        if (!(chest.getInventory() instanceof DoubleChestInventory)) return getConnectedSign(chest.getBlock(), sign);
        DoubleChest dc   = (DoubleChest) chest.getInventory().getHolder();
        Sign        left = getConnectedSign(((Chest) dc.getLeftSide()).getBlock(), sign);
        return (left == null) ? getConnectedSign(((Chest) dc.getRightSide()).getBlock(), sign) : left;
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods > Player interactions helpers
    // ----------------------------------------------------------------------------------------------

    private boolean cancelCreation(Sign sign, Player player, Types type, String error) {
        return cancelCreation(sign, player, type, error, null);
    }

    private boolean cancelCreation(Sign sign, Player player, Types type, String error, ArrayList<String> locations) {
        player.sendMessage(tc.prepareText(error, "type", (type == Types.ROOT) ? ROOT_name : TARGET_name));

        if (locations != null && player.hasPermission("tc.create.show_loc")) {
            locations = formatLocations(locations);

            if (locations.size() == 1)
                player.sendMessage(tc.prepareText("exists_location_chest", "location", locations.get(0)));
            else if (locations.size() > 0)
                player.sendMessage(tc.prepareText("exists_location_chests", "locations",
                        LOCSEPARATOR + String.join(LOCSEPARATOR, locations)));
        }

        return sign.getBlock().breakNaturally();
    }

    private ArrayList<String> formatLocations(ArrayList<String> locations) {
        ArrayList<String> formatted = new ArrayList<String>();

        int i = 0, max = Math.min(locations.size(), LOCMAXSHOWN);
        while (i < max) { // @formatter:off
            ImmutableMap<String, String> parts = locationDecode(locations.get(i++));
            String location = tc.prepareText("exists_location_format", parts), world = parts.get("world");
            if (WORLD_ALIAS != null && WORLD_ALIAS.contains(world))
                location = location.replace(world, WORLD_ALIAS.getString(world));
            formatted.add(location); // @formatter:on
        }

        return formatted;
    }

    private void confirmCreation(Player player, Types type) {
        String message = tc.prepareText(type + "_linked");
        if (!message.isEmpty()) player.sendMessage(message);

        if (tc.reqSign) return;

        message = tc.prepareText("can_be_removed");
        if (!message.isEmpty()) player.sendMessage(message);
    }

    private void create(Player player, Sign sign, Types type, Material item) {
        BukkitRunnable task = pendingTargets.remove(signIncompleteKey(sign));
        if (task != null && !task.isCancelled()) task.cancel();

        UUID uid = player.getUniqueId();
        asyncGet(uid, new ReSyncAction() {
            @Override
            void execute() {
                String error = null, loc = null;

                ArrayList<String> locations = new ArrayList<String>();

                if (!result.equals(true)) error = "db_error";
                else {
                    Chest chest = getConnectedChest(sign);
                    if (chest == null) error = "chest_not_found";
                    else {
                        loc = locationEncode(chest.getBlock().getLocation());
                        if (rootCache.get(uid).contains(loc) || targetCache.get(uid).containsValue(loc))
                            error = "already_connected";
                    }
                }

                if (error == null && type == Types.TARGET) {
                    String exists = targetCache.get(uid).get(item);
                    if (exists != null) {
                        error = "already_exists";
                        locations.add(exists);
                    }
                }

                if (error == null) {
                    locations = limitReached(player, type);
                    if (locations != null) error = "limit_reached";
                }

                if (error == null) store(player, sign, loc, type, item);
                else cancelCreation(sign, player, type, error, locations);
            }
        });
    }

    private void store(Player player, Sign sign, String location, Types type, Material item) {
        UUID uid = player.getUniqueId();

        asyncSet(location, uid, type, item, new ReSyncAction() {
            @Override
            void execute() {
                if (result.equals(true)) {
                    if (type == Types.ROOT) rootCache.get(uid).add(location);
                    else targetCache.get(uid).put(item, location);
                    locationCache.put(location, new TidyingChest(uid, type));
                    confirmCreation(player, type);
                    signRefresh(sign, player, type, item);
                }
                else cancelCreation(sign, player, type, "db_error");
            }
        });
    }

    private boolean transfer(Inventory inventory, UUID uid) {
        HashBiMap<Material, String> targets = targetCache.get(uid);
        if (targets == null) return false;
        if (targets.isEmpty()) return true;

        HashMap<Material, Chest> chests = new HashMap<Material, Chest>();

        ItemStack[] stacks = inventory.getContents();
        for (int slot = 0; slot < stacks.length; slot++) {
            ItemStack stack = stacks[slot];
            if (stack == null) continue;

            Material item = stack.getType();
            if (!targets.containsKey(item)) item = Material.AIR;

            if (!chests.containsKey(item)) {
                String location = targets.get(item);
                chests.put(item, (location == null) ? null : getChestAt(location));
            }

            Chest chest = chests.get(item);
            if (chest == null) continue;

            inventory.setItem(slot, chest.getInventory().addItem(inventory.getItem(slot)).get(0));
        }

        return true;
    }

    // ----------------------------------------------------------------------------------------------
    // Private Asynchronous methods /!\ Don't call any Bukkit API methods in them!
    // ----------------------------------------------------------------------------------------------

    private void async(ReSyncAction action, Supplier<?> supplier) {
        new BukkitRunnable() {
            @Override
            public void run() {
                action.result = supplier.get();
                Bukkit.getScheduler().callSyncMethod(tc, action);
            }
        }.runTaskAsynchronously(tc);
    }

    private void asyncDel(String location, ReSyncAction action) {
        async(action, () -> {
            return tc.db.del(location);
        });
    }

    private void asyncGet(String[] locations, ReSyncAction action) {
        async(action, () -> {
            for (String location : locations) if (locationCache.get(location) != null) return location;

            ImmutableMap<String, String> connected = tc.db.getOwner(locations);
            if (connected == null) return null;

            String owner = connected.get("owner"), location = connected.get("location");

            cacheSet(UUID.fromString(owner), tc.db.getByOwner(owner));
            return locationCache.containsKey(location) ? location : null;
        });
    }

    private void asyncGet(UUID owner, ReSyncAction action) {
        async(action, () -> {
            if (targetCache.containsKey(owner)) return true;

            ArrayList<ImmutableMap<String, String>> datas = tc.db.getByOwner(owner.toString());
            if (datas == null) return false;

            cacheSet(owner, datas);
            return true;
        });
    }

    private void asyncSet(String loc, UUID owner, Types type, Material item, ReSyncAction action) {
        async(action, () -> {
            return tc.db.set(loc, (item == null) ? "" : item.toString(), owner.toString(), type.toString());
        });
    }
}
