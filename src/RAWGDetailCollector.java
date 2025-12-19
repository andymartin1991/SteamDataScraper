import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class RAWGDetailCollector {

    private static final String API_KEY = "867e9e7e82c3459593e684c2664243bd";
    private static final String DB_FILE = "rawg_raw.sqlite";

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando RAWG Detail Collector (Descargando Fichas Completas)...");

            Class.forName("org.sqlite.JDBC");
            setupDatabase();

            List<Integer> pendientes = obtenerJuegosPendientes();
            System.out.println("📋 Juegos pendientes de detalles: " + pendientes.size());

            int procesados = 0;
            for (Integer gameId : pendientes) {
                try {
                    String jsonDetalle = descargarDetalleJuego(gameId);
                    
                    if (jsonDetalle != null) {
                        guardarDetalle(gameId, jsonDetalle);
                        procesados++;
                        System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Detalle guardado: ID " + gameId);
                    } else {
                        System.err.println("⚠️ No se pudo bajar detalle para ID " + gameId);
                    }

                    // Respetar Rate Limit (importante en llamadas detalle)
                    Thread.sleep(600); 

                } catch (Exception e) {
                    System.err.println("❌ Error en ID " + gameId + ": " + e.getMessage());
                }
            }
            
            System.out.println("🏁 Proceso de detalles finalizado.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            // Tabla separada para los detalles pesados
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_details_data (" +
                         "game_id INTEGER PRIMARY KEY, " +
                         "json_full TEXT NOT NULL, " +
                         "fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<Integer> obtenerJuegosPendientes() {
        List<Integer> ids = new ArrayList<>();
        // Seleccionamos IDs que están en la lista RAW pero NO en la tabla de detalles
        String sql = "SELECT r.game_id FROM rawg_raw_data r " +
                     "LEFT JOIN rawg_details_data d ON r.game_id = d.game_id " +
                     "WHERE d.game_id IS NULL";
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                ids.add(rs.getInt("game_id"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ids;
    }

    private static String descargarDetalleJuego(int gameId) {
        // Endpoint de detalle: /games/{id}
        String urlString = "https://api.rawg.io/api/games/" + gameId + "?key=" + API_KEY;
        return peticionHttpConReintento(urlString);
    }

    private static void guardarDetalle(int gameId, String json) {
        String sql = "INSERT OR REPLACE INTO rawg_details_data(game_id, json_full) VALUES(?,?)";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, gameId);
            pstmt.setString(2, json);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String peticionHttpConReintento(String urlString) {
        int intentos = 0;
        while (intentos < 3) {
            try {
                return peticionHttp(urlString);
            } catch (Exception e) {
                intentos++;
                System.err.println("⚠️ Error HTTP (Intento " + intentos + "): " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            }
        }
        return null;
    }

    private static String peticionHttp(String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "SteamDataScraper/1.0");
        
        int code = conn.getResponseCode();
        if (code == 200) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) content.append(line);
                return content.toString();
            }
        } else {
            throw new Exception("HTTP " + code);
        }
    }
}