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
import java.util.HashMap;
import java.util.HashSet;
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
            System.out.println("🚀 Iniciando Fusión Global (Steam + RAWG)...");

            // --- 1. Cargar todos los juegos de Steam en un mapa por slug ---
            System.out.println("   -> Cargando juegos de Steam en memoria...");
            Map<String, JsonNode> steamGames = loadGamesToMap(STEAM_FILE, mapper);
            int totalSteamInicial = steamGames.size();
            System.out.println("   -> " + totalSteamInicial + " juegos de Steam cargados.");

            // Contadores para estadísticas
            int totalRawg = 0;
            int mergedCount = 0;
            int rawgOnlyCount = 0;

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
                        
                        String slug = rawgGame.path("slug").asText();
                        JsonNode finalGame;

                        // Si el juego de RAWG existe en Steam, fusionamos
                        if (steamGames.containsKey(slug)) {
                            JsonNode steamGame = steamGames.get(slug);
                            finalGame = fusionarJuegos(steamGame, rawgGame, mapper);
                            steamGames.remove(slug); // Lo quitamos del mapa para no duplicarlo al final
                            mergedCount++;
                        } else {
                            // Si no, es un exclusivo de consola, lo usamos tal cual
                            finalGame = rawgGame;
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
                int steamOnlyCount = steamGames.size();
                System.out.println("   -> Añadiendo " + steamOnlyCount + " juegos exclusivos de Steam...");
                
                for (JsonNode steamGame : steamGames.values()) {
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
                System.out.println("      - 🔗 Fusionados (Coinciden): " + mergedCount);
                System.out.println("      - 🎮 Solo en RAWG (Nuevos):  " + rawgOnlyCount);
                System.out.println("      - 💻 Solo en Steam (PC):     " + steamOnlyCount);
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

    private static Map<String, JsonNode> loadGamesToMap(String filePath, ObjectMapper mapper) throws Exception {
        Map<String, JsonNode> gameMap = new HashMap<>();
        JsonFactory factory = mapper.getFactory();

        try (InputStream is = new GZIPInputStream(new FileInputStream(filePath));
             JsonParser parser = factory.createParser(is)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Se esperaba un array JSON en " + filePath);
            }

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode gameNode = mapper.readTree(parser);
                String slug = gameNode.path("slug").asText();
                if (slug != null && !slug.isEmpty()) {
                    gameMap.put(slug, gameNode);
                }
            }
        }
        return gameMap;
    }

    private static JsonNode fusionarJuegos(JsonNode steamGame, JsonNode rawgGame, ObjectMapper mapper) {
        // Clonamos el juego de Steam para no modificar el original
        ObjectNode base = (ObjectNode) steamGame.deepCopy();

        // 1. Metacritic: prevalece el más alto
        int steamMetacritic = base.path("metacritic").asInt(0);
        int rawgMetacritic = rawgGame.path("metacritic").asInt(0);
        if (rawgMetacritic > steamMetacritic) {
            base.put("metacritic", rawgMetacritic);
        }

        // 2. Plataformas: añadir las de RAWG si no están en Steam
        fusionarArray(base, rawgGame, "plataformas");

        // 3. Géneros: añadir los de RAWG si no están en Steam
        fusionarArray(base, rawgGame, "generos");

        // 4. Galerías: añadir las de RAWG si no están en Steam
        fusionarArray(base, rawgGame, "galeria");

        // 5. Tiendas: lógica especial (filtrar duplicados de PC)
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
                // Añadimos si la tienda no es de PC (para evitar duplicados como "Steam") y no existe ya
                if (!storeName.equals("steam") && !storeName.equals("gog") && !storeName.equals("epic games") && !existingStoreNames.contains(storeName)) {
                    steamStores.add(storeNode);
                }
            }
        }
        
        return base;
    }

    // Método auxiliar para fusionar arrays de strings simples (plataformas, generos, galeria)
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
}
