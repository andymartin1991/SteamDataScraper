package common;

import common.ReleaseDatePolicy.ReleaseInfo;
import common.ReleaseDatePolicy.Status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class ReleaseDateTrackingStore {

    public record TrackedItem(int itemId, Status status, LocalDate releaseDate, int attemptCount) {
    }

    private ReleaseDateTrackingStore() {
    }

    public static void ensureSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS release_date_tracking (" +
                "item_id INTEGER PRIMARY KEY, " +
                "status TEXT NOT NULL, " +
                "release_date TEXT, " +
                "first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "last_checked_at TIMESTAMP, " +
                "next_check_at TIMESTAMP, " +
                "attempt_count INTEGER NOT NULL DEFAULT 0)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_release_tracking_due " +
                "ON release_date_tracking(status, next_check_at)");
            stmt.execute("CREATE TABLE IF NOT EXISTS release_tracking_meta (" +
                "meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
        }
    }

    public static void recordCollectorObservation(
        Connection conn,
        int itemId,
        ReleaseInfo info
    ) throws SQLException {
        if (!info.requiresFollowUp()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE release_date_tracking SET status = ?, release_date = ?, next_check_at = NULL " +
                    "WHERE item_id = ?")) {
                bindInfo(stmt, info, 1);
                stmt.setInt(3, itemId);
                stmt.executeUpdate();
            }
            return;
        }

        LocalDateTime nextCheck = ReleaseDatePolicy.nextCheckAt(info, 0, LocalDateTime.now());
        String sql = "INSERT INTO release_date_tracking(" +
            "item_id, status, release_date, next_check_at) VALUES(?,?,?,?) " +
            "ON CONFLICT(item_id) DO UPDATE SET " +
            "status=excluded.status, release_date=excluded.release_date, " +
            "next_check_at=CASE " +
            "WHEN release_date_tracking.status='RELEASED' THEN excluded.next_check_at " +
            "WHEN release_date_tracking.next_check_at IS NULL THEN excluded.next_check_at " +
            "WHEN excluded.next_check_at < release_date_tracking.next_check_at THEN excluded.next_check_at " +
            "ELSE release_date_tracking.next_check_at END";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, itemId);
            bindInfo(stmt, info, 2);
            stmt.setTimestamp(4, Timestamp.valueOf(nextCheck));
            stmt.executeUpdate();
        }
    }

    public static void recordMigratedCandidate(
        Connection conn,
        int itemId,
        ReleaseInfo info
    ) throws SQLException {
        if (!info.requiresFollowUp()) return;
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT OR IGNORE INTO release_date_tracking(" +
                "item_id, status, release_date, next_check_at) VALUES(?,?,?,CURRENT_TIMESTAMP)")) {
            stmt.setInt(1, itemId);
            bindInfo(stmt, info, 2);
            stmt.executeUpdate();
        }
    }

    public static List<TrackedItem> loadDue(Connection conn, int limit) throws SQLException {
        List<TrackedItem> items = new ArrayList<>();
        String sql = "SELECT item_id, status, release_date, attempt_count " +
            "FROM release_date_tracking " +
            "WHERE status IN ('MISSING','TBA','FUTURE','NOT_FOUND') " +
            "AND (next_check_at IS NULL OR next_check_at <= CURRENT_TIMESTAMP) " +
            "ORDER BY COALESCE(next_check_at, '1970-01-01 00:00:00'), item_id LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String releaseDate = rs.getString("release_date");
                    items.add(new TrackedItem(
                        rs.getInt("item_id"),
                        Status.valueOf(rs.getString("status")),
                        releaseDate == null ? null : LocalDate.parse(releaseDate),
                        rs.getInt("attempt_count")
                    ));
                }
            }
        }
        return items;
    }

    public static void recordCheck(
        Connection conn,
        int itemId,
        ReleaseInfo info,
        int previousAttemptCount
    ) throws SQLException {
        int newAttemptCount = previousAttemptCount + 1;
        LocalDateTime nextCheck = ReleaseDatePolicy.nextCheckAt(info, newAttemptCount, LocalDateTime.now());
        try (PreparedStatement stmt = conn.prepareStatement(
            "UPDATE release_date_tracking SET status=?, release_date=?, last_checked_at=CURRENT_TIMESTAMP, " +
                "next_check_at=?, attempt_count=? WHERE item_id=?")) {
            bindInfo(stmt, info, 1);
            if (nextCheck == null) stmt.setTimestamp(3, null);
            else stmt.setTimestamp(3, Timestamp.valueOf(nextCheck));
            stmt.setInt(4, newAttemptCount);
            stmt.setInt(5, itemId);
            stmt.executeUpdate();
        }
    }

    public static void recordNotFound(Connection conn, int itemId, int previousAttemptCount) throws SQLException {
        recordCheck(conn, itemId, new ReleaseInfo(Status.NOT_FOUND, null), previousAttemptCount);
    }

    public static boolean isMigrationComplete(Connection conn, String migrationKey) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT 1 FROM release_tracking_meta WHERE meta_key=?")) {
            stmt.setString(1, migrationKey);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void markMigrationComplete(Connection conn, String migrationKey) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT OR REPLACE INTO release_tracking_meta(meta_key, meta_value) " +
                "VALUES(?, CURRENT_TIMESTAMP)")) {
            stmt.setString(1, migrationKey);
            stmt.executeUpdate();
        }
    }

    private static void bindInfo(PreparedStatement stmt, ReleaseInfo info, int startIndex) throws SQLException {
        stmt.setString(startIndex, info.status().name());
        stmt.setString(startIndex + 1, info.releaseDate() == null ? null : info.releaseDate().toString());
    }
}
