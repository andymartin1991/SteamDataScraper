package steam;

import common.ApiKeyConfig;
import common.CollectorRunGuard;
import common.CollectorRunGuard.JsonTarget;
import common.CollectorRunGuard.RunSession;
import common.DataStoreException;
import common.ResilientHttpClient;
import common.ResilientHttpClient.RequestInterruptedException;
import common.ReleaseDatePolicy;
import common.ReleaseDateTrackingStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SteamRawCollector {

    private static final String STEAM_API_KEY_ENV = "STEAM_API_KEY";
    private static final String STEAM_API_KEY_PROPERTY = "steam.api.key";
    private static final String DB_FILE = "data/db/steam_raw.sqlite";
    
    private static final boolean MODO_PRUEBA = false; 
    private static final int LIMITE_PRUEBA = 100;

    public static void main(String[] args) {
        RunSession runSession = null;
        try {
            System.out.println("🚀 Iniciando SteamRawCollector (descubrimiento incremental)...");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("No se encontró el driver JDBC de SQLite", e);
            }

            ensureParentDir(DB_FILE);
            setupDatabase();
            runSession = CollectorRunGuard.begin(
                DB_FILE,
                "SteamRawCollector",
                new JsonTarget("steam_raw_data", "app_id", "json_data", "fecha_sync")
            );
            String steamApiKey = getSteamApiKey();

            // El collector principal solo descubre IDs nuevos. Los juegos sin fecha,
            // TBA o futuros se revisan desde SteamReleaseDateUpdater.
            Set<Integer> idsYaGuardados = cargarIdsYaGuardados();
            System.out.println("📚 Base de datos (Procesados + Ignorados): " + idsYaGuardados.size() + " ítems.");
            
            System.out.println("☁️ Descargando catálogo fresco de Steam (Juegos + DLCs)...");
            List<Integer> catalogoSteam = obtenerCatalogoSteam(steamApiKey);
            System.out.println("📦 Catálogo Steam total: " + catalogoSteam.size() + " ítems.");

            List<Integer> pendientes = new ArrayList<>();
            for (Integer id : catalogoSteam) {
                if (!idsYaGuardados.contains(id)) {
                    pendientes.add(id);
                }
            }
            System.out.println("⚡ IDs nuevos pendientes de análisis: " + pendientes.size() + " ítems.");

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
                        
                        // Aceptamos tanto JUEGOS como DLCs
                        if (jsonCrudo.contains("\"type\":\"game\"") || jsonCrudo.contains("\"type\":\"dlc\"")) {
                            guardarJuego(appId, jsonCrudo);
                            // Si antes estaba ignorado, ahora lo borramos de la lista negra
                            borrarDeIgnorados(appId);
                            juegosGuardados++;
                        } else {
                            guardarIgnorado(appId);
                            basuraDescartada++;
                        }
                    }
                    
                    if (procesados % 50 == 0) {
                        System.out.println(String.format("🚀 Progreso: %d/%d | Guardados: %d | Descartados: %d | ID: %d", 
                            procesados, pendientes.size(), juegosGuardados, basuraDescartada, appId));
                    }
                    
                    procesados++;
                    
                    // Respetar límites de Steam (evita el 429)
                    sleepOrStop(1500);

                } catch (RuntimeException t) {
                    if (t instanceof RequestInterruptedException interrupted) {
                        throw interrupted;
                    }
                    if (t instanceof DataStoreException persistenceFailure) {
                        throw persistenceFailure;
                    }
                    System.err.println("❌ Error crítico en AppID " + appId + ": " + t.toString());
                }
            }
            
            System.out.println("\n🏁 Sincronización finalizada.");
            System.out.println("   -> Juegos/DLCs Procesados: " + juegosGuardados);
            System.out.println("   -> Basura Descartada: " + basuraDescartada);
            
        } catch (RuntimeException e) {
            throw new IllegalStateException("SteamRawCollector finalizó con error", e);
        } finally {
            if (runSession != null) runSession.close();
        }
    }

    private static String getSteamApiKey() {
        return ApiKeyConfig.getRequiredValue(STEAM_API_KEY_PROPERTY, STEAM_API_KEY_ENV, "la Steam API key");
    }

    // --- GESTIÓN DE BASE DE DATOS ---

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("PRAGMA journal_mode=WAL;"); 
            stmt.execute("PRAGMA busy_timeout=5000;"); 
            
            stmt.execute("CREATE TABLE IF NOT EXISTS steam_raw_data (" +
                         "app_id INTEGER PRIMARY KEY, " +
                         "json_data TEXT NOT NULL, " +
                         "fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            
            stmt.execute("CREATE TABLE IF NOT EXISTS steam_ignored_ids (" +
                         "app_id INTEGER PRIMARY KEY)");
            ReleaseDateTrackingStore.ensureSchema(conn);
                         
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo configurar " + DB_FILE, e);
        }
    }

    private static void ensureParentDir(String filePath) {
        try {
            Path parent = Paths.get(filePath).getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo crear la carpeta para: " + filePath, e);
        }
    }

    private static Set<Integer> cargarIdsYaGuardados() {
        Set<Integer> ids = new HashSet<>();
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
             
            // Todo registro ya descargado se considera procesado. El seguimiento
            // de su fecha de lanzamiento vive en release_date_tracking.
            ResultSet rsGames = stmt.executeQuery("SELECT app_id FROM steam_raw_data");
            while (rsGames.next()) {
                ids.add(rsGames.getInt("app_id"));
            }
            rsGames.close();

            // 2. Cargar los IGNORADOS (demos, videos, etc.) para NO volver a evaluarlos.
            ResultSet rsIgnored = stmt.executeQuery("SELECT app_id FROM steam_ignored_ids");
            while (rsIgnored.next()) {
                ids.add(rsIgnored.getInt("app_id"));
            }
            rsIgnored.close();
            
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo cargar la lista de IDs procesados de Steam", e);
        }
        return ids;
    }

    private static void guardarJuego(int appId, String json) {
        CollectorRunGuard.requireCompleteJson(json, "Steam AppID " + appId);
        String sql = "INSERT OR REPLACE INTO steam_raw_data(app_id, json_data) VALUES(?,?)";
        int intentos = 0;
        while (intentos < 3) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, appId);
                    pstmt.setString(2, json);
                    pstmt.executeUpdate();
                }
                ReleaseDateTrackingStore.recordCollectorObservation(
                    conn,
                    appId,
                    ReleaseDatePolicy.fromSteam(json)
                );
                conn.commit();
                return;
            } catch (SQLException e) {
                if (isDatabaseLocked(e)) {
                    intentos++;
                    sleepOrStop(100);
                } else {
                    throw new DataStoreException("No se pudo guardar el juego Steam " + appId, e);
                }
            }
        }
        throw new DataStoreException("La base de datos siguió bloqueada al guardar Steam " + appId, null);
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
            } catch (SQLException e) {
                if (isDatabaseLocked(e)) {
                    intentos++;
                    sleepOrStop(100);
                } else {
                    throw new DataStoreException("No se pudo guardar el ID ignorado de Steam " + appId, e);
                }
            }
        }
        throw new DataStoreException("La base de datos siguió bloqueada al guardar el ID ignorado " + appId, null);
    }

    private static void borrarDeIgnorados(int appId) {
        String sql = "DELETE FROM steam_ignored_ids WHERE app_id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, appId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo retirar Steam " + appId + " de IDs ignorados", e);
        }
    }

    private static boolean isDatabaseLocked(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("locked");
    }

    private static void sleepOrStop(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestInterruptedException("SteamRawCollector interrumpido", e);
        }
    }
    
    // --- LÓGICA DE DESCARGA ---

    private static String descargarJsonJuego(int appId) {
        String urlString = "https://store.steampowered.com/api/appdetails?appids=" + appId + "&l=english&cc=us";
        return peticionHttp(urlString);
    }

    private static List<Integer> obtenerCatalogoSteam(String apiKey) {
        List<Integer> ids = new ArrayList<>();
        int lastAppId = 0;
        
        while (true) {
            String url = "https://api.steampowered.com/IStoreService/GetAppList/v1/?key=" + apiKey +
                         "&include_games=true&include_dlc=true&max_results=50000&last_appid=" + lastAppId;
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
        }
        return ids;
    }

    private static String peticionHttp(String urlString) {
        return ResilientHttpClient.get(urlString);
    }
}
