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
    private static final String PARQUET_OUTPUT_FILE = "juegos.parquet";

    public static void main(String[] args) {
        System.out.println("🚀 Iniciando conversión de JSON a Parquet...");

        // 1. Definir el Esquema AVRO que Parquet usará para estructurar los datos.
        // Este esquema asegura que los datos sean consistentes y optimizados.
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
            // 2. Leer el archivo JSON usando la librería Jackson
            File inputFile = new File(JSON_INPUT_FILE);
            if (!inputFile.exists() || inputFile.length() < 10) { // <10 para descartar JSON vacíos
                System.err.println("❌ Error: El archivo " + JSON_INPUT_FILE + " no existe o está vacío. Ejecuta SteamScraper primero.");
                return;
            }
            
            ObjectMapper objectMapper = new ObjectMapper();
            List<Map<String, Object>> juegos = objectMapper.readValue(inputFile, new TypeReference<List<Map<String, Object>>>() {});
            System.out.println("✅ JSON leído correctamente. Se encontraron " + juegos.size() + " juegos.");

            // 3. Configurar y crear el ParquetWriter
            Path outputPath = new Path(PARQUET_OUTPUT_FILE);
            
            // Borrar archivo antiguo si existe para evitar errores de Hadoop
            File oldParquet = new File(PARQUET_OUTPUT_FILE);
            if(oldParquet.exists()) {
                System.out.println("🧹 Borrando archivo Parquet antiguo...");
                oldParquet.delete();
            }

            ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(outputPath)
                    .withSchema(schema)
                    .withConf(new Configuration())
                    .withCompressionCodec(CompressionCodecName.GZIP) // Usamos compresión GZIP para que ocupe menos
                    .build();

            // 4. Iterar sobre cada juego y escribirlo en el archivo Parquet
            System.out.println("✍️ Escribiendo datos en formato Parquet... (esto puede tardar si el archivo es grande)");
            for (Map<String, Object> juegoMap : juegos) {
                GenericRecord record = new GenericData.Record(schema);
                // Mapeamos cada campo del JSON al esquema del Parquet
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
            System.out.println("🏁 ¡Éxito! Archivo guardado en: " + PARQUET_OUTPUT_FILE);
            System.out.println("   -> Este archivo ya está listo para ser subido a GitHub.");

        } catch (IOException e) {
            System.err.println("❌ ERROR FATAL durante la conversión:");
            e.printStackTrace();
        }
    }
}