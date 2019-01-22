package me.arboriginal.TidyingChest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import com.google.common.collect.ImmutableMap;

class TCDatabase {
  private TCPlugin   tc;
  private Connection db;
  private String     table;
  // ----------------------------------------------------------------------------------------------
  // Constructor methods
  // ----------------------------------------------------------------------------------------------

  public TCDatabase(TCPlugin plugin) throws SQLException {
    tc    = plugin;
    table = tc.config.getString("database.table");

    String type = tc.config.getString("database.type");

    db = (type.equals("sqlite")
        ? DriverManager.getConnection("jdbc:sqlite:" + tc.getDataFolder() + '/' + tc.config.getString("database.file"))
        : DriverManager.getConnection("jdbc:" + type + "://" + tc.config.getString("database.host")
            + ":" + tc.config.getInt("database.port") + "/" + tc.config.getString("database.base"),
            tc.config.getString("database.user"), tc.config.getString("database.pass")));

    Statement stmt = db.createStatement();
    createTable(stmt);
    createIndex(stmt, type, "owner");
    createIndex(stmt, type, "type");
    createIndex(stmt, type, "material");
    stmt.close();
  }

  // ----------------------------------------------------------------------------------------------
  // Protected methods
  // ----------------------------------------------------------------------------------------------

  protected void close() {
    try {
      db.close();
    }
    catch (SQLException error) {
      log("Disconnect", error);
    }
  }

  protected int count(ImmutableMap<String, String> conditions) {
    String sql = "SELECT COUNT(*) AS locations FROM " + table;

    List<String> keys = new ArrayList<String>();
    List<String> vals = new ArrayList<String>();

    for (String key : conditions.keySet()) {
      keys.add(key + " = ?");
      vals.add(conditions.get(key));
    }

    sql += " WHERE " + String.join(" AND ", keys);

    try {
      PreparedStatement query = db.prepareStatement(sql);
      for (int i = 0; i < vals.size(); i++) query.setString(i + 1, vals.get(i));
      ResultSet set = query.executeQuery();
      if (set.isBeforeFirst() && set.next()) return set.getInt("locations");
    }
    catch (SQLException error) {
      tc.getLogger().warning(tc.prepareText("err_cnt"));
      log(sql, error);
    }

    return 999999999;
  }

  protected boolean del(String location) {
    boolean err = true;
    String  sql = "DELETE FROM " + table + " WHERE location = ?;";

    try {
      PreparedStatement query = db.prepareStatement(sql);
      query.setString(1, location);
      query.executeUpdate();
      err = false;
    }
    catch (SQLException error) {
      log(sql, error);
    }

    if (err) tc.getLogger().warning(tc.prepareText("err_del", "key", location));

    return !err;
  }

  protected boolean del(List<String> locations) {
    String[] masks = new String[locations.size()];
    Arrays.fill(masks, "?");

    String  sql = "DELETE FROM " + table + " WHERE location IN (" + String.join(",", masks) + ");";
    boolean err = true;

    try {
      PreparedStatement query = db.prepareStatement(sql);
      for (int i = 0; i < locations.size(); i++) query.setString(i + 1, locations.get(i));
      query.executeUpdate();
      err = false;
    }
    catch (SQLException error) {
      log(sql, error);
    }

    if (err) tc.getLogger().warning(tc.prepareText("err_del", "key", "orphans"));

    return !err;
  }

  protected List<ImmutableMap<String, String>> get() {
    List<ImmutableMap<String, String>> res = new ArrayList<ImmutableMap<String, String>>();

    String sql = "SELECT location, owner, type, material FROM " + table;

    try {
      ResultSet set = db.createStatement().executeQuery(sql);

      if (set.isBeforeFirst())
        while (set.next())
          res.add(ImmutableMap.of(
              "location", set.getString("location"),
              "owner", set.getString("owner"),
              "type", set.getString("type"),
              "material", set.getString("material")));
    }
    catch (SQLException error) {
      log(sql, error);
    }

    return res;
  }

  protected ImmutableMap<String, String> get(String location) {
    String sql = "SELECT owner, type, material FROM " + table + " WHERE location = ?";

    try {
      PreparedStatement query = db.prepareStatement(sql);
      query.setString(1, location);
      ResultSet set = query.executeQuery();

      if (set.isBeforeFirst() && set.next())
        return ImmutableMap.of(
            "location", location,
            "owner", set.getString("owner"),
            "type", set.getString("type"),
            "material", set.getString("material"));
    }
    catch (SQLException error) {
      log(sql, (SQLException) error);
    }

    return null;
  }

  protected List<ImmutableMap<String, String>> get(String owner, String targetType) {
    List<ImmutableMap<String, String>> res = new ArrayList<ImmutableMap<String, String>>();

    String sql = "SELECT location FROM " + table + " WHERE owner = ? AND type = ?";

    try {
      PreparedStatement query = db.prepareStatement(sql);
      query.setString(1, owner);
      query.setString(2, targetType);
      ResultSet set = query.executeQuery();

      if (set.isBeforeFirst())
        while (set.next())
          res.add(ImmutableMap.of(
              "location", set.getString("location"),
              "owner", owner,
              "type", targetType,
              "material", ""));
    }
    catch (SQLException error) {
      log(sql, error);
    }

    return res;
  }

  protected HashMap<String, String> get(String owner, String targetType, Set<String> types) {
    String[] masks = new String[types.size()];
    Arrays.fill(masks, "?");

    String sql = "SELECT location, material FROM " + table
        + " WHERE owner = ? AND type = ? AND material IN (" + String.join(" , ", masks) + ");";

    try {
      PreparedStatement query = db.prepareStatement(sql);
      int               i     = 0;
      query.setString(++i, owner);
      query.setString(++i, targetType);
      for (String type : types) query.setString(++i, type);
      ResultSet set = query.executeQuery();

      HashMap<String, String> locations = new HashMap<String, String>();

      if (set.isBeforeFirst())
        while (set.next()) locations.put(set.getString("material"), set.getString("location"));

      return locations;
    }
    catch (SQLException error) {
      log(sql, error);
    }

    return null;
  }

  protected boolean set(String location, String owner, String type, String material) {
    boolean err = true;
    String  sql = "REPLACE INTO " + table + " (location, owner, type, material) VALUES (?, ?, ?, ?);";

    try {
      PreparedStatement query = db.prepareStatement(sql);
      query.setString(1, location);
      query.setString(2, owner);
      query.setString(3, type);
      query.setString(4, material);

      err = (query.executeUpdate() == 0);
    }
    catch (SQLException error) {
      log(sql, error);
    }

    if (err) tc.getLogger().warning(tc.prepareText("err_set", "key", location));

    return !err;
  }

  // ----------------------------------------------------------------------------------------------
  // Private methods
  // ----------------------------------------------------------------------------------------------

  private boolean createIndex(Statement stmt, String type, String index) throws SQLException {
    String sql = "CREATE INDEX ";

    switch (type) {
      case "mysql":
        ResultSet set = stmt.executeQuery("SELECT COUNT(1) AS \"" + index + "\" FROM INFORMATION_SCHEMA.STATISTICS "
            + "WHERE table_schema=DATABASE() AND table_name=\"" + table + "\" AND index_name=\"" + index + "\";");
        if (set.isBeforeFirst() && set.next() && set.getInt(index) == 1) return true;
        break;

      case "sqlite":
        sql += "IF NOT EXISTS ";
        break;

      default:
        return false;
    }

    return stmt.execute(sql + index + " ON " + table + " (" + index + ");");
  }

  private boolean createTable(Statement stmt) throws SQLException {
    return stmt.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
        + "created TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
        + "location VARCHAR(255) NOT NULL PRIMARY KEY,"
        + "owner VARCHAR(255) NOT NULL,"
        + "type VARCHAR(255) NOT NULL,"
        + "material VARCHAR(255) NOT NULL DEFAULT \"\""
        + ");");
  }

  private void log(String sql, SQLException error) {
    String file = tc.getDataFolder() + "/sqlerror.txt";
    Path   path = Paths.get(file);

    if (!Files.exists(path))
      try {
        Files.createFile(path);
      }
      catch (IOException e) {
        tc.getLogger().warning(tc.prepareText("err_file", "file", file));
        return;
      }

    try {
      Files.write(path, ("[" + new Date().toString() + "]\n" + sql + "\nError: " + error.getMessage() + "\n"
          + "-----------------------------------------------------------\n").getBytes(), StandardOpenOption.APPEND);
    }
    catch (IOException e) {
      tc.getLogger().warning(tc.prepareText("err_write", "file", file));
    }
  }
}
