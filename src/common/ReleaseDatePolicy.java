package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public final class ReleaseDatePolicy {

    public enum Status {
        MISSING,
        TBA,
        FUTURE,
        RELEASED,
        NOT_FOUND
    }

    public record ReleaseInfo(Status status, LocalDate releaseDate) {
        public boolean requiresFollowUp() {
            return status == Status.MISSING || status == Status.TBA || status == Status.FUTURE;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<DateTimeFormatter> STEAM_DATE_FORMATS = List.of(
        formatter("d MMM, uuuu"),
        formatter("MMM d, uuuu"),
        formatter("d MMM uuuu"),
        formatter("MMM d uuuu"),
        DateTimeFormatter.ISO_LOCAL_DATE
    );
    private static final int[] UNKNOWN_DATE_BACKOFF_DAYS = {7, 14, 30, 60, 90};

    private ReleaseDatePolicy() {
    }

    public static ReleaseInfo fromSteam(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode appNode = root.elements().hasNext() ? root.elements().next() : root;
            JsonNode data = appNode.path("data");
            JsonNode release = data.path("release_date");
            boolean comingSoon = release.path("coming_soon").asBoolean(false);
            String rawDate = release.path("date").asText("").trim();
            LocalDate parsedDate = parseSteamDate(rawDate);

            if (comingSoon) {
                if (parsedDate != null && parsedDate.isAfter(LocalDate.now())) {
                    return new ReleaseInfo(Status.FUTURE, parsedDate);
                }
                return new ReleaseInfo(Status.TBA, parsedDate);
            }
            if (parsedDate == null) {
                return new ReleaseInfo(Status.MISSING, null);
            }
            return new ReleaseInfo(
                parsedDate.isAfter(LocalDate.now()) ? Status.FUTURE : Status.RELEASED,
                parsedDate
            );
        } catch (IOException | RuntimeException e) {
            return new ReleaseInfo(Status.MISSING, null);
        }
    }

    public static ReleaseInfo fromRawg(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            boolean tba = root.path("tba").asBoolean(false);
            String rawDate = root.path("released").asText("").trim();
            LocalDate parsedDate = parseIsoDate(rawDate);

            if (tba) {
                return new ReleaseInfo(Status.TBA, parsedDate);
            }
            if (parsedDate == null) {
                return new ReleaseInfo(Status.MISSING, null);
            }
            return new ReleaseInfo(
                parsedDate.isAfter(LocalDate.now()) ? Status.FUTURE : Status.RELEASED,
                parsedDate
            );
        } catch (IOException | RuntimeException e) {
            return new ReleaseInfo(Status.MISSING, null);
        }
    }

    public static LocalDateTime nextCheckAt(ReleaseInfo info, int attemptCount, LocalDateTime now) {
        if (info.status() == Status.RELEASED) {
            return null;
        }
        if (info.status() == Status.NOT_FOUND) {
            return now.plusDays(180);
        }
        if (info.status() == Status.FUTURE && info.releaseDate() != null) {
            long daysUntilRelease = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), info.releaseDate());
            if (daysUntilRelease > 90) return now.plusDays(30);
            if (daysUntilRelease > 30) return now.plusDays(14);
            if (daysUntilRelease > 7) return now.plusDays(7);
            return now.plusDays(1);
        }

        int index = Math.min(Math.max(attemptCount, 0), UNKNOWN_DATE_BACKOFF_DAYS.length - 1);
        return now.plusDays(UNKNOWN_DATE_BACKOFF_DAYS[index]);
    }

    private static LocalDate parseSteamDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) return null;
        String normalized = rawDate.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("tba") || lower.contains("coming") || lower.contains("to be announced")) {
            return null;
        }
        for (DateTimeFormatter formatter : STEAM_DATE_FORMATS) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // Probar el siguiente formato conocido de Steam.
            }
        }
        return null;
    }

    private static LocalDate parseIsoDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) return null;
        try {
            return LocalDate.parse(rawDate, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .toFormatter(Locale.ENGLISH);
    }
}
