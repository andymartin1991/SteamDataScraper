import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GlobalUnion {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=========================================");
        System.out.println("   🔗 GLOBAL UNION - FUSIÓN DE DATOS");
        System.out.println("=========================================");
        System.out.println("Selecciona el modo de operación:");
        System.out.println("   [1] Fusión de Catálogo GLOBAL (Juegos lanzados)");
        System.out.println("   [2] Fusión de PRÓXIMOS Lanzamientos (Coming Soon)");
        System.out.println("   [0] Salir");
        System.out.println("-----------------------------------------");
        System.out.print("👉 Opción: ");

        int opcion = -1;
        if (scanner.hasNextInt()) {
            opcion = scanner.nextInt();
        }

        switch (opcion) {
            case 1:
                System.out.println("\n🌍 Iniciando Fusión GLOBAL...");
                ejecutarFusion(
                    "steam_games.json.gz",
                    "rawg_games.json.gz",
                    "global_games.json.gz",
                    "conflicts_report.txt"
                );
                break;
            case 2:
                System.out.println("\n🌟 Iniciando Fusión de PRÓXIMOS LANZAMIENTOS...");
                ejecutarFusion(
                    "steam_proximos_games.json.gz",
                    "rawg_proximos_games.json.gz",
                    "global_proximos_games.json.gz",
                    "conflicts_report_proximos.txt"
                );
                break;
            case 0:
                System.out.println("👋 Saliendo...");
                break;
            default:
                System.out.println("❌ Opción inválida.");
        }
    }

    private static void ejecutarFusion(String steamFile, String rawgFile, String outputFile, String reportFile) {
        ObjectMapper mapper = new ObjectMapper();
        
        try {
            System.out.println("🚀 Cargando datos (" + steamFile + " + " + rawgFile + ")...");

            // --- 1. Cargar todos los juegos de Steam en un mapa por TÍTULO NORMALIZADO ---
            System.out.println("   -> Cargando Steam en memoria...");
            Map<String, JsonNode> steamGamesByTitle = loadGamesToMapByTitle(steamFile, mapper);
            int totalSteamInicial = steamGamesByTitle.size();
            System.out.println("   -> " + totalSteamInicial + " juegos de Steam cargados.");

            // Contadores
            int totalRawg = 0;
            int mergedCount = 0;
            int rawgOnlyCount = 0;
            int fusionesInteligentes = 0; 

            try (PrintWriter conflictWriter = new PrintWriter(new BufferedWriter(new FileWriter(reportFile)))) {
                conflictWriter.println("📊 REPORTE DE FUSIÓN: " + outputFile);
                conflictWriter.println("=========================================\n");

                List<JsonNode> rawgHuérfanos = new ArrayList<>();

                // --- 2. Primera Pasada: Fusión por Título Exacto ---
                System.out.println("   -> Pasada 1: Fusión por Título Exacto...");
                
                JsonFactory factory = mapper.getFactory();
                try (InputStream is = new GZIPInputStream(new FileInputStream(rawgFile));
                     JsonParser parser = factory.createParser(is)) {

                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new IllegalStateException("Se esperaba un array JSON en " + rawgFile);
                    }

                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode rawgGame = mapper.readTree(parser);
                        totalRawg++;
                        
                        String rawgTitle = rawgGame.path("titulo").asText();
                        String rawgTitleNorm = normalizeTitle(rawgTitle);
                        
                        if (!rawgTitleNorm.isEmpty() && steamGamesByTitle.containsKey(rawgTitleNorm)) {
                            JsonNode steamGame = steamGamesByTitle.get(rawgTitleNorm);
                            
                            if (sonElMismoJuego(steamGame, rawgGame)) {
                                JsonNode finalGame = fusionarJuegos(steamGame, rawgGame, mapper);
                                steamGamesByTitle.put(rawgTitleNorm, finalGame);
                                ((ObjectNode)finalGame).put("_merged", true);
                                mergedCount++;
                            } else {
                                registrarConflicto(conflictWriter, steamGame, rawgGame, "CONFLICTO (AÑO/TIPO)");
                                rawgHuérfanos.add(rawgGame);
                            }
                        } else {
                            rawgHuérfanos.add(rawgGame);
                        }
                    }
                } catch (java.io.FileNotFoundException e) {
                    System.out.println("⚠️ Archivo RAWG no encontrado (" + rawgFile + "). Se generará salida solo con Steam.");
                }

                // --- 3. Segunda Pasada: Fusión Inteligente (Fuzzy) ---
                System.out.println("   -> Pasada 2: Fusión Inteligente (Huérfanos: " + rawgHuérfanos.size() + ")...");
                
                List<JsonNode> rawgFinales = new ArrayList<>();
                List<JsonNode> steamCandidates = new ArrayList<>();
                for (JsonNode s : steamGamesByTitle.values()) {
                    if (!s.has("_merged")) {
                        steamCandidates.add(s);
                    }
                }
                System.out.println("      (Comparando contra " + steamCandidates.size() + " candidatos de Steam no fusionados)");

                int procesadosPasada2 = 0;
                long startTime = System.currentTimeMillis();

                for (JsonNode rawgGame : rawgHuérfanos) {
                    boolean fusionado = false;
                    int anioRawg = extraerAnio(rawgGame);
                    
                    for (JsonNode steamGame : steamCandidates) {
                        if (steamGame.has("_merged")) continue;

                        int anioSteam = extraerAnio(steamGame);
                        boolean anioCompatible = true;
                        if (anioRawg > 0 && anioSteam > 0) {
                            if (Math.abs(anioSteam - anioRawg) > 1) anioCompatible = false;
                        }
                        
                        if (anioCompatible) {
                            if (esMatchInteligente(steamGame, rawgGame)) {
                                JsonNode finalGame = fusionarJuegos(steamGame, rawgGame, mapper);
                                String key = normalizeTitle(steamGame.path("titulo").asText());
                                steamGamesByTitle.put(key, finalGame);
                                ((ObjectNode)steamGame).put("_merged", true); 
                                ((ObjectNode)finalGame).put("_merged", true);
                                
                                registrarConflicto(conflictWriter, steamGame, rawgGame, "FUSIÓN INTELIGENTE");
                                fusionesInteligentes++;
                                fusionado = true;
                                break; 
                            }
                        }
                    }
                    
                    if (!fusionado) {
                        rawgFinales.add(rawgGame);
                    }
                    
                    procesadosPasada2++;
                    if (procesadosPasada2 % 1000 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        double speed = procesadosPasada2 / (Math.max(1, elapsed) / 1000.0);
                        System.out.print("\r      -> Procesados: " + procesadosPasada2 + "/" + rawgHuérfanos.size() + 
                                         " | Fusiones: " + fusionesInteligentes + 
                                         " | Vel: " + String.format("%.1f", speed) + " j/s");
                    }
                }
                System.out.println(); 

                // --- 4. Escribir Resultado Final ---
                System.out.println("   -> Escribiendo archivo final: " + outputFile);
                
                try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(outputFile)), "UTF-8")) {
                    writer.write("[\n");
                    boolean primero = true;

                    for (JsonNode game : steamGamesByTitle.values()) {
                        if (game.has("_merged")) ((ObjectNode)game).remove("_merged"); 
                        limpiarGaleria((ObjectNode) game);
                        if (!primero) writer.write(",\n");
                        writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(game));
                        primero = false;
                    }

                    for (JsonNode rawgGame : rawgFinales) {
                        ObjectNode gameNode = (ObjectNode) rawgGame.deepCopy();
                        limpiarGaleria(gameNode);
                        if (!primero) writer.write(",\n");
                        writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(gameNode));
                        primero = false;
                        rawgOnlyCount++;
                    }
                    writer.write("\n]");
                }

                // --- 5. Estadísticas ---
                System.out.println("\n📊 ESTADÍSTICAS (" + outputFile + "):");
                System.out.println("   =========================================");
                System.out.println("   📥 ORIGEN:");
                System.out.println("      - Steam: " + totalSteamInicial);
                System.out.println("      - RAWG:  " + totalRawg);
                System.out.println("   -----------------------------------------");
                System.out.println("   🔄 PROCESO:");
                System.out.println("      - 🔗 Fusionados (Exacto):      " + mergedCount);
                System.out.println("      - 🧠 Fusionados (Inteligente): " + fusionesInteligentes);
                System.out.println("      - 🎮 Solo en RAWG:             " + rawgOnlyCount);
                System.out.println("      - 💻 Solo en Steam:            " + (steamGamesByTitle.size() - mergedCount - fusionesInteligentes));
                System.out.println("   -----------------------------------------");
                System.out.println("   📤 TOTAL FINAL: " + (steamGamesByTitle.size() + rawgOnlyCount));
                System.out.println("   =========================================");
            }

            System.out.println("\n✅ Proceso completado.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // --- MÉTODOS AUXILIARES ---

    private static boolean esMatchInteligente(JsonNode steam, JsonNode rawg) {
        String tipoSteam = steam.path("tipo").asText("game");
        String tipoRawg = rawg.path("tipo").asText("game");
        if (!tipoSteam.equalsIgnoreCase(tipoRawg)) return false; 

        if (!compartenDesarrollador(steam, rawg)) return false;
        
        String tSteam = normalizeTitle(steam.path("titulo").asText());
        String tRawg = normalizeTitle(rawg.path("titulo").asText());
        return tSteam.contains(tRawg) || tRawg.contains(tSteam);
    }
    
    private static boolean compartenDesarrollador(JsonNode g1, JsonNode g2) {
        Set<String> devs1 = getSetFromJsonArray(g1.path("desarrolladores"));
        Set<String> devs2 = getSetFromJsonArray(g2.path("desarrolladores"));
        if (devs1.isEmpty() || devs2.isEmpty()) return false; 
        for (String d1 : devs1) {
            for (String d2 : devs2) {
                if (nombresSimilares(d1, d2)) return true;
            }
        }
        return false;
    }
    
    private static boolean nombresSimilares(String n1, String n2) {
        String s1 = normalizeTitle(n1); 
        String s2 = normalizeTitle(n2);
        return s1.equals(s2) || s1.contains(s2) || s2.contains(s1);
    }
    
    private static Set<String> getSetFromJsonArray(JsonNode arrayNode) {
        Set<String> set = new HashSet<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode node : arrayNode) {
                set.add(node.asText());
            }
        }
        return set;
    }

    private static void registrarConflicto(PrintWriter writer, JsonNode steam, JsonNode rawg, String tipo) {
        String tituloSteam = steam.path("titulo").asText();
        String tituloRawg = rawg.path("titulo").asText();
        writer.println("ℹ️ " + tipo + ": Steam='" + tituloSteam + "' | RAWG='" + tituloRawg + "'");
    }

    private static Map<String, JsonNode> loadGamesToMapByTitle(String filePath, ObjectMapper mapper) throws Exception {
        Map<String, JsonNode> gameMap = new HashMap<>();
        JsonFactory factory = mapper.getFactory();
        try (InputStream is = new GZIPInputStream(new FileInputStream(filePath));
             JsonParser parser = factory.createParser(is)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) throw new IllegalStateException("Array esperado");
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode gameNode = mapper.readTree(parser);
                String titulo = gameNode.path("titulo").asText();
                if (titulo != null && !titulo.isEmpty()) {
                    gameMap.putIfAbsent(normalizeTitle(titulo), gameNode);
                }
            }
        } catch (java.io.FileNotFoundException e) {
            System.out.println("⚠️ Archivo Steam no encontrado (" + filePath + "). Iniciando mapa vacío.");
        }
        return gameMap;
    }
    
    private static String normalizeTitle(String title) {
        if (title == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : title.toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
    
    private static boolean sonElMismoJuego(JsonNode steam, JsonNode rawg) {
        String tipoSteam = steam.path("tipo").asText("game");
        String tipoRawg = rawg.path("tipo").asText("game");
        if (!tipoSteam.equalsIgnoreCase(tipoRawg)) return false; 

        int anioSteam = extraerAnio(steam);
        int anioRawg = extraerAnio(rawg);
        if (anioSteam == 0 || anioRawg == 0) return true;
        return Math.abs(anioSteam - anioRawg) < 10;
    }
    
    private static int extraerAnio(JsonNode game) {
        String fecha = game.path("fecha_lanzamiento").asText();
        if (fecha == null || fecha.isEmpty() || fecha.equals("TBA")) return 0;
        try {
            if (fecha.length() >= 4) return Integer.parseInt(fecha.substring(0, 4));
        } catch (Exception e) {}
        return 0;
    }

    private static JsonNode fusionarJuegos(JsonNode steamGame, JsonNode rawgGame, ObjectMapper mapper) {
        ObjectNode base = (ObjectNode) steamGame.deepCopy();
        
        String fechaSteam = base.path("fecha_lanzamiento").asText("");
        String fechaRawg = rawgGame.path("fecha_lanzamiento").asText("");
        if (fechaSteam.equals("TBA") && !fechaRawg.equals("TBA") && !fechaRawg.isEmpty()) {
            base.put("fecha_lanzamiento", fechaRawg);
        } else if (!fechaRawg.isEmpty() && !fechaSteam.isEmpty() && !fechaSteam.equals("TBA")) {
            if (fechaRawg.compareTo(fechaSteam) < 0) base.put("fecha_lanzamiento", fechaRawg);
        }

        int steamMetacritic = base.path("metacritic").asInt(0);
        int rawgMetacritic = rawgGame.path("metacritic").asInt(0);
        if (rawgMetacritic > steamMetacritic) base.put("metacritic", rawgMetacritic);

        fusionarArray(base, rawgGame, "plataformas");
        fusionarArray(base, rawgGame, "generos");
        fusionarArray(base, rawgGame, "galeria");
        fusionarArray(base, rawgGame, "desarrolladores");
        fusionarArray(base, rawgGame, "editores");

        ArrayNode steamStores = (ArrayNode) base.path("tiendas");
        ArrayNode rawgStores = (ArrayNode) rawgGame.path("tiendas");
        Set<String> existingStoreNames = new HashSet<>();
        if (steamStores != null) {
            steamStores.forEach(node -> existingStoreNames.add(node.path("tienda").asText().toLowerCase()));
        } else {
            steamStores = base.putArray("tiendas");
        }

        if (rawgStores != null) {
            for (JsonNode storeNode : rawgStores) {
                String storeName = storeNode.path("tienda").asText().toLowerCase();
                if (!storeName.equals("steam") && !existingStoreNames.contains(storeName)) {
                    steamStores.add(storeNode);
                }
            }
        }
        
        limpiarGaleria(base);
        return base;
    }

    private static void fusionarArray(ObjectNode base, JsonNode source, String fieldName) {
        ArrayNode baseArray = (ArrayNode) base.path(fieldName);
        ArrayNode sourceArray = (ArrayNode) source.path(fieldName);
        Set<String> existingItems = new HashSet<>();
        if (baseArray != null) {
            baseArray.forEach(node -> existingItems.add(node.asText().toLowerCase()));
        } else {
            baseArray = base.putArray(fieldName);
        }
        if (sourceArray != null) {
            for (JsonNode node : sourceArray) {
                String item = node.asText();
                if (!existingItems.contains(item.toLowerCase())) {
                    baseArray.add(item);
                    existingItems.add(item.toLowerCase());
                }
            }
        }
    }
    
    private static void limpiarGaleria(ObjectNode game) {
        String imgPrincipal = game.path("img_principal").asText("");
        if (imgPrincipal.isEmpty()) return;
        ArrayNode galeria = (ArrayNode) game.path("galeria");
        if (galeria == null) return;
        Iterator<JsonNode> it = galeria.iterator();
        while (it.hasNext()) {
            JsonNode imgNode = it.next();
            if (imgNode.asText().equals(imgPrincipal)) it.remove();
        }
    }
}