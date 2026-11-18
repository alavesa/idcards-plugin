package fi.alavesa.idcards.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage (plugins/IdCards/idcards.db): placed blocks, links, the scan log, issued cards and the profile
 * cache. SQLite because all of it must survive a crash mid-write and the driver ships with Paper (nothing shaded).
 *
 * <p>Threading: one connection, used only on the single "idcards-db" thread. {@link #write} and {@link #read} return
 * at once with a future; callers hop back to the main thread themselves before touching Bukkit. {@link #load} blocks
 * and is meant for onEnable only.
 */
public final class Database {

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection db) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private final Logger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "idcards-db");
        t.setDaemon(true);
        return t;
    });
    private Connection db;

    public Database(Logger log) {
        this.log = log;
    }

    /** Opens the file and creates the tables. Blocks: called once from onEnable. */
    public void open(File file) throws Exception {
        executor.submit(() -> {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS profiles (uuid TEXT PRIMARY KEY, name TEXT, tex_value TEXT, "
                        + "tex_signature TEXT, updated_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS cards (owner TEXT PRIMARY KEY, card_id TEXT NOT NULL, issued_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS blocks (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, "
                        + "world TEXT NOT NULL, world_uid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, "
                        + "facing TEXT NOT NULL, mount TEXT NOT NULL, name TEXT, placed_by TEXT, placed_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS links (scanner INTEGER NOT NULL, computer INTEGER NOT NULL, PRIMARY KEY (scanner, computer))");
                st.execute("CREATE TABLE IF NOT EXISTS scans (id INTEGER PRIMARY KEY AUTOINCREMENT, computer INTEGER NOT NULL, "
                        + "scanner INTEGER NOT NULL, scanner_name TEXT NOT NULL, owner TEXT, holder TEXT, at INTEGER NOT NULL, "
                        + "granted INTEGER NOT NULL, reason TEXT NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS scans_computer ON scans (computer, id)");
            }
            return null;
        }).get();
    }

    /** Finishes queued writes (up to 10 s) and closes the file. Blocks: called from onDisable. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) log.warning("Database writes did not finish in 10 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (db != null) db.close();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not close the database", e);
        }
    }

    /** Runs on the database thread; failures are logged and complete the future exceptionally. */
    public <T> CompletableFuture<T> read(Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(work.run(db));
                } catch (Exception e) {
                    log.log(Level.WARNING, "Database error", e);
                    future.completeExceptionally(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            future.completeExceptionally(e); // the plugin is shutting down
        }
        return future;
    }

    /** A statement with positional arguments, queued on the database thread. */
    public CompletableFuture<Void> write(String sql, Object... args) {
        return read(db -> {
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                bind(ps, args);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** INSERT that returns the generated row id. Blocks until done: used where the id is needed at once (placing a block). */
    public long insert(String sql, Object... args) throws Exception {
        return read(db -> {
            try (PreparedStatement ps = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, args);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    /** A query, mapped row by row, on the database thread. */
    public <T> CompletableFuture<List<T>> query(String sql, RowMapper<T> mapper, Object... args) {
        return read(db -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapper.map(rs));
                }
            }
            return out;
        });
    }

    /** {@link #query} that blocks. For onEnable only. */
    public <T> List<T> load(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return query(sql, mapper, args).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Could not load from the database: " + sql, e);
            return List.of();
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }
}
