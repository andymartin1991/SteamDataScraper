# 🎮 Steam & RAWG Data Scraper

Suite de herramientas en Java para la recolección masiva, procesamiento y unificación de metadatos de videojuegos desde **Steam** y **RAWG**. Genera una base de datos unificada en formato JSON comprimido (`.json.gz`), ideal para aplicaciones offline-first como **VoxGamer**.

---

## 🚀 Arquitectura del Sistema

El sistema opera mediante un pipeline de tres etapas principales: **Recolección (Raw) -> Enriquecimiento (Detail) -> Exportación y Fusión (Scraper/Union)**.

### 1. Recolección (Collectors)
Responsables de descargar los datos crudos desde las APIs y almacenarlos en SQLite.

*   **`SteamRawCollector`**:
    *   **Fuente:** Steam Web API (`GetAppList`, `appdetails`).
    *   **Almacenamiento:** `steam_raw.sqlite`.
    *   **Lógica:** Descarga el catálogo completo (~180k+ apps). Filtra basura (demos, videos) y maneja "Coming Soon" para futuras actualizaciones.
    *   **Resiliencia:** Maneja *Rate Limits* (429) pausando la ejecución.

*   **`RAWGRawCollector`**:
    *   **Fuente:** RAWG.io API (`/games`).
    *   **Almacenamiento:** `rawg_raw.sqlite`.
    *   **Estrategia "Decenal":** Para superar el límite de 10,000 items de la API, divide las consultas por décadas de cada mes (días 1-10, 11-20, 21-fin) desde 1970 hasta hoy.
    *   **Modos:**
        *   *Llenado Masivo:* Si la BD tiene <98% del estimado, barre todo el historial.
        *   *Mantenimiento:* Solo descarga actualizaciones recientes.
    *   **Rotación de Keys:** Rota automáticamente entre múltiples API Keys para evitar bloqueos (401) y límites de cuota.

### 2. Enriquecimiento (Detail Collectors)
Completan la información básica con descripciones detalladas, tiendas y metadatos profundos.

*   **`RAWGDetailCollector`**:
    *   **Fuente:** RAWG.io API (`/games/{id}`, `/games/{id}/stores`).
    *   **Lógica Inteligente:**
        *   Prioriza juegos de **Consola/Multiplataforma** sobre exclusivos de PC (ya cubiertos por Steam).
        *   Implementa **Cooldown** de 3 días para juegos con datos vacíos, evitando reintentos inútiles constantes.
        *   Dashboard en consola con estadísticas de progreso (Pendientes vs Procesados).

### 3. Procesamiento y Exportación (Scrapers)
Transforman los datos crudos de SQLite a JSON limpio y normalizado.

*   **`SteamScraper`**:
    *   Genera: `steam_games.json.gz`.
    *   **Limpieza:** Normaliza títulos, extrae requisitos, idiomas (voces/textos) y detecta tipo (Juego/DLC).
    *   **Resolución de Conflictos:** Detecta duplicados de nombre/slug y añade sufijos (ej. ID de Steam o año) para garantizar unicidad.

*   **`RAWGScraper`**:
    *   Genera: `rawg_games.json.gz`.
    *   **Filtros de Calidad:** Descarta juegos sin fecha, futuros lanzamientos ("TBA") o sin descripción válida.
    *   **Heurística de Tipo:** Determina si es DLC basándose en `parents_count` y `playtime` (ej. si dura >2h suele ser juego standalone).
    *   **Tiendas:** Construye enlaces a tiendas (PS Store, Xbox, Nintendo) usando datos oficiales o generando búsquedas fallback.

### 4. Fusión Final (Union)
*   **`GlobalUnion`**:
    *   **Input:** `steam_games.json.gz` + `rawg_games.json.gz`.
    *   **Output:** **`global_games.json.gz`**.
    *   **Algoritmo de Fusión:**
        *   Une juegos por **Título Normalizado**.
        *   **Validación Inteligente:** Si coinciden en título pero la diferencia de años es >= 10, asume que son juegos distintos (Remake/Reboot) y los separa. Si es < 10, los fusiona (Port).
        *   **Prioridad de Datos:**
            *   *Fecha:* La más antigua.
            *   *Metacritic:* El mayor valor.
            *   *Listas:* Fusiona plataformas, géneros y galería sin duplicados.
    *   **Reportes:** Genera `conflicts_report.txt` detallando fusiones y separaciones.

### 5. Análisis
*   **`DataAnalyzer`**:
    *   Herramienta de diagnóstico que escanea los archivos `.json.gz` resultantes para detectar colisiones de Slugs o Títulos y generar métricas de calidad (`analysis_report.txt`).

---

## 🛠️ Configuración y Requisitos

### Requisitos
*   **Java JDK 17+**
*   **SQLite** (Drivers incluidos en dependencias)
*   Conexión a Internet estable.

### API Keys
El proyecto utiliza múltiples claves de API para RAWG rotativas. Se encuentran configuradas en:
*   `src/RAWGRawCollector.java`
*   `src/RAWGDetailCollector.java`
*   `src/SteamRawCollector.java` (Clave de Steam)

---

## ▶️ Flujo de Ejecución Recomendado

Para realizar una actualización completa de la base de datos:

1.  **Recolección de Datos (Raw):**
    ```bash
    # Descargar catálogo de Steam
    ./gradlew SteamRawCollector.main()

    # Descargar catálogo de RAWG (puede tardar días la primera vez)
    ./gradlew RAWGRawCollector.main()
    ```

2.  **Enriquecimiento (Details):**
    ```bash
    # Descargar detalles faltantes de RAWG (ejecutar en segundo plano)
    ./gradlew RAWGDetailCollector.main()
    ```

3.  **Generación de JSONs Intermedios:**
    ```bash
    # Exportar datos de Steam
    ./gradlew SteamScraper.main()

    # Exportar datos de RAWG
    ./gradlew RAWGScraper.main()
    ```

4.  **Fusión Global:**
    ```bash
    # Generar archivo maestro unificado
    ./gradlew GlobalUnion.main()
    ```

5.  **(Opcional) Análisis de Calidad:**
    ```bash
    ./gradlew DataAnalyzer.main()
    ```

El resultado final estará en **`global_games.json.gz`**.

---

## 📂 Estructura del JSON Final

Cada objeto en `global_games.json.gz` sigue este esquema unificado:

```json
{
  "slug": "half-life-2",
  "titulo": "Half-Life 2",
  "tipo": "game",
  "descripcion_corta": "The Seven Hour War is lost...",
  "fecha_lanzamiento": "2004-11-16",
  "storage": "6500 MB",
  "generos": ["Shooter", "Action"],
  "plataformas": ["PC", "Xbox 360", "PlayStation 3", "Android"],
  "img_principal": "https://media.rawg.io/...",
  "galeria": [
    "https://media.rawg.io/...",
    "https://cdn.akamai.steamstatic.com/..."
  ],
  "idiomas": {
    "voces": ["English"],
    "textos": ["English", "Spanish", "French"]
  },
  "metacritic": 96,
  "tiendas": [
    {
      "tienda": "Steam",
      "id_externo": "220",
      "url": "https://store.steampowered.com/app/220",
      "is_free": false
    },
    {
      "tienda": "Xbox Store",
      "id_externo": "...",
      "url": "https://www.xbox.com/...",
      "is_free": false
    }
  ]
}
```
