# 🚂 VoxGamer Data Sync (Steam Adapter)

Este proyecto es el núcleo del sistema de datos de **VoxGamer**. Su función es sincronizar el catálogo completo de Steam, filtrar el contenido relevante y generar una base de datos estandarizada y agnóstica de la plataforma.

## 🏗️ Arquitectura ELT (Extract, Load, Transform)

El sistema se divide en dos procesos principales que conforman una arquitectura de datos profesional:

### 1. `SteamRawCollector` (Fase de Extracción y Carga)

Es el componente encargado de la recolección masiva y el filtrado inicial. Su objetivo es crear una copia local y robusta de los datos de Steam.

*   **Funcionamiento Detallado:**
    1.  **Carga de Estado Previo:** Al iniciar, consulta la base de datos `steam_raw.sqlite` para obtener dos listas de IDs:
        *   Juegos ya procesados y finalizados.
        *   IDs ignorados (DLCs, demos, vídeos, etc.).
    2.  **Descarga del Catálogo Completo:** Se conecta a la API de Steam para descargar la lista completa de `app_id` existentes.
    3.  **Detección de Novedades:** Compara el catálogo de Steam con los IDs locales y crea una lista de "pendientes", que incluye tanto juegos nuevos como aquellos que estaban en "Coming Soon" y podrían haber sido lanzados.
    4.  **Procesamiento en Lotes:** Itera sobre la lista de pendientes y, para cada `app_id`:
        *   Descarga su JSON de detalles desde la API de Steam.
        *   **Filtro Inteligente:** Analiza el JSON para determinar el tipo de contenido.
            *   Si es un **juego** (`"type":"game"`), lo guarda o actualiza en la tabla `steam_raw_data`.
            *   Si es cualquier otra cosa (DLC, demo, banda sonora), lo añade a la tabla `steam_ignored_ids` para no volver a consultarlo en el futuro.
    5.  **Manejo de Errores y Límites:** Está preparado para manejar errores de red y los límites de peticiones de la API de Steam (error 429), reintentando automáticamente tras un tiempo de espera.

### 2. `SteamScraper` (Fase de Transformación)

Este componente se encarga de transformar los datos crudos en un formato limpio, estandarizado y listo para ser consumido por otras aplicaciones.

*   **Funcionamiento Detallado:**
    1.  **Conexión a la Base de Datos:** Lee todos los registros de la tabla `steam_raw_data` de `steam_raw.sqlite`.
    2.  **Procesamiento Individual:** Para cada juego, realiza las siguientes transformaciones:
        *   **Validación:** Descarta juegos marcados como `"coming_soon":true` o aquellos que no tienen una fecha de lanzamiento válida.
        *   **Extracción de Datos:** Parsea manualmente el JSON para extraer campos clave como título, descripción, imagen principal, géneros, etc.
        *   **Limpieza y Estandarización:**
            *   Genera un `slug` a partir del título (ej: "The Witcher 3" -> "the-witcher-3").
            *   Convierte la fecha de lanzamiento a formato `YYYY-MM-DD`.
            *   Limpia y formatea la lista de idiomas (separando voces y textos).
            *   Extrae el `metacritic` score y el espacio en disco requerido.
    3.  **Generación del JSON Final:** Construye un objeto JSON con la estructura final y lo escribe en el fichero `steam_games.json`. El resultado es un único fichero JSON que contiene un array con todos los juegos procesados.

## 🚀 Cómo Ejecutar

### Requisitos
*   Java 17 (Amazon Corretto recomendado).
*   Gradle.

### Ejecución
Puedes ejecutar cada fase de forma independiente usando las tareas de Gradle:

1.  Abre el panel de **Gradle** en tu IDE.
2.  Ve a `Tasks` -> `Application`.
3.  Ejecuta la tarea que necesites:
    *   **`runCollector`**: Para la fase de extracción y carga.
    *   **`runScraper`**: Para la fase de transformación.

O desde la terminal:
```bash
# Para recolectar datos de Steam
./gradlew runCollector

# Para transformar los datos recolectados a JSON
./gradlew runScraper
```

## 📂 Estructura de Datos (SQLite)

El archivo `steam_raw.sqlite` contiene dos tablas clave:

*   **`steam_raw_data`**:
    *   `app_id` (PK): ID de Steam.
    *   `json_data`: El JSON completo y original devuelto por la API.
    *   `fecha_sync`: Cuándo se actualizó por última vez.

*   **`steam_ignored_ids`**:
    *   `app_id` (PK): IDs de DLCs y contenido no deseado.

## 🛠️ Tecnologías
*   **Java 17**: Lenguaje principal.
*   **SQLite**: Almacenamiento intermedio robusto y portable.
*   **Gradle**: Gestión de dependencias y tareas.
*   **Steam Web API**: Fuente de datos.

---
*VoxGamer Data Engineering Team*
