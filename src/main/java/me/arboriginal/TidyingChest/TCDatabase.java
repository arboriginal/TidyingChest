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
import org.bukkit.configuration.ConfigurationSection;
import com.google.common.collect.ImmutableMap;

class TCDatabase {
    private Connection db;
    private TCPlugin   tc;
    private String     table;

    // ----------------------------------------------------------------------------------------------
    // Constructor methods
    // ----------------------------------------------------------------------------------------------

    TCDatabase(TCPlugin plugin) throws SQLException {
        ConfigurationSection config = plugin.config.getConfigurationSection("database");

        String  type   = config.getString("type");
        boolean sqlite = type.equals("sqlite");

        tc = plugin;
        db = sqlite
                ? DriverManager.getConnection("jdbc:sqlite:" + tc.getDataFolder() + '/' + config.getString("file"))
                : DriverManager.getConnection("jdbc:" + type + "://" + config.getString("host")
                        + ":" + config.getInt("port") + "/" + config.getString("base"),
                        config.getString("user"), config.getString("pass"));

        Statement stmt = db.createStatement();
        table = config.getString("table");
        // @formatter:off
        stmt.execute(
        "CREATE TABLE IF NOT EXISTS " + table + " (" +
            "created TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "location VARCHAR(255) NOT NULL," +
            "owner VARCHAR(255) NOT NULL," +
            "type VARCHAR(255) NOT NULL," +
            "material VARCHAR(255) NOT NULL DEFAULT \"\"" +
            (sqlite? "": ",UNIQUE location(location)"+
                          ",INDEX material(material)"+
                          ",INDEX owner(owner)"+
                          ",INDEX type(type)") +
        ");");

        if (sqlite) {
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS location ON " + table + " (location);");
            stmt.execute("CREATE "   + "INDEX IF NOT EXISTS material ON " + table + " (material);");
            stmt.execute("CREATE "   + "INDEX IF NOT EXISTS owner ON "    + table + " (owner);");
            stmt.execute("CREATE "   + "INDEX IF NOT EXISTS type ON "     + table + " (type);");
        }
        // @formatter:on
        stmt.close();
    }

    // ----------------------------------------------------------------------------------------------
    // Package methods
    // ----------------------------------------------------------------------------------------------

    void close() {
        try {
            db.close();
        }
        catch (SQLException error) {
            log("Disconnect", error);
        }
    }

    int count(ImmutableMap<String, String> conditions) {
        List<String> keys = new ArrayList<String>(), vals = new ArrayList<String>();

        for (String key : conditions.keySet()) {
            keys.add(key + " = ?");
            vals.add(conditions.get(key));
        }

        String sql = "SELECT COUNT(*) AS locations FROM " + table + " WHERE " + String.join(" AND ", keys);

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

    boolean del(String location) {
        String sql = "DELETE FROM " + table + " WHERE location = ?;";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            query.setString(1, location);
            query.executeUpdate();
            return true;
        }
        catch (SQLException error) {
            tc.getLogger().warning(tc.prepareText("err_del", "key", location));
            log(sql, error);
            return false;
        }
    }

    boolean del(List<String> locations) {
        String[] masks = new String[locations.size()];
        Arrays.fill(masks, "?");

        String sql = "DELETE FROM " + table + " WHERE location IN (" + String.join(",", masks) + ");";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            for (int i = 0; i < locations.size(); i++) query.setString(i + 1, locations.get(i));

            query.executeUpdate();
            return true;
        }
        catch (SQLException error) {
            tc.getLogger().warning(tc.prepareText("err_del", "key", "orphans"));
            log(sql, error);
            return false;
        }
    }

    List<ImmutableMap<String, String>> get() {
        List<ImmutableMap<String, String>> res = new ArrayList<ImmutableMap<String, String>>();

        String sql = "SELECT location, owner, type, material FROM " + table;

        try {
            ResultSet set = db.createStatement().executeQuery(sql);
            // @formatter:off
            if (set.isBeforeFirst()) while (set.next()) res.add(ImmutableMap.of(
                "location", set.getString("location"),
                "owner",    set.getString("owner"),
                "type",     set.getString("type"),
                "material", set.getString("material")));
        } catch (SQLException error) { log(sql, error); }
        // @formatter:on
        return res;
    }

    ImmutableMap<String, String> get(String location) {
        String sql = "SELECT owner, type, material FROM " + table + " WHERE location = ?";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            query.setString(1, location);

            ResultSet set = query.executeQuery();
            // @formatter:off
            if (set.isBeforeFirst() && set.next()) return ImmutableMap.of(
                "location", location,
                "owner",    set.getString("owner"),
                "type",     set.getString("type"),
                "material", set.getString("material"));
        } catch (SQLException error) { log(sql, (SQLException) error); }
        // @formatter:on
        return null;
    }

    List<ImmutableMap<String, String>> get(String owner, String targetType) {
        List<ImmutableMap<String, String>> res = new ArrayList<ImmutableMap<String, String>>();

        String sql = "SELECT location FROM " + table + " WHERE owner = ? AND type = ?";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            query.setString(1, owner);
            query.setString(2, targetType);

            ResultSet set = query.executeQuery();
            // @formatter:off
            if (set.isBeforeFirst()) while (set.next()) res.add(ImmutableMap.of(
                "location", set.getString("location"),
                "owner",    owner,
                "type",     targetType,
                "material", ""));
        } catch (SQLException error) { log(sql, error); }
        // @formatter:on
        return res;
    }

    HashMap<String, String> get(String owner, String targetType, Set<String> types) {
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

    boolean set(String location, String owner, String type, String material) {
        String sql = "REPLACE INTO " + table + " (location, owner, type, material) VALUES (?, ?, ?, ?);";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            query.setString(1, location);
            query.setString(2, owner);
            query.setString(3, type);
            query.setString(4, material);

            return (query.executeUpdate() == 1);
        }
        catch (SQLException error) {
            tc.getLogger().warning(tc.prepareText("err_set", "key", location));
            log(sql, error);
            return false;
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------------------------------

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
            Files.write(path, ("[" + new Date().toString() + "]\n" + sql + "\nError: " + error.getMessage()
                    + "\n-----------------------------------------------------------\n").getBytes(),
                    StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            tc.getLogger().warning(tc.prepareText("err_write", "file", file));
        }
    }
}
