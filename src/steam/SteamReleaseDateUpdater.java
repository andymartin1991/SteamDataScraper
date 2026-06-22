package steam;

import common.DataStoreException;
import common.CollectorRunGuard;
import common.CollectorRunGuard.JsonTarget;
import common.CollectorRunGuard.RunSession;
import common.ReleaseDatePolicy;
import common.ReleaseDatePolicy.ReleaseInfo;
import common.ReleaseDateTrackingStore;
import common.ReleaseDateTrackingStore.TrackedItem;
import common.ResilientHttpClient;
import common.ResilientHttpClient.RequestInterruptedException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SteamReleaseDateUpdater {

    private static final String DB_FILE = "data/db/steam_raw.sqlite";
    private static final String MIGRATION_KEY = "steam_release_tracking_v1";
    private static final int UPDATE_LIMIT = Integer.getInteger("release.update.limit", 1_000);

    private record Candidate(int id, ReleaseInfo info) {
    }

    public static void main(String[] args) {
        RunSession runSession = null;
        try {
            Class.forName("org.sqlite.JDBC");
            setupDatabase();
            runSession = CollectorRunGuard.begin(
                DB_FILE,
                "SteamReleaseDateUpdater",
                new JsonTarget("steam_raw_data", "app_id", "json_data", "fecha_sync")
            );
            initializeExistingTracking();

            List<TrackedItem> pending = loadDueItems();
            System.out.println("📅 Steam: " + pending.size() + " fechas pendientes para revisar.");

            int updated = 0;
            for (TrackedItem item : pending) {
                String json = ResilientHttpClient.get(
                    "https://store.steampowered.com/api/appdetails?appids=" + item.itemId() + "&l=english&cc=us"
                );

                if (json == null || json.isBlank() || json.contains("\"success\":false")) {
                    recordNotFound(item);
                } else {
                    saveResult(item, json);
                    updated++;
                }
                sleepOrStop(1_500);
            }

            System.out.println("✅ SteamReleaseDateUpdater finalizado. Respuestas actualizadas: " + updated + ".");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("No se encontró el driver JDBC de SQLite", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("SteamReleaseDateUpdater finalizó con error", e);
        } finally {
            if (runSession != null) runSession.close();
        }
    }

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            ReleaseDateTrackingStore.ensureSchema(conn);
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo preparar el seguimiento de fechas Steam", e);
        }
    }

    private static void initializeExistingTracking() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            if (ReleaseDateTrackingStore.isMigrationComplete(conn, MIGRATION_KEY)) return;

            System.out.println("🔎 Steam: indexando una sola vez los juegos existentes sin fecha, TBA o futuros...");
            List<Candidate> candidates = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT app_id, json_data FROM steam_raw_data")) {
                while (rs.next()) {
                    ReleaseInfo info = ReleaseDatePolicy.fromSteam(rs.getString("json_data"));
                    if (info.requiresFollowUp()) {
                        candidates.add(new Candidate(rs.getInt("app_id"), info));
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
            System.out.println("✅ Steam: " + candidates.size() + " juegos incorporados al seguimiento de fechas.");
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo migrar el seguimiento de fechas Steam", e);
        }
    }

    private static List<TrackedItem> loadDueItems() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            return ReleaseDateTrackingStore.loadDue(conn, UPDATE_LIMIT);
        } catch (SQLException e) {
            throw new DataStoreException("No se pudieron cargar las fechas pendientes de Steam", e);
        }
    }

    private static void saveResult(TrackedItem item, String json) {
        CollectorRunGuard.requireCompleteJson(json, "actualización Steam AppID " + item.itemId());
        String sql = "INSERT OR REPLACE INTO steam_raw_data(app_id, json_data) VALUES(?,?)";
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
                ReleaseDatePolicy.fromSteam(json),
                item.attemptCount()
            );
            conn.commit();
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo actualizar la fecha Steam " + item.itemId(), e);
        }
    }

    private static void recordNotFound(TrackedItem item) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            ReleaseDateTrackingStore.recordNotFound(conn, item.itemId(), item.attemptCount());
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo registrar Steam no encontrado " + item.itemId(), e);
        }
    }

    private static void sleepOrStop(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestInterruptedException("SteamReleaseDateUpdater interrumpido", e);
        }
    }
}
