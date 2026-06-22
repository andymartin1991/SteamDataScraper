package rawg;

import common.DataStoreException;
import common.CollectorRunGuard;
import common.CollectorRunGuard.JsonTarget;
import common.CollectorRunGuard.RunSession;
import common.ReleaseDatePolicy;
import common.ReleaseDatePolicy.ReleaseInfo;
import common.ReleaseDateTrackingStore;
import common.ReleaseDateTrackingStore.TrackedItem;
import common.ResilientHttpClient.HttpStatusException;
import common.ResilientHttpClient.RequestInterruptedException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class RAWGReleaseDateUpdater {

    private static final String DB_FILE = "data/db/rawg_raw.sqlite";
    private static final String MIGRATION_KEY = "rawg_release_tracking_v1";
    private static final int UPDATE_LIMIT = Integer.getInteger("release.update.limit", 1_000);
    private static final RAWGApiClient API_CLIENT = new RAWGApiClient();

    private record Candidate(int id, ReleaseInfo info) {
    }

    public static void main(String[] args) {
        RunSession runSession = null;
        try {
            Class.forName("org.sqlite.JDBC");
            setupDatabase();
            runSession = CollectorRunGuard.begin(
                DB_FILE,
                "RAWGReleaseDateUpdater",
                new JsonTarget("rawg_details_data", "game_id", "json_full", "fecha_sync")
            );
            initializeExistingTracking();

            List<TrackedItem> pending = loadDueItems();
            System.out.println("📅 RAWG: " + pending.size() + " fechas pendientes para revisar.");

            int updated = 0;
            for (TrackedItem item : pending) {
                try {
                    String json = API_CLIENT.get(
                        "https://api.rawg.io/api/games/" + item.itemId() + "?key=placeholder"
                    );
                    saveResult(item, json);
                    updated++;
                    sleepOrStop(1_000);
                } catch (HttpStatusException e) {
                    if (e.getStatusCode() == 404) {
                        recordNotFound(item);
                        continue;
                    }
                    throw e;
                }
            }

            System.out.println("✅ RAWGReleaseDateUpdater finalizado. Respuestas actualizadas: " + updated + ".");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("No se encontró el driver JDBC de SQLite", e);
        } catch (RAWGApiClient.ApiKeysExhaustedException e) {
            System.err.println("🛑 " + e.getMessage());
            throw new IllegalStateException("RAWGReleaseDateUpdater detenido por agotamiento de cuota", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("RAWGReleaseDateUpdater finalizó con error", e);
        } finally {
            if (runSession != null) runSession.close();
        }
    }

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            ReleaseDateTrackingStore.ensureSchema(conn);
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_details_data (" +
                "game_id INTEGER PRIMARY KEY, json_full TEXT NOT NULL, " +
                "fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP, json_stores TEXT)");
            if (!hasColumn(conn, "rawg_details_data", "json_stores")) {
                stmt.execute("ALTER TABLE rawg_details_data ADD COLUMN json_stores TEXT");
            }
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo preparar el seguimiento de fechas RAWG", e);
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return true;
            }
            return false;
        }
    }

    private static void initializeExistingTracking() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            if (ReleaseDateTrackingStore.isMigrationComplete(conn, MIGRATION_KEY)) return;

            System.out.println("🔎 RAWG: indexando una sola vez los juegos existentes sin fecha, TBA o futuros...");
            List<Candidate> candidates = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT r.game_id, r.json_data, d.json_full " +
                         "FROM rawg_raw_data r LEFT JOIN rawg_details_data d ON r.game_id=d.game_id")) {
                while (rs.next()) {
                    ReleaseInfo info = ReleaseDatePolicy.fromRawg(rs.getString("json_data"));
                    String jsonDetail = rs.getString("json_full");
                    if (jsonDetail != null && !jsonDetail.contains("\"error\":")) {
                        info = ReleaseDatePolicy.fromRawg(jsonDetail);
                    }
                    if (info.requiresFollowUp()) {
                        candidates.add(new Candidate(rs.getInt("game_id"), info));
                    }
                }
            }

            conn.setAutoCommit(false);
            for (Candidate candidate : candidates) {
                ReleaseDateTrackingStore.recordMigratedCandidate(
                    conn,
                    candidate.id(),
                    candidate.info()
                );
            }
            ReleaseDateTrackingStore.markMigrationComplete(conn, MIGRATION_KEY);
            conn.commit();
            System.out.println("✅ RAWG: " + candidates.size() + " juegos incorporados al seguimiento de fechas.");
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo migrar el seguimiento de fechas RAWG", e);
        }
    }

    private static List<TrackedItem> loadDueItems() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            return ReleaseDateTrackingStore.loadDue(conn, UPDATE_LIMIT);
        } catch (SQLException e) {
            throw new DataStoreException("No se pudieron cargar las fechas pendientes de RAWG", e);
        }
    }

    private static void saveResult(TrackedItem item, String json) {
        CollectorRunGuard.requireCompleteJson(json, "actualización RAWG ID " + item.itemId());
        String sql = "INSERT INTO rawg_details_data(game_id, json_full, fecha_sync) " +
            "VALUES(?,?,CURRENT_TIMESTAMP) ON CONFLICT(game_id) DO UPDATE SET " +
            "json_full=excluded.json_full, fecha_sync=CURRENT_TIMESTAMP";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, item.itemId());
                stmt.setString(2, json);
                stmt.executeUpdate();
            }
            ReleaseDateTrackingStore.recordCheck(
                conn,
                item.itemId(),
                ReleaseDatePolicy.fromRawg(json),
                item.attemptCount()
            );
            conn.commit();
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo actualizar la fecha RAWG " + item.itemId(), e);
        }
    }

    private static void recordNotFound(TrackedItem item) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            ReleaseDateTrackingStore.recordNotFound(conn, item.itemId(), item.attemptCount());
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo registrar RAWG no encontrado " + item.itemId(), e);
        }
    }

    private static void sleepOrStop(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestInterruptedException("RAWGReleaseDateUpdater interrumpido", e);
        }
    }
}
