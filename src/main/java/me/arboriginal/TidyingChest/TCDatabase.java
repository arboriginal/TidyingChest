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
                        + ":" + config.getInt("port") + "/" + config.getString("base") + config.getString("options"),
                        config.getString("user"), config.getString("pass"));

        Statement stmt = db.createStatement();
        table = config.getString("table");
        // @formatter:off
        stmt.execute(
        "CREATE TABLE IF NOT EXISTS " + table + " (" +
            "created TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "location VARCHAR(255) NOT NULL," +
            "material VARCHAR(255) NOT NULL DEFAULT \"\"," +
            "owner VARCHAR(255) NOT NULL," +
            "type VARCHAR(255) NOT NULL" +
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

    boolean del(String location) {
        ArrayList<String> locations = new ArrayList<String>();
        locations.add(location);

        return del(locations, location);
    }

    boolean del(ArrayList<String> locations, String key) {
        String[] masks = new String[locations.size()];
        Arrays.fill(masks, "?");
        // @note: can't use a single ? + query.setArray() because of SQLITE (not implemented)
        String sql = "DELETE FROM " + table + " WHERE location IN (" + String.join(",", masks) + ");";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            for (int i = 0; i < locations.size(); i++) query.setString(i + 1, locations.get(i));

            query.executeUpdate();
            return true;
        }
        catch (SQLException error) {
            tc.getLogger().warning(tc.prepareText("err_del", "key", key));
            log(sql, error);
            return false;
        }
    }

    ArrayList<ImmutableMap<String, String>> getAll(int from, int limit) {
        ArrayList<ImmutableMap<String, String>> res = new ArrayList<ImmutableMap<String, String>>();

        String sql = "SELECT location, material, owner, type FROM " + table + " LIMIT " + from + "," + limit;

        try {
            ResultSet set = db.createStatement().executeQuery(sql);
            // @formatter:off
            if (set.isBeforeFirst()) while (set.next()) res.add(ImmutableMap.of(
                "location", set.getString("location"),
                "material", set.getString("material"),
                "owner",    set.getString("owner"),
                "type",     set.getString("type")));
        } catch (SQLException error) { log(sql, error); }
        // @formatter:on
        return res;
    }

    ArrayList<ImmutableMap<String, String>> getByOwner(String owner) {
        ArrayList<ImmutableMap<String, String>> res = new ArrayList<ImmutableMap<String, String>>();

        String sql = "SELECT location, material, type FROM " + table + " WHERE owner = ?";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            query.setString(1, owner);

            ResultSet set = query.executeQuery();
            // @formatter:off
            if (set.isBeforeFirst()) while (set.next()) res.add(ImmutableMap.of(
                "location", set.getString("location"),
                "material", set.getString("material"),
                "type",     set.getString("type"),
                "owner",    owner));
        } catch (SQLException error) { log(sql, error); return null; }
        // @formatter:on
        return res;
    }

    ImmutableMap<String, String> getOwner(String[] locations) {
        String[] masks = new String[locations.length];
        Arrays.fill(masks, "?");
        // @note: can't use a single ? + query.setArray() because of SQLITE (not implemented)
        String sql = "SELECT location, owner FROM " + table + " WHERE location IN (" + String.join(",", masks) + ")";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            for (int i = 0; i < locations.length; i++) query.setString(i + 1, locations[i]);

            ResultSet set = query.executeQuery();
            if (set.isBeforeFirst() && set.next())
                return ImmutableMap.of("location", set.getString("location"), "owner", set.getString("owner"));
        // @formatter:off
        } catch (SQLException error) { log(sql, (SQLException) error); }
        // @formatter:on
        return null;
    }

    boolean set(String location, String material, String owner, String type) {
        String sql = "INSERT INTO " + table + " (location, material, owner, type) VALUES (?, ?, ?, ?);";

        try {
            PreparedStatement query = db.prepareStatement(sql);
            query.setString(1, location);
            query.setString(2, material);
            query.setString(3, owner);
            query.setString(4, type);

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
