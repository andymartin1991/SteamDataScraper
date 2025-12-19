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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RAWGRawCollector {

    private static final String API_KEY = "867e9e7e82c3459593e684c2664243bd";
    private static final String DB_FILE = "rawg_raw.sqlite";
    
    private static final String PLATFORMS = "187,186,7"; 
    
    // Si encontramos 200 juegos seguidos (5 páginas) que ya tenemos, paramos.
    private static final int UMBRAL_PARADA_TEMPRANA = 200; 

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando RAWGRawCollector (Optimizado: Orden por Actualización + Parada Temprana)...");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                System.err.println("❌ ERROR CRÍTICO: No se encontró el driver JDBC de SQLite.");
                return;
            }

            setupDatabase();

            Set<Integer> idsYaProcesados = cargarIdsYaProcesados();
            System.out.println("📚 Base de datos: " + idsYaProcesados.size() + " juegos ya registrados.");
            
            descargarJuegos(idsYaProcesados);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void descargarJuegos(Set<Integer> idsProcesados) {
        int page = 1;
        boolean hayMasDatos = true;
        int totalJuegosAPI = -1;
        int procesadosEnSesion = 0;
        int guardadosEnSesion = 0;
        int consecutivosSinNovedad = 0;

        System.out.println("☁️ Conectando a RAWG (Plataformas: " + PLATFORMS + " | Orden: Recientes)...");

        while (hayMasDatos) {
            try {
                // AÑADIDO: ordering=-updated para traer primero lo último modificado/creado
                String urlString = "https://api.rawg.io/api/games?key=" + API_KEY + 
                                   "&platforms=" + PLATFORMS + 
                                   "&ordering=-updated" + 
                                   "&page_size=40&page=" + page;

                String jsonResponse = peticionHttpConReintento(urlString);

                if (jsonResponse == null) {
                    System.out.println("⚠️ Error fatal en página " + page + ". Abortando.");
                    break;
                }
                
                if (totalJuegosAPI == -1) {
                    totalJuegosAPI = extraerCount(jsonResponse);
                    if (totalJuegosAPI != -1) {
                        System.out.println("📊 Total en API: " + totalJuegosAPI);
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
                    
                    if (gameId != -1) {
                        if (!idsProcesados.contains(gameId)) {
                            // ES NUEVO O ACTUALIZADO
                            guardarJuego(gameId, juegoJson);
                            idsProcesados.add(gameId); // Lo añadimos para no repetirlo
                            guardadosEnSesion++;
                            novedadesEnPagina++;
                            consecutivosSinNovedad = 0; // Reset contador
                        } else {
                            // YA LO TENEMOS
                            consecutivosSinNovedad++;
                        }
                    }
                }

                String progreso = String.format("🚀 Pág %d | Nuevos: %d | Sin Novedad Seguidos: %d/%d", 
                                                page, novedadesEnPagina, consecutivosSinNovedad, UMBRAL_PARADA_TEMPRANA);
                System.out.println(progreso);

                // LÓGICA DE PARADA TEMPRANA
                if (consecutivosSinNovedad >= UMBRAL_PARADA_TEMPRANA) {
                    System.out.println("✅ Se alcanzó el umbral de parada temprana. El resto de juegos ya están actualizados.");
                    System.out.println("   (Últimos " + consecutivosSinNovedad + " juegos ya existían en DB)");
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

    // --- MÉTODOS AUXILIARES (Sin cambios) ---
    
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
        Pattern pContext = Pattern.compile("\"updated\":\"[^\"]+\",\"id\":(\\d+)");
        Matcher mContext = pContext.matcher(json);
        if (mContext.find()) return Integer.parseInt(mContext.group(1));
        
        int lastId = -1;
        Pattern p = Pattern.compile("\"id\":(\\d+)");
        Matcher m = p.matcher(json);
        while(m.find()){
            lastId = Integer.parseInt(m.group(1));
        }
        return lastId;
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

    private static Set<Integer> cargarIdsYaProcesados() {
        Set<Integer> ids = new HashSet<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            ResultSet rsIgnored = stmt.executeQuery("SELECT game_id FROM rawg_ignored_ids");
            while (rsIgnored.next()) ids.add(rsIgnored.getInt("game_id"));
            
            ResultSet rsGames = stmt.executeQuery("SELECT game_id, json_data FROM rawg_raw_data");
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            LocalDate hoy = LocalDate.now();

            while (rsGames.next()) {
                int id = rsGames.getInt("game_id");
                String json = rsGames.getString("json_data");
                boolean esFinalizado = false;

                if (json.contains("\"tba\":true")) {
                    esFinalizado = false; 
                } else {
                    Pattern p = Pattern.compile("\"released\":\"(\\d{4}-\\d{2}-\\d{2})\"");
                    Matcher m = p.matcher(json);
                    if (m.find()) {
                        try {
                            LocalDate fechaLanzamiento = LocalDate.parse(m.group(1), dtf);
                            if (fechaLanzamiento.isBefore(hoy)) esFinalizado = true; 
                        } catch (Exception e) {}
                    }
                }
                if (esFinalizado) ids.add(id);
            }
        } catch (Exception e) {}
        return ids;
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
    
    private static String peticionHttpConReintento(String urlString) {
        int intentos = 0;
        while (intentos < 3) {
            try {
                return peticionHttp(urlString);
            } catch (Exception e) {
                intentos++;
                System.err.println("⚠️ Error HTTP (Intento " + intentos + "/3): " + e.getMessage());
                if (e.getMessage().contains("502") || e.getMessage().contains("500") || e.getMessage().contains("504")) {
                    System.out.println("⏳ Servidor saturado. Esperando 5s...");
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {}
                } else if (e.getMessage().contains("429")) {
                    System.out.println("⏳ Rate Limit. Esperando 60s...");
                    try { Thread.sleep(60000); } catch (InterruptedException ie) {}
                } else {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                }
            }
        }
        return null;
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