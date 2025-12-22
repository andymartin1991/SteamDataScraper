# 🎮 Steam & RAWG Data Scraper

Este proyecto es una suite de herramientas en Java diseñada para recolectar, procesar y unificar metadatos de videojuegos desde **Steam** y **RAWG**. Su objetivo es generar una base de datos masiva y limpia (`JSON`) para alimentar aplicaciones offline-first como **VoxGamer**.

---

## 🚀 Arquitectura del Proyecto

El sistema funciona mediante una "tubería" (pipeline) de tres etapas: **Recolección (Raw) -> Enriquecimiento (Detail) -> Exportación (Scraper)**.

### 1. Recolección (Collectors)
Descargan los datos crudos de las APIs y los almacenan en bases de datos SQLite locales.

*   **`SteamRawCollector`**:
    *   Descarga el catálogo completo de Steam (~180k apps), incluyendo juegos y DLCs.
    *   Guarda el JSON crudo en `steam_raw.sqlite`.
    *   *Estrategia:* Barrido secuencial de IDs.

*   **`RAWGRawCollector`**:
    *   **El colector más avanzado del proyecto.** Su misión es descargar el catálogo histórico completo de RAWG (~900k juegos) de la forma más robusta posible.
    *   **Modo Dual Inteligente:**
        *   **Modo Llenado Masivo:** Si detecta que la base de datos local tiene menos del 95% del catálogo, activa un barrido histórico exhaustivo.
        *   **Modo Mantenimiento:** Si la base de datos está casi completa, solo descarga las últimas actualizaciones para mantenerla al día.
    *   **Estrategia de Barrido Decenal:** Para evitar los límites de paginación de la API (~10.000 items), el modo masivo divide cada mes en 3 "decenas" (1-10, 11-20, 21-fin), garantizando la captura del 100% del catálogo.
    *   **Progreso Persistente y Reanudable:** Guarda el progreso página por página para cada decena en la tabla `rawg_progress_decenal`. Si el script se detiene, reanudará la descarga exactamente donde la dejó, ahorrando miles de peticiones.
    *   **Rotación de API Keys:** Utiliza una lista de claves API. Si una clave es bloqueada (error 401) o excede su cuota (error 429), rota automáticamente a la siguiente, permitiendo un funcionamiento desatendido durante días.

### 2. Enriquecimiento (Detail Collectors)
Completan la información de los juegos que solo tienen datos básicos.

*   **`RAWGDetailCollector`**:
    *   Escanea `rawg_raw.sqlite` buscando juegos sin descripción o tiendas.
    *   Descarga los detalles completos (`/games/{id}`) y tiendas (`/stores`).
    *   También implementa **rotación de API Keys** para máxima resiliencia.
    *   *Inteligencia:* Si un juego sigue incompleto, aplica un "cooldown" de 3 días antes de volver a intentarlo.

### 3. Exportación y Fusión (Scrapers & Union)
Procesan los datos crudos, los limpian y generan el archivo final.

*   **`SteamScraper`**:
    *   Lee `steam_raw.sqlite`.
    *   Limpia textos, extrae imágenes, requisitos, idiomas y el tipo de producto (juego/dlc).
    *   Genera `steam_games.json.gz`.

*   **`RAWGScraper`**:
    *   Lee `rawg_raw.sqlite` (fusionando datos básicos + detalles).
    *   **Filtro de Calidad:** Solo exporta juegos que tengan descripción corta válida.
    *   Detecta si es **Juego** o **DLC**.
    *   Genera `rawg_games.json.gz`.

*   **`GlobalUnion`**:
    *   **El paso final.**
    *   Lee `steam_games.json.gz` y `rawg_games.json.gz`.
    *   Fusiona ambos catálogos eliminando duplicados (priorizando Steam para datos de PC).
    *   Genera el archivo maestro: **`global_games.json.gz`**.

---

## 🛠️ Configuración

### Requisitos
*   Java JDK 17+
*   Maven o Gradle (incluido en el wrapper)
*   Claves de API válidas para RAWG.

### Claves de API
Las claves están hardcodeadas en una lista dentro de las clases. Si necesitas cambiarlas o añadirlas, modifica el array `API_KEYS` en:
*   `src/RAWGRawCollector.java`
*   `src/RAWGDetailCollector.java`

---

## ▶️ Cómo Ejecutar (Flujo Completo)

Para una actualización completa desde cero o mantenimiento diario:

1.  **Recolectar Steam:**
    ```bash
    ./gradlew SteamRawCollector.main()
    ```
2.  **Recolectar RAWG (Lista Completa):**
    ```bash
    ./gradlew RAWGRawCollector.main()
    ```
    *(Nota: La primera vez tardará días en bajar los ~900k juegos. Es reanudable, puedes pararlo y seguir en cualquier momento).*

3.  **Enriquecer RAWG (Detalles):**
    ```bash
    ./gradlew RAWGDetailCollector.main()
    ```
    *(Nota: Se ejecuta en segundo plano para ir completando descripciones. Tardará semanas en completar todo el catálogo).*

4.  **Generar JSONs Intermedios:**
    ```bash
    ./gradlew SteamScraper.main()
    ./gradlew RAWGScraper.main()
    ```

5.  **Fusión Final:**
    ```bash
    ./gradlew GlobalUnion.main()
    ```

El resultado será un archivo **`global_games.json.gz`** listo para ser consumido por la app VoxGamer.

---

## 📂 Estructura de Datos (JSON Final)

Cada juego en el JSON final tiene este formato unificado:

```json
{
  "slug": "half-life-2",
  "titulo": "Half-Life 2",
  "tipo": "game",  // o "dlc"
  "descripcion_corta": "The Seven Hour War is lost...",
  "fecha_lanzamiento": "2004-11-16",
  "storage": "6500 MB",
  "generos": ["Shooter", "Action"],
  "plataformas": ["PC", "Xbox 360", "PlayStation 3"],
  "img_principal": "https://...",
  "galeria": ["url1", "url2"],
  "idiomas": {
    "voces": ["English"],
    "textos": ["English", "Spanish"]
  },
  "metacritic": 96,
  "tiendas": [
    {
      "tienda": "Steam",
      "id_externo": "220",
      "url": "https://store.steampowered.com/app/220",
      "is_free": false
    }
  ]
}
```
