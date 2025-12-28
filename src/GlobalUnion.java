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
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GlobalUnion {

    private static final String STEAM_FILE = "steam_games.json.gz";
    private static final String RAWG_FILE = "rawg_games.json.gz";
    private static final String OUTPUT_FILE = "global_games.json.gz";
    private static final String CONFLICT_REPORT_FILE = "conflicts_report.txt";

    public static void main(String[] args) {
        ObjectMapper mapper = new ObjectMapper();
        
        try {
            System.out.println("🚀 Iniciando Fusión Global (Steam + RAWG) por TÍTULO (Unicode)...");

            // --- 1. Cargar todos los juegos de Steam en un mapa por TÍTULO NORMALIZADO ---
            System.out.println("   -> Cargando juegos de Steam en memoria (Indexando por Título)...");
            Map<String, JsonNode> steamGamesByTitle = loadGamesToMapByTitle(STEAM_FILE, mapper);
            int totalSteamInicial = steamGamesByTitle.size();
            System.out.println("   -> " + totalSteamInicial + " juegos de Steam cargados.");

            // Contadores para estadísticas
            int totalRawg = 0;
            int mergedCount = 0;
            int rawgOnlyCount = 0;
            int conflictosResueltos = 0;
            int fusionesInteligentes = 0; 

            // Preparar reporte de conflictos
            try (PrintWriter conflictWriter = new PrintWriter(new BufferedWriter(new FileWriter(CONFLICT_REPORT_FILE)))) {
                conflictWriter.println("📊 REPORTE DE CONFLICTOS Y FUSIONES");
                conflictWriter.println("=========================================\n");

                // Lista para guardar los juegos de RAWG que no hicieron match directo
                List<JsonNode> rawgHuérfanos = new ArrayList<>();

                // --- 2. Primera Pasada: Fusión por Título Exacto ---
                System.out.println("   -> Pasada 1: Fusión por Título Exacto...");
                
                JsonFactory factory = mapper.getFactory();
                try (InputStream is = new GZIPInputStream(new FileInputStream(RAWG_FILE));
                     JsonParser parser = factory.createParser(is)) {

                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new IllegalStateException("Se esperaba un array JSON en " + RAWG_FILE);
                    }

                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode rawgGame = mapper.readTree(parser);
                        totalRawg++;
                        
                        String rawgTitle = rawgGame.path("titulo").asText();
                        String rawgTitleNorm = normalizeTitle(rawgTitle);
                        
                        // BÚSQUEDA POR TÍTULO EXACTO
                        if (!rawgTitleNorm.isEmpty() && steamGamesByTitle.containsKey(rawgTitleNorm)) {
                            JsonNode steamGame = steamGamesByTitle.get(rawgTitleNorm);
                            
                            if (sonElMismoJuego(steamGame, rawgGame)) {
                                // Fusión exitosa
                                JsonNode finalGame = fusionarJuegos(steamGame, rawgGame, mapper);
                                steamGamesByTitle.put(rawgTitleNorm, finalGame);
                                ((ObjectNode)finalGame).put("_merged", true);
                                mergedCount++;
                            } else {
                                // Conflicto (mismo título, distinto juego) -> Se trata como nuevo
                                registrarConflicto(conflictWriter, steamGame, rawgGame, "CONFLICTO (AÑO/TIPO)");
                                rawgHuérfanos.add(rawgGame);
                            }
                        } else {
                            // No encontrado por título exacto -> A la lista de espera para Pasada 2
                            rawgHuérfanos.add(rawgGame);
                        }
                    }
                }

                // --- 3. Segunda Pasada: Fusión Inteligente (Fuzzy) ---
                System.out.println("   -> Pasada 2: Fusión Inteligente (Huérfanos: " + rawgHuérfanos.size() + ")...");
                
                List<JsonNode> rawgFinales = new ArrayList<>();
                
                // Optimización: Convertir mapa a lista para iterar más rápido
                // Y pre-filtrar solo los NO fusionados para reducir comparaciones
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
                    if (anioRawg > 0) {
                        // Buscamos en la lista optimizada de Steam
                        for (JsonNode steamGame : steamCandidates) {
                            // Si ya fue fusionado en esta misma pasada (por otro huérfano), saltar
                            if (steamGame.has("_merged")) continue;

                            int anioSteam = extraerAnio(steamGame);
                            if (Math.abs(anioSteam - anioRawg) <= 1) {
                                // Candidato por año. Verificamos desarrollador, título parcial Y TIPO.
                                if (esMatchInteligente(steamGame, rawgGame)) {
                                    JsonNode finalGame = fusionarJuegos(steamGame, rawgGame, mapper);
                                    
                                    // Actualizamos el mapa original usando el título normalizado del juego de Steam
                                    String key = normalizeTitle(steamGame.path("titulo").asText());
                                    steamGamesByTitle.put(key, finalGame);
                                    
                                    // Marcamos en el objeto en memoria para que no se vuelva a usar
                                    ((ObjectNode)steamGame).put("_merged", true); 
                                    ((ObjectNode)finalGame).put("_merged", true);
                                    
                                    registrarConflicto(conflictWriter, steamGame, rawgGame, "FUSIÓN INTELIGENTE");
                                    fusionesInteligentes++;
                                    fusionado = true;
                                    break; 
                                }
                            }
                        }
                    }
                    
                    if (!fusionado) {
                        rawgFinales.add(rawgGame);
                    }
                    
                    procesadosPasada2++;
                    if (procesadosPasada2 % 1000 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        double speed = procesadosPasada2 / (elapsed / 1000.0);
                        System.out.print("\r      -> Procesados: " + procesadosPasada2 + "/" + rawgHuérfanos.size() + 
                                         " | Fusiones: " + fusionesInteligentes + 
                                         " | Vel: " + String.format("%.1f", speed) + " j/s");
                    }
                }
                System.out.println(); // Salto de línea final

                // --- 4. Escribir Resultado Final ---
                System.out.println("   -> Escribiendo archivo final...");
                
                try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(OUTPUT_FILE)), "UTF-8")) {
                    writer.write("[\n");
                    boolean primero = true;

                    // Escribir juegos de Steam (que ahora incluyen los fusionados)
                    for (JsonNode game : steamGamesByTitle.values()) {
                        if (game.has("_merged")) {
                            ((ObjectNode)game).remove("_merged"); 
                        }
                        limpiarGaleria((ObjectNode) game);
                        
                        if (!primero) writer.write(",\n");
                        writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(game));
                        primero = false;
                    }

                    // Escribir juegos exclusivos de RAWG
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
                System.out.println("\n📊 ESTADÍSTICAS DE FUSIÓN:");
                System.out.println("   =========================================");
                System.out.println("   📥 ORIGEN:");
                System.out.println("      - Total Steam: " + totalSteamInicial);
                System.out.println("      - Total RAWG:  " + totalRawg);
                System.out.println("   -----------------------------------------");
                System.out.println("   🔄 PROCESO:");
                System.out.println("      - 🔗 Fusionados (Exacto):      " + mergedCount);
                System.out.println("      - 🧠 Fusionados (Inteligente): " + fusionesInteligentes);
                System.out.println("      - 🎮 Solo en RAWG (Nuevos):    " + rawgOnlyCount);
                System.out.println("      - 💻 Solo en Steam (PC):       " + (steamGamesByTitle.size() - mergedCount - fusionesInteligentes));
                System.out.println("   -----------------------------------------");
                System.out.println("   📤 RESULTADO FINAL:");
                System.out.println("      - Total Global: " + (steamGamesByTitle.size() + rawgOnlyCount));
                System.out.println("   =========================================");
            }

            System.out.println("\n✅ Fusión completada. Archivo: " + OUTPUT_FILE);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // --- LÓGICA DE FUSIÓN INTELIGENTE ---
    
    private static boolean esMatchInteligente(JsonNode steam, JsonNode rawg) {
        // 0. VALIDACIÓN DE SEGURIDAD: El TIPO debe ser idéntico (game vs game, dlc vs dlc)
        String tipoSteam = steam.path("tipo").asText("game");
        String tipoRawg = rawg.path("tipo").asText("game");
        
        if (!tipoSteam.equalsIgnoreCase(tipoRawg)) {
            return false; // Nunca fusionar Juego con DLC en la pasada inteligente
        }

        // 1. Verificar Desarrollador (Intersección de conjuntos)
        if (!compartenDesarrollador(steam, rawg)) {
            return false;
        }
        
        // 2. Verificar Similitud de Título
        String tSteam = normalizeTitle(steam.path("titulo").asText());
        String tRawg = normalizeTitle(rawg.path("titulo").asText());
        
        if (tSteam.contains(tRawg) || tRawg.contains(tSteam)) {
            return true;
        }
        
        return false;
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
        String fechaSteam = steam.path("fecha_lanzamiento").asText("N/A");
        String fechaRawg = rawg.path("fecha_lanzamiento").asText("N/A");
        
        writer.println("ℹ️ " + tipo + ":");
        writer.println("   Steam: " + tituloSteam + " (" + fechaSteam + ")");
        writer.println("   RAWG:  " + tituloRawg + " (" + fechaRawg + ")");
        writer.println("-----------------------------------------");
    }

    private static Map<String, JsonNode> loadGamesToMapByTitle(String filePath, ObjectMapper mapper) throws Exception {
        Map<String, JsonNode> gameMap = new HashMap<>();
        JsonFactory factory = mapper.getFactory();

        try (InputStream is = new GZIPInputStream(new FileInputStream(filePath));
             JsonParser parser = factory.createParser(is)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Se esperaba un array JSON en " + filePath);
            }

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode gameNode = mapper.readTree(parser);
                String titulo = gameNode.path("titulo").asText();
                
                if (titulo != null && !titulo.isEmpty()) {
                    String normTitle = normalizeTitle(titulo);
                    if (!normTitle.isEmpty()) {
                        gameMap.putIfAbsent(normTitle, gameNode);
                    }
                }
            }
        }
        return gameMap;
    }
    
    private static String normalizeTitle(String title) {
        if (title == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : title.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
    
    // --- LÓGICA DE VALIDACIÓN DE FUSIÓN (RESTAURADA) ---
    
    private static boolean sonElMismoJuego(JsonNode steam, JsonNode rawg) {
        // 1. Tipos: Deben ser iguales (game vs game, dlc vs dlc)
        String tipoSteam = steam.path("tipo").asText("game");
        String tipoRawg = rawg.path("tipo").asText("game");
        
        if (!tipoSteam.equalsIgnoreCase(tipoRawg)) {
            return false; // RESTAURADO: No fusionar si los tipos difieren
        }

        // 2. Fechas: Solo validamos que no sean juegos distintos con mismo nombre (Remakes)
        int anioSteam = extraerAnio(steam);
        int anioRawg = extraerAnio(rawg);
        
        if (anioSteam == 0 || anioRawg == 0) return true;
        
        int diff = Math.abs(anioSteam - anioRawg);

        if (diff <= 1) return true;

        // Si la diferencia es < 10 años -> Asumimos PORT -> FUSIONAR
        // Si la diferencia es >= 10 años -> Asumimos REMAKE/REBOOT -> SEPARAR
        return diff < 10;
    }
    
    private static int extraerAnio(JsonNode game) {
        String fecha = game.path("fecha_lanzamiento").asText();
        if (fecha == null || fecha.isEmpty()) return 0;
        try {
            if (fecha.length() >= 4) {
                return Integer.parseInt(fecha.substring(0, 4));
            }
        } catch (Exception e) {}
        return 0;
    }

    private static JsonNode fusionarJuegos(JsonNode steamGame, JsonNode rawgGame, ObjectMapper mapper) {
        ObjectNode base = (ObjectNode) steamGame.deepCopy();

        String fechaSteam = base.path("fecha_lanzamiento").asText("");
        String fechaRawg = rawgGame.path("fecha_lanzamiento").asText("");
        
        if (!fechaRawg.isEmpty() && !fechaSteam.isEmpty()) {
            if (fechaRawg.compareTo(fechaSteam) < 0) {
                base.put("fecha_lanzamiento", fechaRawg);
            }
        } else if (fechaSteam.isEmpty() && !fechaRawg.isEmpty()) {
            base.put("fecha_lanzamiento", fechaRawg);
        }

        int steamMetacritic = base.path("metacritic").asInt(0);
        int rawgMetacritic = rawgGame.path("metacritic").asInt(0);
        if (rawgMetacritic > steamMetacritic) {
            base.put("metacritic", rawgMetacritic);
        }

        fusionarArray(base, rawgGame, "plataformas");
        fusionarArray(base, rawgGame, "generos");
        fusionarArray(base, rawgGame, "galeria");
        
        // NUEVO: Fusión de Desarrolladores y Editores
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
                // CORREGIDO: Solo bloqueamos "steam" para evitar duplicados. Permitimos GOG, Epic, etc.
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
            if (imgNode.asText().equals(imgPrincipal)) {
                it.remove();
            }
        }
    }
}