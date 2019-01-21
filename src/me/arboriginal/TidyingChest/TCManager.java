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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import com.google.common.collect.ImmutableMap;

class TCManager {
  private TCPlugin tc;

  // @formatter:off
  static enum Types { ROOT, TARGET }
  static enum Rows  { FREE, ITEM, OWNER, TYPE }
  // @formatter:on

  private HashMap<Types, HashMap<Rows, Integer>> signRows;

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
    protected Chest chest = null; protected Material item = null; protected UUID uid = null;
    protected Sign  sign  = null; protected Types    type = null;

    public TidyingChest(UUID uid, Chest chest, Sign sign, Types type, Material item) {
      this.chest = chest; this.item = item; this.sign = sign; this.type = type; this.uid = uid;
    }// @formatter:on
  }

  // ----------------------------------------------------------------------------------------------
  // Protected methods
  // ----------------------------------------------------------------------------------------------

  protected boolean del(TidyingChest chest) {
    return tc.db.del(locationEncode(chest.chest.getBlock()));
  }

  protected TidyingChest get(Chest chest) {
    return get(chest, null);
  }

  protected TidyingChest get(Chest chest, ImmutableMap<String, String> datas) {
    Sign sign = signConnected(chest);
    if (sign == null || signType(sign) == null) return null;

    if (datas == null) datas = tc.db.get(locationEncode(chest.getBlock()));
    if (datas == null) return null;

    return get(chest, sign, datas);
  }

  protected TidyingChest get(Sign sign) {
    return get(sign, null);
  }

  protected TidyingChest get(Sign sign, UUID uid) {
    Block block = sign.getBlock().getRelative(((org.bukkit.material.Sign) sign.getData()).getAttachedFace());
    if (!block.getType().equals(Material.CHEST)) return null;

    Types type = signType(sign);
    if (type == null) return null;

    ImmutableMap<String, String> datas = tc.db.get(locationEncode(block));

    if (datas == null) {
      if (uid == null) return null;

      datas = ImmutableMap.of(
          "location", locationEncode(block), "owner", uid.toString(), "type", type.toString(), "material", "");
    }

    return get((Chest) block.getState(), sign, datas);
  }

  protected boolean limitReached(Player player, Types type) {
    if (player.hasPermission("tc.create." + type + ".unlimited")) return false;

    ConfigurationSection perms = tc.config.getConfigurationSection("signs." + type + ".limits");
    int                  count = tc.db.count(
        ImmutableMap.of("owner", player.getUniqueId().toString(), "type", type.toString()));

    for (String key : perms.getKeys(false))
      if (player.hasPermission("tc.create." + type + "." + key) && perms.getInt(key) > count) return false;

    return true;
  }

  protected ImmutableMap<String, String> locationDecode(String location) {
    Matcher m = Pattern.compile("^(.*):([-\\d]+)/([-\\d]+)/([-\\d]+)$").matcher(location);

    if (m.find() && m.groupCount() == 4) {
      return ImmutableMap.of("world", m.group(1), "x", m.group(2), "y", m.group(3), "z", m.group(4));
    }

    return null;
  }

  protected String locationEncode(Block block) {
    return block.getWorld().getName() + ":" + block.getX() + "/" + block.getY() + "/" + block.getZ();
  }

  protected boolean refresh(TidyingChest chest) {
    if (chest.type != null && chest.type.equals(Types.ROOT)) chest.item = null;

    refreshSign(chest);

    return tc.db.set(
        locationEncode(chest.chest.getBlock()),
        chest.uid.toString(),
        chest.type.toString(),
        (chest.item == null) ? "" : chest.item.toString());
  }

  protected boolean refresh(TidyingChest chest, Material itemType) {
    chest.item = itemType;
    return refresh(chest);
  }

  protected void refreshSign(TidyingChest chest) { // @formatter:off
    signRow(chest.sign, chest.type, Rows.TYPE,  "type");
    signRow(chest.sign, chest.type, Rows.OWNER, "owner", "{owner}",
        tc.getServer().getOfflinePlayer(chest.uid).getName());

    if (chest.type.equals(Types.TARGET)) if (chest.item == null)
      signRow(chest.sign, chest.type, Rows.ITEM, "not_set"); else
      signRow(chest.sign, chest.type, Rows.ITEM, "item", "{item}", chest.item.equals(Material.AIR) ?
        tc.config.getString("signs." + chest.type + "." + "catchall") : chest.item.name());

    chest.sign.update();
  } // @formatter:on

  protected void removeOrphans() {
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
    else if (tc.db.del(orphans)) {
      tc.getLogger().warning(tc.prepareText("orphan_removed", "number", orphans.size() + ""));
    }
  }

  protected Sign signConnected(Chest chest) {
    return signConnected(chest, null);
  }

  protected Sign signConnected(Chest chest, Sign sign) {
    Sign connectedSign = null;

    if (chest.getInventory().getHolder() instanceof DoubleChest) {
      DoubleChest dc = (DoubleChest) chest.getInventory().getHolder();

      connectedSign = signConnected(((Chest) dc.getLeftSide()).getBlock(), sign);
      if (connectedSign != null) return connectedSign;
      connectedSign = signConnected(((Chest) dc.getRightSide()).getBlock(), sign);
    }
    else
      connectedSign = signConnected(chest.getBlock(), sign);

    return connectedSign;
  }

  protected Sign signConnected(Block origin, Sign sign) {
    for (BlockFace face : Arrays.asList(BlockFace.NORTH, BlockFace.WEST, BlockFace.SOUTH, BlockFace.EAST)) {
      Block block = origin.getRelative(face);
      if (!block.getType().equals(Material.WALL_SIGN)) continue;

      Sign thisSign = (Sign) block.getState();
      if ((sign != null && sign.equals(thisSign)) || (!thisSign.getBlock().getRelative(
          ((org.bukkit.material.Sign) thisSign.getData()).getAttachedFace()).equals(origin)))
        continue;

      if (signType(thisSign) != null) return thisSign;
    }

    return null;
  }

  protected void signRowsInit() {
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

  protected Types signType(Sign sign) {
    for (Types type : Types.values()) if (signType(sign, type)) return type;

    return null;
  }

  protected boolean signType(Sign sign, Types type) {
    String typeRow = tc.cleanText(sign.getLine(signRows.get(type).get(Rows.TYPE)));

    if (typeRow.isEmpty()) return false;
    if (typeRow.equals(tc.cleanText(tc.config.getString("signs." + type + ".type")))) return true;
    for (String alias : tc.config.getStringList("signs." + type + ".aliases"))
      if (typeRow.equals(tc.cleanText(alias))) return true;

    return false;
  }

  protected boolean transfer(Inventory inventory) {
    InventoryHolder holder = inventory.getHolder();
    TidyingChest    chest  = null;

    if (holder instanceof Chest)
      chest = get((Chest) holder);
    else if (holder instanceof DoubleChest) {
      chest = get((Chest) ((DoubleChest) holder).getLeftSide());
      if (chest == null) chest = get((Chest) ((DoubleChest) holder).getRightSide());
    }

    if (chest != null) {
      switch (chest.type) {
        case ROOT:
          return transfer(inventory, chest.uid);

        case TARGET:
          if (chest.item != null)
            for (ImmutableMap<String, String> datas : tc.db.get(chest.uid.toString(), Types.ROOT.toString())) {
              Chest rootChest = getChestAt(datas.get("location"));
              if (rootChest == null) continue;
              TidyingChest root = get(rootChest, datas);
              if (root == null || !root.type.equals(Types.ROOT)) continue;
              transfer(rootChest.getInventory(), root.uid, chest.item);
            }
          break;
      }
    }

    return true;
  }

  protected boolean transfer(Inventory inventory, UUID uid) {
    String catchallKey = Material.AIR.toString();

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
    types.put(catchallKey, new ArrayList<Integer>());

    return transfer(inventory, uid, types);
  }

  protected boolean transfer(Inventory inventory, UUID uid, Material item) {
    String key = item.toString();

    HashMap<String, List<Integer>> types = new HashMap<String, List<Integer>>();
    types.put(key, new ArrayList<Integer>());

    ItemStack[] stacks = inventory.getContents();
    for (int i = 0; i < stacks.length; i++) {
      ItemStack stack = stacks[i];
      if (stack == null || !stack.getType().equals(item)) continue;
      types.get(key).add(i);
    }

    return !types.get(key).isEmpty() && transfer(inventory, uid, types);
  }

  protected boolean transfer(Inventory inventory, UUID uid, HashMap<String, List<Integer>> types) {
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
      Chest chest = null;
      if (targets.containsKey(type)) chest = getChestAt(targets.get(type));
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
    TidyingChest tidyingChest = null;

    try {
      Types type = Types.valueOf(datas.get("type"));

      tidyingChest = new TidyingChest(
          UUID.fromString(datas.get("owner")), chest, sign, type,
          (type.equals(Types.ROOT) || datas.get("material").isEmpty()) ? null
              : Material.valueOf(datas.get("material")));
    }
    catch (Exception e) {
      tc.getLogger().warning(e.getMessage());
    }

    return tidyingChest;
  }

  private Chest getChestAt(String location) {
    ImmutableMap<String, String> coords = locationDecode(location);
    if (coords == null) return null;

    Chest chest = null;

    try {
      chest = (Chest) tc.getServer().getWorld(coords.get("world")).getBlockAt(
          Integer.parseInt(coords.get("x")),
          Integer.parseInt(coords.get("y")),
          Integer.parseInt(coords.get("z"))).getState();
    }
    catch (Exception e) {}

    return chest;
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

    for (int i = 0; i < list.size(); i++) try {
      Rows row = Rows.valueOf(list.get(i));
      map.put(row, i);
    }
    catch (Exception e) {}

    return map;
  }
}
