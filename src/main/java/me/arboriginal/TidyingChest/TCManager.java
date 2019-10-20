package me.arboriginal.TidyingChest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import com.google.common.collect.ImmutableMap;

class TCManager {
    // @formatter:off
    private TCPlugin tc;
    private HashMap<Types, HashMap<Rows, Integer>> signRows;

    static enum Rows  { FREE, ITEM, OWNER, TYPE }
    static enum Types { ROOT, TARGET }
    // @formatter:on

    // ----------------------------------------------------------------------------------------------
    // Constructor methods
    // ----------------------------------------------------------------------------------------------

    public TCManager(TCPlugin plugin) {
        tc = plugin;
    }

    // ----------------------------------------------------------------------------------------------
    // Custom classes
    // ----------------------------------------------------------------------------------------------

    class TidyingChest { // @formatter:off
                     UUID uid;        Chest chest;       Sign  sign;       Types type;    Material item;
        TidyingChest(UUID uid,        Chest chest,        Sign sign,       Types type,    Material item) {
               this.uid = uid; this.chest = chest; this.sign = sign; this.type = type; this.item = item; }
    } // @formatter:on

    // ----------------------------------------------------------------------------------------------
    // Package methods
    // ----------------------------------------------------------------------------------------------

    boolean del(TidyingChest chest) {
        return tc.db.del(locationEncode(chest.chest.getBlock()));
    }

    TidyingChest get(Chest chest) {
        return get(chest, null);
    }

    TidyingChest get(Chest chest, ImmutableMap<String, String> datas) {
        Sign sign = signConnected(chest);
        if (sign == null || signType(sign) == null) return null;

        if (datas == null) datas = tc.db.get(locationEncode(chest.getBlock()));
        if (datas == null) return null;

        return get(chest, sign, datas);
    }

    TidyingChest get(Sign sign) {
        return get(sign, null);
    }

    TidyingChest get(Sign sign, UUID uid) {
        Block block = getConnectedBlock(sign);
        if (block.getType() != Material.CHEST) return null;

        Types type = signType(sign);
        if (type == null) return null;

        ImmutableMap<String, String> datas = tc.db.get(locationEncode(block));

        if (datas == null) {
            if (uid == null) return null;

            datas = ImmutableMap.of("material", "",
                    "location", locationEncode(block), "owner", uid.toString(), "type", type.toString());
        }

        return get((Chest) block.getState(), sign, datas);
    }

    boolean limitReached(Player player, Types type) {
        if (player.hasPermission("tc.create." + type + ".unlimited")) return false;

        int count = tc.db.count(
                ImmutableMap.of("owner", player.getUniqueId().toString(), "type", type.toString()));

        ConfigurationSection perms = tc.config.getConfigurationSection("signs." + type + ".limits");

        for (String key : perms.getKeys(false))
            if (player.hasPermission("tc.create." + type + "." + key) && perms.getInt(key) > count) return false;

        return true;
    }

    ImmutableMap<String, String> locationDecode(String location) {
        Matcher m = Pattern.compile("^(.*):([-\\d]+)/([-\\d]+)/([-\\d]+)$").matcher(location);
        return (m.find() && m.groupCount() != 4) ? null
                : ImmutableMap.of("world", m.group(1), "x", m.group(2), "y", m.group(3), "z", m.group(4));
    }

    String locationEncode(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + "/" + block.getY() + "/" + block.getZ();
    }

    boolean refresh(TidyingChest chest) {
        if (chest.type != null && chest.type == Types.ROOT) chest.item = null;

        refreshSign(chest);

        return tc.db.set(locationEncode(chest.chest.getBlock()), chest.uid.toString(), chest.type.toString(),
                (chest.item == null) ? "" : chest.item.toString());
    }

    boolean refresh(TidyingChest chest, Material itemType) {
        chest.item = itemType;
        return refresh(chest);
    }

    void refreshSign(TidyingChest chest) {
        signRow(chest.sign, chest.type, Rows.TYPE, "type");
        signRow(chest.sign, chest.type, Rows.OWNER, "owner", "{owner}",
                tc.getServer().getOfflinePlayer(chest.uid).getName());

        if (chest.type == Types.TARGET)
            if (chest.item == null) signRow(chest.sign, chest.type, Rows.ITEM, "not_set");
            else signRow(chest.sign, chest.type, Rows.ITEM, "item", "{item}", (chest.item == Material.AIR)
                    ? tc.config.getString("signs." + chest.type + "." + "catchall")
                    : chest.item.name());

        chest.sign.update();
    }

    void removeOrphans() {
        tc.getLogger().info(tc.prepareText("orphan_search"));
        List<String> orphans = new ArrayList<String>();
        int          checked = 0;

        for (ImmutableMap<String, String> datas : tc.db.get()) {
            Chest chest = getChestAt(datas.get("location"));
            if (chest == null || get(chest, datas) == null) orphans.add(datas.get("location"));
            checked++;
        }

        if (orphans.isEmpty())
            tc.getLogger().info(tc.prepareText("orphan_finish", "number", checked + ""));
        else if (tc.db.del(orphans))
            tc.getLogger().warning(tc.prepareText("orphan_removed", "number", orphans.size() + ""));
    }

    Sign signConnected(Chest chest) {
        return signConnected(chest, null);
    }

    Sign signConnected(Chest chest, Sign sign) {
        if (!(chest.getInventory().getHolder() instanceof DoubleChest)) return signConnected(chest.getBlock(), sign);

        DoubleChest dc = (DoubleChest) chest.getInventory().getHolder();

        Sign left = signConnected(((Chest) dc.getLeftSide()).getBlock(), sign);
        return (left == null) ? signConnected(((Chest) dc.getRightSide()).getBlock(), sign) : left;
    }

    Sign signConnected(Block origin, Sign sign) {
        for (BlockFace face : Arrays.asList(BlockFace.NORTH, BlockFace.WEST, BlockFace.SOUTH, BlockFace.EAST)) {
            Block block = origin.getRelative(face);
            if (!(block.getBlockData() instanceof WallSign)) continue;

            Sign thisSign = (Sign) block.getState();
            if ((sign != null && sign.equals(thisSign)) || !origin.equals(getConnectedBlock(thisSign)))
                continue;

            if (signType(thisSign) != null) return thisSign;
        }

        return null;
    }

    void signRowsInit() {
        signRows = new HashMap<Types, HashMap<Rows, Integer>>();

        for (Types type : Types.values()) {
            String key = "signs." + type + ".rows";
            signRows.put(type, signRowsImport(tc.config.getStringList(key)));

            if (signRows.get(type).size() != Rows.values().length) {
                tc.getLogger().warning(tc.prepareText("err_rows", ImmutableMap.of("type", type.toString())));
                signRows.put(type, signRowsImport(tc.config.getDefaults().getStringList(key)));
            }
        }
    }

    Types signType(Sign sign) {
        for (Types type : Types.values()) if (signType(sign, type)) return type;

        return null;
    }

    boolean signType(Sign sign, Types type) {
        String typeRow = tc.cleanText(sign.getLine(signRows.get(type).get(Rows.TYPE)));

        if (typeRow.isEmpty()) return false;
        if (typeRow.equals(tc.cleanText(tc.config.getString("signs." + type + ".type")))) return true;

        for (String alias : tc.config.getStringList("signs." + type + ".aliases"))
            if (typeRow.equals(tc.cleanText(alias))) return true;

        return false;
    }

    boolean transfer(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        TidyingChest    chest  = null;

        if (holder instanceof Chest)
            chest = get((Chest) holder);
        else if (holder instanceof DoubleChest) {
            chest = get((Chest) ((DoubleChest) holder).getLeftSide());
            if (chest == null) chest = get((Chest) ((DoubleChest) holder).getRightSide());
        }
        // @formatter:off
        if (chest != null) switch (chest.type) {
            case ROOT: return transfer(inventory, chest.uid);

            case TARGET: if (chest.item != null)
                for (ImmutableMap<String, String> datas
                    : tc.db.get(chest.uid.toString(), Types.ROOT.toString()))
                {
                    Chest rootChest = getChestAt(datas.get("location"));
                    if (rootChest == null) continue;

                    TidyingChest root = get(rootChest, datas);
                    if (root == null || root.type != Types.ROOT) continue;

                    transfer(rootChest.getInventory(), root.uid, chest.item);
                } break;
        } // @formatter:on
        return true;
    }

    boolean transfer(Inventory inventory, UUID uid) {
        HashMap<String, List<Integer>> types = new HashMap<String, List<Integer>>();

        ItemStack[] stacks = inventory.getContents();
        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            if (stack == null) continue;

            String type = stack.getType().toString();
            if (!types.containsKey(type)) types.put(type, new ArrayList<Integer>());
            types.get(type).add(i);
        }

        if (types.isEmpty()) return true;

        types.put(Material.AIR.toString(), new ArrayList<Integer>());
        return transfer(inventory, uid, types);
    }

    boolean transfer(Inventory inventory, UUID uid, Material item) {
        List<Integer> slots  = new ArrayList<Integer>();
        ItemStack[]   stacks = inventory.getContents();

        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            if (stack != null && stack.getType() == item) slots.add(i);
        }

        if (slots.isEmpty()) return false;

        HashMap<String, List<Integer>> types = new HashMap<String, List<Integer>>();
        types.put(item.toString(), slots);

        return transfer(inventory, uid, types);
    }

    boolean transfer(Inventory inventory, UUID uid, HashMap<String, List<Integer>> types) {
        HashMap<String, String> targets = tc.db.get(uid.toString(), Types.TARGET.toString(), types.keySet());
        if (targets == null) return false;
        if (targets.isEmpty()) return true;

        String catchallKey = Material.AIR.toString();
        Chest  catchall    = null;

        if (targets.containsKey(catchallKey)) {
            catchall = getChestAt(targets.get(catchallKey));
            types.remove(catchallKey);
        }

        for (String type : types.keySet()) {
            Chest chest = targets.containsKey(type) ? getChestAt(targets.get(type)) : null;
            if (chest == null) chest = catchall;
            if (chest != null) for (Integer slot : types.get(type))
                inventory.setItem(slot, chest.getInventory().addItem(inventory.getItem(slot)).get(0));
        }

        return true;
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------------------------------

    private TidyingChest get(Chest chest, Sign sign, ImmutableMap<String, String> datas) {
        try {
            Types type = Types.valueOf(datas.get("type"));

            return new TidyingChest(UUID.fromString(datas.get("owner")), chest, sign, type,
                    (type == Types.ROOT || datas.get("material").isEmpty()) ? null
                            : Material.valueOf(datas.get("material")));
        }
        catch (Exception e) {
            tc.getLogger().warning(e.getMessage());
            return null;
        }
    }

    private Chest getChestAt(String location) {
        ImmutableMap<String, String> coords = locationDecode(location);
        if (coords == null) return null;

        try {
            return (Chest) tc.getServer().getWorld(coords.get("world")).getBlockAt(
                    Integer.parseInt(coords.get("x")),
                    Integer.parseInt(coords.get("y")),
                    Integer.parseInt(coords.get("z"))).getState();
        }
        catch (Exception e) {
            return null;
        }
    }

    private Block getConnectedBlock(Sign sign) {
        Block block = sign.getBlock();
        return block.getRelative(((WallSign) block.getState().getBlockData()).getFacing().getOppositeFace());
    }

    private void signRow(Sign sign, Types type, Rows row, String key) {
        signRow(sign, type, row, key, null, null);
    }

    private void signRow(Sign sign, Types type, Rows row, String key, String from, String replace) {
        String text = tc.config.getString("signs." + type + "." + key);
        if (from != null) text = text.replace(from, replace);

        sign.setLine(signRows.get(type).get(row), tc.formatText(text));
    }

    private HashMap<Rows, Integer> signRowsImport(List<String> list) {
        HashMap<Rows, Integer> map = new HashMap<Rows, Integer>();

        try {
            for (int i = 0; i < list.size(); i++) map.put(Rows.valueOf(list.get(i)), i);
        }
        catch (Exception e) {}

        return map;
    }
}
