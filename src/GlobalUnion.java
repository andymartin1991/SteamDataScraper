import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GlobalUnion {

    private static final String STEAM_FILE = "steam_games.json.gz";
    private static final String RAWG_FILE = "rawg_games.json.gz";
    private static final String OUTPUT_FILE = "global_games.json.gz";

    public static void main(String[] args) {
        ObjectMapper mapper = new ObjectMapper();
        
        try {
            System.out.println("🚀 Iniciando Fusión Global (Steam + RAWG) por TÍTULO (Unicode)...");

            // --- 1. Cargar todos los juegos de Steam en un mapa por TÍTULO NORMALIZADO ---
            System.out.println("   -> Cargando juegos de Steam en memoria (Indexando por Título)...");
            // Mapa: Título Normalizado -> Nodo del Juego
            Map<String, JsonNode> steamGamesByTitle = loadGamesToMapByTitle(STEAM_FILE, mapper);
            int totalSteamInicial = steamGamesByTitle.size();
            System.out.println("   -> " + totalSteamInicial + " juegos de Steam cargados.");

            // Contadores para estadísticas
            int totalRawg = 0;
            int mergedCount = 0;
            int rawgOnlyCount = 0;
            int conflictosResueltos = 0;

            // --- 2. Iterar sobre RAWG y fusionar con Steam ---
            System.out.println("   -> Procesando y fusionando juegos de RAWG...");
            
            try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(OUTPUT_FILE)), "UTF-8")) {
                writer.write("[\n");

                JsonFactory factory = mapper.getFactory();
                try (InputStream is = new GZIPInputStream(new FileInputStream(RAWG_FILE));
                     JsonParser parser = factory.createParser(is)) {

                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new IllegalStateException("Se esperaba un array JSON en " + RAWG_FILE);
                    }

                    boolean primero = true;
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode rawgGame = mapper.readTree(parser);
                        totalRawg++;
                        
                        String rawgTitle = rawgGame.path("titulo").asText();
                        String rawgTitleNorm = normalizeTitle(rawgTitle);
                        String rawgSlug = rawgGame.path("slug").asText();
                        
                        JsonNode finalGame;

                        // BÚSQUEDA POR TÍTULO
                        // Ignoramos títulos vacíos (para evitar falsos positivos con juegos sin nombre)
                        if (!rawgTitleNorm.isEmpty() && steamGamesByTitle.containsKey(rawgTitleNorm)) {
                            JsonNode steamGame = steamGamesByTitle.get(rawgTitleNorm);
                            
                            // VALIDACIÓN: ¿Son realmente el mismo juego? (Año y Tipo)
                            if (sonElMismoJuego(steamGame, rawgGame)) {
                                finalGame = fusionarJuegos(steamGame, rawgGame, mapper);
                                
                                // Lo quitamos del mapa para no duplicarlo al final
                                steamGamesByTitle.remove(rawgTitleNorm);
                                mergedCount++;
                            } else {
                                // Conflicto: Mismo título pero distinto año/tipo (Remake vs Original)
                                ObjectNode rawgGameModificado = (ObjectNode) rawgGame.deepCopy();
                                
                                // Generamos slug único
                                String sufijo = extraerAnio(rawgGame) > 0 ? String.valueOf(extraerAnio(rawgGame)) : "rawg";
                                String nuevoSlug = rawgSlug + "-" + sufijo;
                                if (nuevoSlug.equals(rawgSlug)) nuevoSlug = rawgSlug + "-v2";
                                
                                rawgGameModificado.put("slug", nuevoSlug);
                                limpiarGaleria(rawgGameModificado);
                                
                                finalGame = rawgGameModificado;
                                rawgOnlyCount++;
                                conflictosResueltos++;
                            }
                        } else {
                            // Exclusivo de RAWG (Consola/Móvil o título diferente)
                            ObjectNode rawgGameNode = (ObjectNode) rawgGame;
                            limpiarGaleria(rawgGameNode); 
                            finalGame = rawgGameNode;
                            rawgOnlyCount++;
                        }

                        if (!primero) {
                            writer.write(",\n");
                        }
                        writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(finalGame));
                        primero = false;
                    }
                }

                // --- 3. Añadir los juegos restantes de Steam (exclusivos de PC) ---
                int steamOnlyCount = steamGamesByTitle.size();
                System.out.println("   -> Añadiendo " + steamOnlyCount + " juegos exclusivos de Steam...");
                
                for (JsonNode steamGame : steamGamesByTitle.values()) {
                    limpiarGaleria((ObjectNode) steamGame);
                    writer.write(",\n");
                    writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(steamGame));
                }

                writer.write("\n]");
                
                // --- 4. Imprimir Estadísticas Finales ---
                System.out.println("\n📊 ESTADÍSTICAS DE FUSIÓN:");
                System.out.println("   =========================================");
                System.out.println("   📥 ORIGEN:");
                System.out.println("      - Total Steam: " + totalSteamInicial);
                System.out.println("      - Total RAWG:  " + totalRawg);
                System.out.println("   -----------------------------------------");
                System.out.println("   🔄 PROCESO:");
                System.out.println("      - 🔗 Fusionados (Por Título): " + mergedCount);
                System.out.println("      - 🛡️ Conflictos Resueltos:    " + conflictosResueltos);
                System.out.println("      - 🎮 Solo en RAWG (Nuevos):   " + rawgOnlyCount);
                System.out.println("      - 💻 Solo en Steam (PC):      " + steamOnlyCount);
                System.out.println("   -----------------------------------------");
                System.out.println("   📤 RESULTADO FINAL:");
                System.out.println("      - Total Global: " + (mergedCount + rawgOnlyCount + steamOnlyCount));
                System.out.println("   =========================================");
            }

            System.out.println("\n✅ Fusión completada. Archivo: " + OUTPUT_FILE);

        } catch (Exception e) {
            e.printStackTrace();
        }
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
                    // Si hay duplicados en Steam, nos quedamos con el primero
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
        
        // Normalización Unicode: Mantiene letras y números de cualquier idioma
        StringBuilder sb = new StringBuilder();
        for (char c : title.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
    
    // --- LÓGICA DE VALIDACIÓN DE FUSIÓN ---
    
    private static boolean sonElMismoJuego(JsonNode steam, JsonNode rawg) {
        // 1. Validación de Tipo (Game vs DLC)
        String tipoSteam = steam.path("tipo").asText("game");
        String tipoRawg = rawg.path("tipo").asText("game");
        
        if (!tipoSteam.equalsIgnoreCase(tipoRawg)) {
            return false;
        }

        // 2. Validación de Año
        int anioSteam = extraerAnio(steam);
        int anioRawg = extraerAnio(rawg);
        
        if (anioSteam == 0 || anioRawg == 0) return true;
        
        int diff = Math.abs(anioSteam - anioRawg);

        // CASO A: Lanzamiento cercano (mismo año o siguiente) -> Es el mismo juego
        if (diff <= 1) return true;

        // CASO B: Lanzamiento lejano (Ports vs Remakes)
        // Como ya hemos coincidido por título exacto (porque así los buscamos),
        // solo nos queda verificar la distancia temporal.
        
        // - Si la diferencia es < 10 años -> Asumimos PORT -> FUSIONAR
        // - Si la diferencia es >= 10 años -> Asumimos REMAKE/REBOOT -> SEPARAR
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

        // 0. FECHA: Prevalece la más antigua (Original Release Date)
        String fechaSteam = base.path("fecha_lanzamiento").asText("");
        String fechaRawg = rawgGame.path("fecha_lanzamiento").asText("");
        
        if (!fechaRawg.isEmpty() && !fechaSteam.isEmpty()) {
            if (fechaRawg.compareTo(fechaSteam) < 0) {
                base.put("fecha_lanzamiento", fechaRawg);
            }
        } else if (fechaSteam.isEmpty() && !fechaRawg.isEmpty()) {
            base.put("fecha_lanzamiento", fechaRawg);
        }

        // 1. Metacritic: prevalece el más alto
        int steamMetacritic = base.path("metacritic").asInt(0);
        int rawgMetacritic = rawgGame.path("metacritic").asInt(0);
        if (rawgMetacritic > steamMetacritic) {
            base.put("metacritic", rawgMetacritic);
        }

        // 2. Plataformas
        fusionarArray(base, rawgGame, "plataformas");

        // 3. Géneros
        fusionarArray(base, rawgGame, "generos");

        // 4. Galerías
        fusionarArray(base, rawgGame, "galeria");

        // 5. Tiendas
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
                if (!storeName.equals("steam") && !storeName.equals("gog") && !storeName.equals("epic games") && !existingStoreNames.contains(storeName)) {
                    steamStores.add(storeNode);
                }
            }
        }
        
        // 6. LIMPIEZA FINAL
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