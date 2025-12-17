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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SteamRawCollector {

    private static final String API_KEY = "4BACB9912AA5A10AC6A59248FB48820F";
    private static final String DB_FILE = "steam_raw.sqlite";
    
    private static final boolean MODO_PRUEBA = false; 
    private static final int LIMITE_PRUEBA = 100;

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando SteamRawCollector (Filtro Inteligente + Auto-Update Coming Soon)...");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                System.err.println("❌ ERROR CRÍTICO: No se encontró el driver JDBC de SQLite.");
                return;
            }

            setupDatabase();

            Set<Integer> idsYaProcesados = cargarIdsYaProcesados();
            System.out.println("📚 Base de datos (Juegos Finalizados + Ignorados): " + idsYaProcesados.size() + " ítems.");
            
            System.out.println("☁️ Descargando catálogo fresco de Steam...");
            List<Integer> catalogoSteam = obtenerCatalogoSteam();
            System.out.println("📦 Catálogo Steam total: " + catalogoSteam.size() + " ítems.");

            List<Integer> pendientes = new ArrayList<>();
            for (Integer id : catalogoSteam) {
                if (!idsYaProcesados.contains(id)) {
                    pendientes.add(id);
                }
            }
            System.out.println("⚡ Pendientes de análisis (Nuevos + Coming Soon): " + pendientes.size() + " ítems.");

            if (pendientes.isEmpty()) {
                System.out.println("✅ Todo sincronizado. No hay trabajo pendiente.");
                return;
            }

            int procesados = 0;
            int juegosGuardados = 0;
            int basuraDescartada = 0;
            
            for (int i = 0; i < pendientes.size(); i++) {
                int appId = pendientes.get(i);
                
                try {
                    if (MODO_PRUEBA && procesados >= LIMITE_PRUEBA) {
                        System.out.println("🧪 Límite de prueba alcanzado.");
                        break;
                    }

                    String jsonCrudo = descargarJsonJuego(appId);

                    if (jsonCrudo != null && !jsonCrudo.isEmpty()) {
                        
                        if (jsonCrudo.contains("\"type\":\"game\"")) {
                            guardarJuego(appId, jsonCrudo);
                            juegosGuardados++;
                        } else {
                            guardarIgnorado(appId);
                            basuraDescartada++;
                        }
                    }
                    
                    if (procesados % 50 == 0) {
                        System.out.println(String.format("🚀 Progreso: %d/%d | Juegos: %d | Descartados: %d | ID: %d", 
                            procesados, pendientes.size(), juegosGuardados, basuraDescartada, appId));
                    }
                    
                    procesados++;

                } catch (Throwable t) {
                    System.err.println("❌ Error crítico en AppID " + appId + ": " + t.toString());
                }
            }
            
            System.out.println("\n🏁 Sincronización finalizada.");
            System.out.println("   -> Juegos Procesados/Actualizados: " + juegosGuardados);
            System.out.println("   -> Basura Descartada: " + basuraDescartada);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- GESTIÓN DE BASE DE DATOS ---

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            
            // Configuración para mejorar concurrencia y evitar bloqueos
            stmt.execute("PRAGMA journal_mode=WAL;"); // Write-Ahead Logging
            stmt.execute("PRAGMA busy_timeout=5000;"); // Esperar hasta 5s si está ocupada
            
            stmt.execute("CREATE TABLE IF NOT EXISTS steam_raw_data (" +
                         "app_id INTEGER PRIMARY KEY, " +
                         "json_data TEXT NOT NULL, " +
                         "fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            
            stmt.execute("CREATE TABLE IF NOT EXISTS steam_ignored_ids (" +
                         "app_id INTEGER PRIMARY KEY)");
                         
        } catch (Exception e) {
            System.err.println("❌ Error fatal al configurar la base de datos: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Set<Integer> cargarIdsYaProcesados() {
        Set<Integer> ids = new HashSet<>();
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
             
            ResultSet rsIgnored = stmt.executeQuery("SELECT app_id FROM steam_ignored_ids");
            while (rsIgnored.next()) {
                ids.add(rsIgnored.getInt("app_id"));
            }
            
            ResultSet rsGames = stmt.executeQuery("SELECT app_id, json_data FROM steam_raw_data");
            while (rsGames.next()) {
                String json = rsGames.getString("json_data");
                if (!json.contains("\"coming_soon\":true")) {
                    ids.add(rsGames.getInt("app_id"));
                }
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ No se pudo cargar la lista de IDs procesados: " + e.getMessage());
        }
        return ids;
    }

    private static void guardarJuego(int appId, String json) {
        String sql = "INSERT OR REPLACE INTO steam_raw_data(app_id, json_data) VALUES(?,?)";
        int intentos = 0;
        while (intentos < 3) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, appId);
                pstmt.setString(2, json);
                pstmt.executeUpdate();
                return; // Éxito
            } catch (Exception e) {
                if (e.getMessage().contains("locked")) {
                    intentos++;
                    try { Thread.sleep(100); } catch (InterruptedException ie) {}
                } else {
                    System.err.println("⚠️ Error guardando JUEGO " + appId + ": " + e.getMessage());
                    return;
                }
            }
        }
        System.err.println("❌ Fallo al guardar JUEGO " + appId + " tras 3 intentos (DB Locked)");
    }
    
    private static void guardarIgnorado(int appId) {
        String sql = "INSERT OR IGNORE INTO steam_ignored_ids(app_id) VALUES(?)";
        int intentos = 0;
        while (intentos < 3) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, appId);
                pstmt.executeUpdate();
                return;
            } catch (Exception e) {
                if (e.getMessage().contains("locked")) {
                    intentos++;
                    try { Thread.sleep(100); } catch (InterruptedException ie) {}
                } else {
                    System.err.println("⚠️ Error guardando IGNORADO " + appId + ": " + e.getMessage());
                    return;
                }
            }
        }
    }
    
    // --- LÓGICA DE DESCARGA ---

    private static String descargarJsonJuego(int appId) throws Exception {
        String urlString = "https://store.steampowered.com/api/appdetails?appids=" + appId + "&l=english&cc=us";
        return peticionHttp(urlString);
    }

    private static List<Integer> obtenerCatalogoSteam() {
        List<Integer> ids = new ArrayList<>();
        int lastAppId = 0;
        
        while (true) { 
            try {
                String url = "https://api.steampowered.com/IStoreService/GetAppList/v1/?key=" + API_KEY +
                             "&include_games=true&include_dlc=false&max_results=50000&last_appid=" + lastAppId;
                String json = peticionHttp(url);
                if (json == null || json.isEmpty()) break;
                
                Pattern p = Pattern.compile("\"appid\":(\\d+)");
                Matcher m = p.matcher(json);
                int foundInPage = 0;
                while (m.find()) {
                    ids.add(Integer.parseInt(m.group(1)));
                    foundInPage++;
                }
                
                if (foundInPage == 0) break;
                if (MODO_PRUEBA) break; 
                
                lastAppId = ids.get(ids.size() - 1);
            } catch (Exception e) {
                break;
            }
        }
        return ids;
    }

    private static String peticionHttp(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            
            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) content.append(line);
                    return content.toString();
                }
            } else if (code == 429) {
                System.out.println("⏳ Bloqueo detectado (Error 429). Reintentando en 60s...");
                Thread.sleep(60000);
                return peticionHttp(urlString); 
            } else {
                return null; 
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}