# 🎮 Steam & RAWG Data Scraper

Suite de herramientas en Java para la recolección masiva, procesamiento y unificación de metadatos de videojuegos desde **Steam** y **RAWG**. Genera una base de datos unificada en formato JSON comprimido (`.json.gz`), ideal para aplicaciones offline-first como **VoxGamer**.

---

## 🚀 Arquitectura del Sistema

El sistema opera mediante un pipeline de tres etapas principales: **Recolección (Raw) -> Enriquecimiento (Detail) -> Exportación y Fusión (Scraper/Union)**.

### 1. Recolección (Collectors)
Responsables de descargar los datos crudos de las APIs y almacenarlos en SQLite.

*   **`SteamRawCollector`**:
    *   **Fuente:** Steam Web API.
    *   **Almacenamiento:** `steam_raw.sqlite`.
    *   **Lógica:** Descarga el catálogo completo (~180k+ apps). Filtra basura y maneja "Coming Soon".
    *   **Resiliencia:** Maneja *Rate Limits* (429) pausando la ejecución.

*   **`RAWGRawCollector`**:
    *   **Fuente:** RAWG.io API (`/games`).
    *   **Almacenamiento:** `rawg_raw.sqlite`.
    *   **Estrategia "Decenal":** Divide las consultas por décadas de cada mes para superar el límite de 10,000 items.
    *   **Modos:** *Llenado Masivo* (histórico completo) y *Mantenimiento* (solo actualizaciones).
    *   **Rotación de Keys:** Rota automáticamente entre múltiples API Keys.

### 2. Enriquecimiento (Detail Collectors)
Completan la información básica con descripciones detalladas, tiendas y metadatos profundos.

*   **`RAWGDetailCollector`**:
    *   **Fuente:** RAWG.io API (`/games/{id}`, `/games/{id}/stores`).
    *   **Lógica Inteligente:** Prioriza juegos de consola/multiplataforma. Implementa *Cooldown* para reintentos fallidos.

### 3. Procesamiento y Exportación (Scrapers)
Transforman los datos crudos de SQLite a JSON limpio y normalizado.

*   **`SteamScraper`**:
    *   Genera: `steam_games.json.gz`.
    *   **Novedades:**
        *   🎬 **Extracción de Videos:** Obtiene trailers en MP4/WebM (480p/Max).
        *   🏢 **Metadatos:** Extrae Desarrolladores y Editores.
    *   **Limpieza:** Normaliza títulos, extrae requisitos, idiomas y detecta tipo (Juego/DLC).

*   **`RAWGScraper`**:
    *   Genera: `rawg_games.json.gz`.
    *   **Robustez:** Implementa *fallback* al detalle si faltan datos (devs/publishers) en la lista básica.
    *   **Tiendas:** Construye enlaces a tiendas de consola (PS Store, Xbox, Nintendo).

*   **`UpcomingGamesScraper` (NUEVO)**:
    *   Genera: `proximos_games.json.gz`.
    *   **Propósito:** Crea una lista de próximos lanzamientos, enfocada en consolas.
    *   **Filtros:**
        *   **Fecha:** Solo incluye juegos con fecha de lanzamiento futura o marcados como "TBA".
        *   **Plataforma:** Descarta juegos que son **exclusivos de PC**.

### 4. Fusión Final (Union)
*   **`GlobalUnion`**:
    *   **Input:** `steam_games.json.gz` + `rawg_games.json.gz`.
    *   **Output:** **`global_games.json.gz`**.
    *   **Algoritmo de Fusión en 2 Pasadas:**
        1.  **Fusión Exacta:** Por Título Normalizado.
        2.  **Fusión Inteligente (Fuzzy):** Para juegos con títulos ligeramente distintos (ej. "LEGO Batman" vs "LEGO Batman: The Videogame").
            *   *Criterios:* Diferencia de Año <= 1 **Y** Mismo Desarrollador **Y** Título parcial.
    *   **Seguridad Estricta:** Nunca fusiona un **Juego** con un **DLC**, incluso si coinciden en título o desarrollador.
    *   **Prioridad de Datos:**
        *   *Base:* Steam (manda en videos, descripción, etc.).
        *   *Listas:* Unión sin duplicados (Plataformas, Géneros, Devs, Editores).
        *   *Tiendas:* Añade tiendas de terceros (GOG, Epic) desde RAWG, pero bloquea duplicados de Steam.

### 5. Análisis
*   **`DataAnalyzer`**: Herramienta de diagnóstico para detectar colisiones y validar la calidad del JSON final.

---

## 🛠️ Configuración y Requisitos

### Requisitos
*   **Java JDK 17+**
*   **SQLite** (Drivers incluidos)
*   Conexión a Internet estable.

### API Keys
Configuradas en:
*   `src/RAWGRawCollector.java`
*   `src/RAWGDetailCollector.java`
*   `src/SteamRawCollector.java`

---

## ▶️ Flujo de Ejecución Recomendado

### Flujo Principal (Juegos Lanzados)

1.  **Recolección (Raw):**
    ```bash
    ./gradlew SteamRawCollector.main()
    ./gradlew RAWGRawCollector.main()
    ```

2.  **Enriquecimiento (Details):**
    ```bash
    ./gradlew RAWGDetailCollector.main()
    ```

3.  **Generación de JSONs Intermedios:**
    ```bash
    ./gradlew SteamScraper.main()
    ./gradlew RAWGScraper.main()
    ```

4.  **Fusión Global:**
    ```bash
    ./gradlew GlobalUnion.main()
    ```

### Flujo Secundario (Próximos Lanzamientos)

Para generar la lista de próximos lanzamientos de consola:
```bash
./gradlew UpcomingGamesScraper.main()
```

---

## 📂 Estructura del JSON Final

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
  "desarrolladores": ["Valve"],
  "editores": ["Valve"],
  "img_principal": "https://media.rawg.io/...",
  "galeria": [
    "https://media.rawg.io/...",
    "https://cdn.akamai.steamstatic.com/..."
  ],
  "videos": [
    {
      "titulo": "Launch Trailer",
      "thumbnail": "https://...",
      "url": "http://.../movie_max.mp4"
    }
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
