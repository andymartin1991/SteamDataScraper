import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class JsonToParquet {

    private static final String JSON_INPUT_FILE = "juegos_nuevos.json";
    // Nombre actualizado para el archivo final
    private static final String PARQUET_OUTPUT_FILE = "SteamGames.parquet";

    public static void main(String[] args) {
        System.out.println("🚀 Iniciando conversión de JSON a Parquet...");

        // 1. Definir el Esquema AVRO
        String schemaJson = "{\"namespace\": \"com.steamscraper.avro\", " +
                            "\"type\": \"record\", " +
                            "\"name\": \"Juego\", " +
                            "\"fields\": [" +
                            "    {\"name\": \"id\", \"type\": \"int\"}," +
                            "    {\"name\": \"titulo\", \"type\": [\"string\", \"null\"]}," +
                            "    {\"name\": \"fecha\", \"type\": [\"string\", \"null\"]}," +
                            "    {\"name\": \"size\", \"type\": [\"string\", \"null\"]}," +
                            "    {\"name\": \"url_steam\", \"type\": [\"string\", \"null\"]}," +
                            "    {\"name\": \"img\", \"type\": [\"string\", \"null\"]}," +
                            "    {\"name\": \"supported_languages_raw\", \"type\": [\"string\", \"null\"]}," +
                            "    {\"name\": \"idiomas_texto\", \"type\": {\"type\": \"array\", \"items\": \"string\"}}," +
                            "    {\"name\": \"idiomas_voces\", \"type\": {\"type\": \"array\", \"items\": \"string\"}}" +
                            "]}";
        Schema schema = new Schema.Parser().parse(schemaJson);

        try {
            // 2. Leer el archivo JSON
            File inputFile = new File(JSON_INPUT_FILE);
            if (!inputFile.exists() || inputFile.length() < 10) { 
                System.err.println("❌ Error: El archivo " + JSON_INPUT_FILE + " no existe o está vacío.");
                return;
            }
            
            ObjectMapper objectMapper = new ObjectMapper();
            List<Map<String, Object>> juegos = objectMapper.readValue(inputFile, new TypeReference<List<Map<String, Object>>>() {});
            System.out.println("✅ JSON leído correctamente. Se encontraron " + juegos.size() + " juegos.");

            // 3. Configurar ParquetWriter
            Path outputPath = new Path(PARQUET_OUTPUT_FILE);
            
            File oldParquet = new File(PARQUET_OUTPUT_FILE);
            if(oldParquet.exists()) {
                System.out.println("🧹 Borrando archivo Parquet antiguo (" + PARQUET_OUTPUT_FILE + ")...");
                oldParquet.delete();
            }

            ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(outputPath)
                    .withSchema(schema)
                    .withConf(new Configuration())
                    .withCompressionCodec(CompressionCodecName.GZIP) 
                    .build();

            // 4. Escribir datos
            System.out.println("✍️ Escribiendo datos en " + PARQUET_OUTPUT_FILE + "...");
            for (Map<String, Object> juegoMap : juegos) {
                GenericRecord record = new GenericData.Record(schema);
                record.put("id", juegoMap.get("id"));
                record.put("titulo", juegoMap.get("titulo"));
                record.put("fecha", juegoMap.get("fecha"));
                record.put("size", juegoMap.get("size"));
                record.put("url_steam", juegoMap.get("url_steam"));
                record.put("img", juegoMap.get("img"));
                record.put("supported_languages_raw", juegoMap.get("supported_languages_raw"));
                record.put("idiomas_texto", juegoMap.get("idiomas_texto"));
                record.put("idiomas_voces", juegoMap.get("idiomas_voces"));
                writer.write(record);
            }

            writer.close();
            System.out.println("🏁 ¡Éxito! Archivo guardado como: " + PARQUET_OUTPUT_FILE);

        } catch (IOException e) {
            System.err.println("❌ ERROR FATAL durante la conversión:");
            e.printStackTrace();
        }
    }
}