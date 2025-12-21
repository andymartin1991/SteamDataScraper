import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RAWGRawCollector {

    private static final String API_KEY = "867e9e7e82c3459593e684c2664243bd";
    private static final String DB_FILE = "rawg_raw.sqlite";
    
    // AL QUITAR EL FILTRO DE PLATAFORMAS, TRAEMOS TODO EL CATÁLOGO DE RAWG (Cualquier consola/PC)
    // private static final String PLATFORMS = "4,187,186,7,18,1,5,6,26,24,43"; 
    
    // Si encontramos 1000 juegos seguidos (25 páginas) que ya tenemos Y no han cambiado, paramos.
    // Aumentado de 200 a 1000 para ser más tolerante en escaneos profundos.
    private static final int UMBRAL_PARADA_TEMPRANA = 1000; 

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando RAWGRawCollector (MODO TOTAL: Todo el catálogo)...");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                System.err.println("❌ ERROR CRÍTICO: No se encontró el driver JDBC de SQLite.");
                return;
            }

            setupDatabase();

            // Ahora cargamos un mapa ID -> FechaActualización para detectar cambios
            Map<Integer, String> juegosYaProcesados = cargarJuegosYaProcesados();
            int totalEnBD = juegosYaProcesados.size();
            System.out.println("📚 Base de datos: " + totalEnBD + " juegos ya registrados.");
            
            descargarJuegos(juegosYaProcesados, totalEnBD);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void descargarJuegos(Map<Integer, String> juegosProcesados, int totalEnBD) {
        int page = 1;
        boolean hayMasDatos = true;
        int totalJuegosAPI = -1;
        int procesadosEnSesion = 0;
        int guardadosEnSesion = 0;
        int consecutivosSinNovedad = 0;
        
        // Si tenemos menos del 10% del catálogo estimado (aprox 900k), desactivamos la parada temprana
        // para forzar un llenado inicial masivo.
        boolean modoLlenadoMasivo = false;

        System.out.println("☁️ Conectando a RAWG (Todas las plataformas | Orden: Recientes)...");

        while (hayMasDatos) {
            try {
                // SIN PARAMETRO PLATFORMS = TODO EL CATÁLOGO
                String urlString = "https://api.rawg.io/api/games?key=" + API_KEY + 
                                   "&ordering=-updated" + 
                                   "&page_size=40&page=" + page;

                String jsonResponse = peticionHttpConReintentoInfinito(urlString);

                if (jsonResponse == null) {
                    System.out.println("⚠️ Error fatal en página " + page + ". Abortando.");
                    break;
                }
                
                if (totalJuegosAPI == -1) {
                    totalJuegosAPI = extraerCount(jsonResponse);
                    if (totalJuegosAPI != -1) {
                        System.out.println("📊 Total en API: " + totalJuegosAPI);
                        
                        // Chequeo de Modo Llenado Masivo
                        if (totalEnBD < (totalJuegosAPI * 0.1)) { // Si tenemos menos del 10%
                            modoLlenadoMasivo = true;
                            System.out.println("🚨 MODO LLENADO MASIVO ACTIVADO: Se ignorará la parada temprana hasta tener una base sólida.");
                        }
                    }
                }

                String resultsArray = extraerArrayResults(jsonResponse);
                
                if (resultsArray == null || resultsArray.isEmpty() || resultsArray.equals("[]")) {
                    hayMasDatos = false;
                    break;
                }

                List<String> juegosJson = separarObjetosJson(resultsArray);
                int novedadesEnPagina = 0;
                
                for (String juegoJson : juegosJson) {
                    procesadosEnSesion++;
                    
                    int gameId = extraerIdDelJuego(juegoJson);
                    String fechaUpdateNueva = extraerFechaUpdate(juegoJson);
                    
                    if (gameId != -1) {
                        boolean esNuevo = !juegosProcesados.containsKey(gameId);
                        boolean esActualizacion = false;

                        if (!esNuevo) {
                            String fechaUpdateGuardada = juegosProcesados.get(gameId);
                            // Si la fecha nueva es distinta (asumimos más reciente por el orden de la API), actualizamos
                            if (fechaUpdateNueva != null && !fechaUpdateNueva.equals(fechaUpdateGuardada)) {
                                esActualizacion = true;
                            }
                        }

                        if (esNuevo || esActualizacion) {
                            // ES NUEVO O ACTUALIZADO -> GUARDAR
                            guardarJuego(gameId, juegoJson);
                            juegosProcesados.put(gameId, fechaUpdateNueva); // Actualizamos memoria
                            
                            guardadosEnSesion++;
                            novedadesEnPagina++;
                            consecutivosSinNovedad = 0; // Reset contador
                            
                            if (esActualizacion) {
                                System.out.println("   -> 🔄 Actualizado ID " + gameId + " (Fecha antigua: " + juegosProcesados.get(gameId) + " -> Nueva: " + fechaUpdateNueva + ")");
                            }
                        } else {
                            // YA LO TENEMOS Y NO HA CAMBIADO
                            consecutivosSinNovedad++;
                        }
                    }
                }

                String estadoModo = modoLlenadoMasivo ? "[MASIVO - NO STOP]" : "[NORMAL]";
                String progreso = String.format("🚀 Pág %d %s | Nuevos/Upd: %d | Sin Cambios Seguidos: %d/%d", 
                                                page, estadoModo, novedadesEnPagina, consecutivosSinNovedad, UMBRAL_PARADA_TEMPRANA);
                System.out.println(progreso);

                // LÓGICA DE PARADA TEMPRANA (Solo si NO estamos en modo masivo)
                if (!modoLlenadoMasivo && consecutivosSinNovedad >= UMBRAL_PARADA_TEMPRANA) {
                    System.out.println("✅ Se alcanzó el umbral de parada temprana. El resto de juegos ya están actualizados.");
                    System.out.println("   (Últimos " + consecutivosSinNovedad + " juegos ya existían sin cambios)");
                    hayMasDatos = false;
                    break;
                }

                if (!jsonResponse.contains("\"next\":\"http")) {
                    hayMasDatos = false;
                }

                page++;
                Thread.sleep(250); 

            } catch (Exception e) {
                System.err.println("❌ Error: " + e.getMessage());
            }
        }
        
        System.out.println("🏁 Proceso finalizado.");
        System.out.println("   -> Total procesados: " + procesadosEnSesion);
        System.out.println("   -> Nuevos/Actualizados guardados: " + guardadosEnSesion);
    }

    // --- MÉTODOS AUXILIARES ---
    
    private static int extraerCount(String json) {
        Pattern p = Pattern.compile("\"count\":(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) return Integer.parseInt(m.group(1));
        return -1;
    }
    
    private static String extraerArrayResults(String fullJson) {
        int startIndex = fullJson.indexOf("\"results\":[");
        if (startIndex == -1) return null;
        startIndex += "\"results\":".length();
        int balance = 0;
        for (int i = startIndex; i < fullJson.length(); i++) {
            char c = fullJson.charAt(i);
            if (c == '[') balance++;
            if (c == ']') {
                balance--;
                if (balance == 0) return fullJson.substring(startIndex, i + 1);
            }
        }
        return null;
    }

    private static List<String> separarObjetosJson(String jsonArray) {
        List<String> lista = new ArrayList<>();
        if (jsonArray == null || !jsonArray.startsWith("[")) return lista;
        String content = jsonArray.trim().substring(1, jsonArray.length() - 1);
        int balance = 0;
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') balance++;
            if (c == '}') balance--;
            buffer.append(c);
            if (balance == 0 && c == '}') {
                lista.add(buffer.toString());
                buffer.setLength(0);
            } else if (balance == 0 && c == ',') {
                if (buffer.length() > 0 && buffer.charAt(0) == ',') buffer.setLength(0); 
            }
        }
        return lista;
    }

    private static int extraerIdDelJuego(String json) {
        // Intentamos buscar ID cerca de "updated" primero para mayor precisión en el contexto
        Pattern pContext = Pattern.compile("\"updated\":\"[^\"]+\",\"id\":(\\d+)");
        Matcher mContext = pContext.matcher(json);
        if (mContext.find()) return Integer.parseInt(mContext.group(1));
        
        // Fallback genérico
        Pattern p = Pattern.compile("\"id\":(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) return Integer.parseInt(m.group(1));
        
        return -1;
    }
    
    private static String extraerFechaUpdate(String json) {
        Pattern p = Pattern.compile("\"updated\":\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout=5000;");
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_raw_data (" +
                         "game_id INTEGER PRIMARY KEY, " +
                         "json_data TEXT NOT NULL, " +
                         "fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_ignored_ids (" +
                         "game_id INTEGER PRIMARY KEY)");
        } catch (Exception e) {
            System.err.println("❌ Error fatal DB: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Map<Integer, String> cargarJuegosYaProcesados() {
        Map<Integer, String> juegos = new HashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            
            // Ignorados (no nos interesa su fecha, solo que existen para no procesarlos si estuvieran en raw_data)
            // Aunque en este diseño, rawg_ignored_ids parece usarse para exclusiones manuales.
            // Lo mantenemos por compatibilidad, asignando fecha null o dummy.
            ResultSet rsIgnored = stmt.executeQuery("SELECT game_id FROM rawg_ignored_ids");
            while (rsIgnored.next()) juegos.put(rsIgnored.getInt("game_id"), "IGNORED");
            
            ResultSet rsGames = stmt.executeQuery("SELECT game_id, json_data FROM rawg_raw_data");
            
            while (rsGames.next()) {
                int id = rsGames.getInt("game_id");
                String json = rsGames.getString("json_data");
                String fechaUpdate = extraerFechaUpdate(json);
                
                // Si no tiene fecha update, ponemos una muy antigua
                if (fechaUpdate == null) fechaUpdate = "1970-01-01T00:00:00";
                
                juegos.put(id, fechaUpdate);
            }
        } catch (Exception e) {}
        return juegos;
    }

    private static void guardarJuego(int gameId, String json) {
        String sql = "INSERT OR REPLACE INTO rawg_raw_data(game_id, json_data) VALUES(?,?)";
        int intentos = 0;
        while (intentos < 3) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, gameId);
                pstmt.setString(2, json);
                pstmt.executeUpdate();
                return;
            } catch (Exception e) {
                if (e.getMessage().contains("locked")) {
                    intentos++;
                    try { Thread.sleep(100); } catch (InterruptedException ie) {}
                } else {
                    System.err.println("⚠️ Error guardando juego " + gameId + ": " + e.getMessage());
                    return;
                }
            }
        }
    }
    
    private static String peticionHttpConReintentoInfinito(String urlString) {
        int intentos = 0;
        
        while (true) { // Bucle infinito hasta éxito o error fatal
            try {
                return peticionHttp(urlString);
            } catch (Exception e) {
                intentos++;
                System.err.println("⚠️ Error HTTP (Intento " + intentos + "): " + e.getMessage());
                
                if (e.getMessage().contains("502") || e.getMessage().contains("500") || e.getMessage().contains("504") || e.getMessage().contains("429")) {
                    System.out.println("⏳ Servidor saturado o Rate Limit. Esperando 60s y reintentando...");
                    try { Thread.sleep(60000); } catch (InterruptedException ie) {}
                } else {
                    // Errores desconocidos (ej. timeout local), esperamos un poco menos
                    System.out.println("⏳ Error de conexión. Esperando 10s...");
                    try { Thread.sleep(10000); } catch (InterruptedException ie) {}
                }
            }
        }
    }

    private static String peticionHttp(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "SteamDataScraper/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) content.append(line);
                    return content.toString();
                }
            } else {
                throw new Exception("HTTP Code " + code);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}