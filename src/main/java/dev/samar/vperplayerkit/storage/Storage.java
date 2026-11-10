package dev.samar.vperplayerkit.storage;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.rtpq.HistoryRow;
import dev.samar.vperplayerkit.rtpq.PlayerStats;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * One generic key/value table, works on SQLite and MySQL:
 * owner = player uuid or "global", type = kit/echest/icon/selected/kitroom/public/share, id = slot, name or code.
 * All methods block, so call them off the main thread.
 */
public final class Storage {
    private static final String CREATE =
            "CREATE TABLE IF NOT EXISTS vpk_data ("
                    + "owner VARCHAR(64) NOT NULL, type VARCHAR(32) NOT NULL, id VARCHAR(64) NOT NULL, "
                    + "data MEDIUMBLOB, PRIMARY KEY (owner, type, id))";

    private static final String CREATE_STATS =
            "CREATE TABLE IF NOT EXISTS vpk_stats ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32), "
                    + "kills INT NOT NULL DEFAULT 0, wins INT NOT NULL DEFAULT 0, losses INT NOT NULL DEFAULT 0, "
                    + "played INT NOT NULL DEFAULT 0, tier INT NOT NULL DEFAULT 0, "
                    + "progress DOUBLE NOT NULL DEFAULT 0, chances INT NOT NULL DEFAULT 2)";
    private static final String CREATE_HISTORY =
            "CREATE TABLE IF NOT EXISTS vpk_history ("
                    + "uuid VARCHAR(36) NOT NULL, played_at BIGINT NOT NULL, my_score INT, opp_score INT, "
                    + "my_tier INT, opp_tier INT, my_name VARCHAR(32), opp_name VARCHAR(32), test INT, "
                    + "PRIMARY KEY (uuid, played_at))";

    private final VPerPlayerKit plugin;
    private final boolean mysql;
    private final String url;
    private final String user;
    private final String pass;
    private Connection conn;

    public Storage(VPerPlayerKit plugin) throws SQLException {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        this.mysql = "mysql".equalsIgnoreCase(cfg.getString("storage.type", "sqlite"));
        if (mysql) {
            String host = cfg.getString("storage.mysql.host", "localhost");
            int port = cfg.getInt("storage.mysql.port", 3306);
            String db = cfg.getString("storage.mysql.database", "vperplayerkit");
            boolean ssl = cfg.getBoolean("storage.mysql.use-ssl", false);
            this.url = "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=" + ssl + "&autoReconnect=true&characterEncoding=utf8";
            this.user = cfg.getString("storage.mysql.username", "root");
            this.pass = cfg.getString("storage.mysql.password", "");
        } else {
            plugin.getDataFolder().mkdirs();
            File file = new File(plugin.getDataFolder(), cfg.getString("storage.sqlite-file", "kits.db"));
            this.url = "jdbc:sqlite:" + file.getAbsolutePath();
            this.user = null;
            this.pass = null;
        }
        try {
            Class.forName(mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("JDBC driver not found", ex);
        }
        try (Statement st = connection().createStatement()) {
            st.executeUpdate(CREATE);
            st.executeUpdate(CREATE_STATS);
            st.executeUpdate(CREATE_HISTORY);
        }
    }

    private synchronized Connection connection() throws SQLException {
        if (conn == null || conn.isClosed() || (mysql && !conn.isValid(2))) {
            conn = (user == null) ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, pass);
        }
        return conn;
    }

    public synchronized boolean put(String owner, String type, String id, byte[] data) {
        try (PreparedStatement ps = connection().prepareStatement(
                "REPLACE INTO vpk_data (owner, type, id, data) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, type);
            ps.setString(3, id);
            ps.setBytes(4, data);
            ps.executeUpdate();
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database write failed", ex);
            return false;
        }
    }

    public synchronized byte[] get(String owner, String type, String id) {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT data FROM vpk_data WHERE owner = ? AND type = ? AND id = ?")) {
            ps.setString(1, owner);
            ps.setString(2, type);
            ps.setString(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
            return null;
        }
    }

    public synchronized boolean exists(String owner, String type, String id) {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT 1 FROM vpk_data WHERE owner = ? AND type = ? AND id = ?")) {
            ps.setString(1, owner);
            ps.setString(2, type);
            ps.setString(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
            return false;
        }
    }

    public synchronized boolean delete(String owner, String type, String id) {
        try (PreparedStatement ps = connection().prepareStatement(
                "DELETE FROM vpk_data WHERE owner = ? AND type = ? AND id = ?")) {
            ps.setString(1, owner);
            ps.setString(2, type);
            ps.setString(3, id);
            ps.executeUpdate();
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database delete failed", ex);
            return false;
        }
    }

    public synchronized List<String> ids(String owner, String type) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT id FROM vpk_data WHERE owner = ? AND type = ?")) {
            ps.setString(1, owner);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
        }
        return out;
    }

    public synchronized Map<String, byte[]> all(String owner, String type) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT id, data FROM vpk_data WHERE owner = ? AND type = ?")) {
            ps.setString(1, owner);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getBytes(2));
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
        }
        return out;
    }

    // ------------------------------------------------------------------ rtpq stats

    private static PlayerStats readStats(ResultSet rs) throws SQLException {
        PlayerStats s = new PlayerStats(UUID.fromString(rs.getString("uuid")), rs.getString("name"));
        s.kills = rs.getInt("kills");
        s.wins = rs.getInt("wins");
        s.losses = rs.getInt("losses");
        s.played = rs.getInt("played");
        s.tier = rs.getInt("tier");
        s.progress = rs.getDouble("progress");
        s.chances = rs.getInt("chances");
        return s;
    }

    /** Always returns an object; a player without a row gets fresh Unranked stats. */
    public synchronized PlayerStats loadStats(UUID uuid, String name) {
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM vpk_stats WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerStats s = readStats(rs);
                    if (name != null) s.name = name;
                    return s;
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
        }
        return new PlayerStats(uuid, name);
    }

    /** Null when no stats row has that name. */
    public synchronized PlayerStats loadStatsByName(String name) {
        try (PreparedStatement ps = connection().prepareStatement("SELECT * FROM vpk_stats WHERE LOWER(name) = ?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return readStats(rs);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
        }
        return null;
    }

    public synchronized boolean saveStats(PlayerStats s) {
        try (PreparedStatement ps = connection().prepareStatement(
                "REPLACE INTO vpk_stats (uuid, name, kills, wins, losses, played, tier, progress, chances) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, s.uuid.toString());
            ps.setString(2, s.name);
            ps.setInt(3, s.kills);
            ps.setInt(4, s.wins);
            ps.setInt(5, s.losses);
            ps.setInt(6, s.played);
            ps.setInt(7, s.tier);
            ps.setDouble(8, s.progress);
            ps.setInt(9, s.chances);
            ps.executeUpdate();
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database write failed", ex);
            return false;
        }
    }

    private static String orderBy(String category) {
        return switch (category) {
            case "kills" -> "kills DESC";
            case "victory" -> "wins DESC";
            case "defeat" -> "losses DESC";
            case "matchplayed" -> "played DESC";
            default -> "tier DESC, progress DESC";   // overall
        };
    }

    /** category: overall, kills, victory, defeat, matchplayed */
    public synchronized List<PlayerStats> top(String category, int limit) {
        List<PlayerStats> out = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM vpk_stats ORDER BY " + orderBy(category) + ", wins DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readStats(rs));
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
        }
        return out;
    }

    /** 1-based position of the given stats in a category. */
    public synchronized int rank(String category, PlayerStats s) {
        String sql;
        switch (category) {
            case "kills" -> sql = "SELECT COUNT(*) FROM vpk_stats WHERE kills > ?";
            case "victory" -> sql = "SELECT COUNT(*) FROM vpk_stats WHERE wins > ?";
            case "defeat" -> sql = "SELECT COUNT(*) FROM vpk_stats WHERE losses > ?";
            case "matchplayed" -> sql = "SELECT COUNT(*) FROM vpk_stats WHERE played > ?";
            default -> sql = "SELECT COUNT(*) FROM vpk_stats WHERE tier > ? OR (tier = ? AND progress > ?)";
        }
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            switch (category) {
                case "kills" -> ps.setInt(1, s.kills);
                case "victory" -> ps.setInt(1, s.wins);
                case "defeat" -> ps.setInt(1, s.losses);
                case "matchplayed" -> ps.setInt(1, s.played);
                default -> {
                    ps.setInt(1, s.tier);
                    ps.setInt(2, s.tier);
                    ps.setDouble(3, s.progress);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) + 1;
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
        }
        return -1;
    }

    public synchronized boolean addHistory(UUID uuid, HistoryRow row) {
        try (PreparedStatement ps = connection().prepareStatement(
                "REPLACE INTO vpk_history (uuid, played_at, my_score, opp_score, my_tier, opp_tier, my_name, opp_name, test) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, row.time());
            ps.setInt(3, row.myScore());
            ps.setInt(4, row.oppScore());
            ps.setInt(5, row.myTier());
            ps.setInt(6, row.oppTier());
            ps.setString(7, row.myName());
            ps.setString(8, row.oppName());
            ps.setInt(9, row.test() ? 1 : 0);
            ps.executeUpdate();
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database write failed", ex);
            return false;
        }
    }

    public synchronized List<HistoryRow> history(UUID uuid, int limit) {
        List<HistoryRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT * FROM vpk_history WHERE uuid = ? ORDER BY played_at DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HistoryRow(rs.getLong("played_at"), rs.getInt("my_score"), rs.getInt("opp_score"),
                            rs.getInt("my_tier"), rs.getInt("opp_tier"), rs.getString("my_name"),
                            rs.getString("opp_name"), rs.getInt("test") == 1));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Database read failed", ex);
        }
        return out;
    }

    public synchronized void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
            // shutting down
        }
    }
}
