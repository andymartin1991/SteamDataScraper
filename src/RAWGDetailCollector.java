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

    // Clase auxiliar para saber qué trabajo necesita cada juego
    private static class GameTask {
        int id;
        boolean tieneDetalle;
        boolean esError404;

        public GameTask(int id, boolean tieneDetalle, boolean esError404) {
            this.id = id;
            this.tieneDetalle = tieneDetalle;
            this.esError404 = esError404;
        }
    }

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando RAWG Detail Collector (Modo Inteligente: Detalle + Stores)...");

            Class.forName("org.sqlite.JDBC");
            setupDatabase();

            List<GameTask> pendientes = obtenerTareasPendientes();
            System.out.println("📋 Juegos pendientes de procesar: " + pendientes.size());

            int procesados = 0;
            for (GameTask tarea : pendientes) {
                try {
                    // CASO 1: El juego ya fue marcado como error 404 anteriormente.
                    // Solo actualizamos stores a vacío para que deje de salir en pendientes.
                    if (tarea.esError404) {
                        System.out.println("⏩ ID " + tarea.id + " es un error conocido. Actualizando stores a vacío.");
                        actualizarStores(tarea.id, "[]");
                        procesados++;
                        continue;
                    }

                    // CASO 2: Ya tenemos el detalle, SOLO falta descargar Stores (Backfill)
                    if (tarea.tieneDetalle) {
                        String jsonStores = descargarStoresJuego(tarea.id);
                        if (jsonStores == null || "404".equals(jsonStores)) {
                            jsonStores = "[]";
                        }
                        actualizarStores(tarea.id, jsonStores);
                        procesados++;
                        System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Stores actualizados (Detalle ya existía): ID " + tarea.id);
                        
                        Thread.sleep(600); // Rate limit más suave solo para stores
                    } 
                    // CASO 3: No tenemos nada, descargar TODO (Juego nuevo)
                    else {
                        String jsonDetalle = descargarDetalleJuego(tarea.id);

                        if ("404".equals(jsonDetalle)) {
                            System.err.println("⚠️ ID " + tarea.id + " no encontrado (404). Marcando error.");
                            guardarNuevoCompleto(tarea.id, "{\"error\":\"404_not_found\"}", "[]");
                            continue;
                        }

                        if (jsonDetalle != null) {
                            String jsonStores = descargarStoresJuego(tarea.id);
                            if (jsonStores == null || "404".equals(jsonStores)) {
                                jsonStores = "[]";
                            }

                            guardarNuevoCompleto(tarea.id, jsonDetalle, jsonStores);
                            procesados++;
                            System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Detalle y Stores descargados: ID " + tarea.id);
                        } else {
                            System.err.println("⚠️ No se pudo bajar detalle para ID " + tarea.id);
                        }
                        
                        Thread.sleep(600); // Rate limit completo
                    }

                } catch (Exception e) {
                    System.err.println("❌ Error en ID " + tarea.id + ": " + e.getMessage());
                }
            }
            
            System.out.println("🏁 Proceso finalizado.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_details_data (" +
                         "game_id INTEGER PRIMARY KEY, " +
                         "json_full TEXT NOT NULL, " +
                         "fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(rawg_details_data);");
            boolean storesColumnExists = false;
            while (rs.next()) {
                if ("json_stores".equals(rs.getString("name"))) {
                    storesColumnExists = true;
                    break;
                }
            }
            rs.close();

            if (!storesColumnExists) {
                stmt.execute("ALTER TABLE rawg_details_data ADD COLUMN json_stores TEXT;");
                System.out.println("ℹ️ Columna 'json_stores' añadida.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<GameTask> obtenerTareasPendientes() {
        List<GameTask> tareas = new ArrayList<>();
        // Seleccionamos juegos que:
        // 1. No están en details (d.game_id IS NULL) -> Caso Nuevo
        // 2. Están en details pero no tienen stores (d.json_stores IS NULL) -> Caso Update
        String sql = "SELECT r.game_id, " +
                     "CASE WHEN d.json_full IS NOT NULL THEN 1 ELSE 0 END as tiene_detalle, " +
                     "CASE WHEN d.json_full LIKE '%\"error\":\"404_not_found\"%' THEN 1 ELSE 0 END as es_error " +
                     "FROM rawg_raw_data r " +
                     "LEFT JOIN rawg_details_data d ON r.game_id = d.game_id " +
                     "WHERE d.game_id IS NULL OR d.json_stores IS NULL";
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                tareas.add(new GameTask(
                    rs.getInt("game_id"),
                    rs.getBoolean("tiene_detalle"),
                    rs.getBoolean("es_error")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tareas;
    }

    private static String descargarDetalleJuego(int gameId) {
        String urlString = "https://api.rawg.io/api/games/" + gameId + "?key=" + API_KEY;
        return peticionHttpConReintento(urlString);
    }

    private static String descargarStoresJuego(int gameId) {
        String urlString = "https://api.rawg.io/api/games/" + gameId + "/stores?key=" + API_KEY;
        return peticionHttpConReintento(urlString);
    }

    // Inserta todo de cero (INSERT OR REPLACE)
    private static void guardarNuevoCompleto(int gameId, String jsonDetail, String jsonStores) {
        String sql = "INSERT OR REPLACE INTO rawg_details_data(game_id, json_full, json_stores) VALUES(?,?,?)";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, gameId);
            pstmt.setString(2, jsonDetail);
            pstmt.setString(3, jsonStores);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Solo actualiza la columna stores (UPDATE)
    private static void actualizarStores(int gameId, String jsonStores) {
        String sql = "UPDATE rawg_details_data SET json_stores = ? WHERE game_id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, jsonStores);
            pstmt.setInt(2, gameId);
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
                if (e.getMessage().contains("HTTP 404")) {
                    return "404";
                }
                intentos++;
                System.err.println("⚠️ Error HTTP (Intento " + intentos + "): " + e.getMessage() + " para URL: " + urlString);
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
