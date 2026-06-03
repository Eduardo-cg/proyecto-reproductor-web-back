# FonoPort - Backend

Backend reactivo para plataforma de streaming de música estilo Spotify. Construido con Spring WebFlux, R2DBC y PostgreSQL, siguiendo arquitectura hexagonal por bounded contexts.

## Características

- **Streaming de audio con Range requests** - Soporte completo para HTTP Range (206 Partial Content) con lectura por chunks (64KB random, 256KB secuencial)
- **Carga multipart** - Subida de archivos de audio (hasta 500MB), portadas de tracks/álbumes e imágenes de artistas
- **Procesamiento de imágenes** - Redimensionamiento automático de portadas con Caffeine cache (TTL 1h)
- **Descarga ZIP** - Álbumes y artistas descargables como archivos ZIP con estructura de carpetas
- **Gestión de almacenamiento** - Límites por rol con tracking de uso; ADMIN sin límite, STANDARD con 512MB
- **Búsqueda sin acentos** - Extensión PostgreSQL `unaccent()` para buscar "cafe" y encontrar "café"
- **Filtrado y ordenamiento avanzado** - Tracks y álbumes soportan búsqueda por texto, filtrado por artista/álbum, y sorting por título, artista, año o duración
- **Rate limiting** - Implementación custom en endpoints de login (10/min) y registro (5/min) por IP
- **Autenticación JWT** - Tokens HS256 con expiración de 24 horas
- **Aislamiento de datos** - Todas las queries filtran por `user_id`; `OwnershipValidator` protege recursos
- **Health monitoring** - `StorageHealthIndicator` verifica espacio en disco + Actuator en puerto 8081
- **Apagado graceful** - Timeout de 30 segundos para cierre ordenado

## Stack Tecnológico

| Categoría | Tecnología | Versión |
|---|---|---|
| Lenguaje | Java | 21 (Virtual Threads) |
| Framework | Spring Boot | 3.4.0 |
| Web | Spring WebFlux (Netty) | Reactivo (non-blocking) |
| Base de datos | PostgreSQL | 16 |
| Driver DB | R2DBC + r2dbc-postgresql | Reactivo |
| Seguridad | Spring Security WebFlux | Filtro reactivo |
| Auth | JWT (JJWT) | 0.12.5 (HS256) |
| Caché | Caffeine + Spring Cache | In-memory, 1h TTL |
| Resiliencia | Custom Rate Limiter | ConcurrentHashMap + sliding window |
| Métricas | Spring Actuator + Micrometer + Prometheus | Metrics/health |
| Build | Maven | 3.9+ |
| Validación | Jakarta Validation | Spring Boot starter |
| Boilerplate | Lombok | Reducción de código |
| Procesamiento archivos | Apache Tika 2.9.2 + Commons IO 2.16.1 | MIME detection |
| Containerización | Docker + Docker Compose | Multi-stage build |

## Arquitectura

Hexagonal por bounded contexts:

```
src/main/java/com/musicstreaming/
├── Application.java                         # Entry point (@EnableR2dbcRepositories)
├── auth/                                    # Auth bounded context
│   ├── controller/AuthController.java       # Registro, login, usuario actual, storage
│   ├── dto/                                 # RegisterRequest, LoginRequest, LoginResponse, UserResponse, UserPrincipal, StorageUsageResponse
│   ├── entity/                              # User, Role
│   ├── repository/                          # UserRepository, RoleRepository (R2DBC)
│   └── service/                             # AuthService, StorageService
├── track/                                   # Track bounded context
│   ├── controller/TrackController.java      # CRUD, stream, download tracks
│   ├── dto/TrackDTO.java
│   ├── entity/                              # Track, TrackArtist
│   ├── repository/                          # TrackRepository, TrackArtistRepository
│   └── service/TrackService.java
├── album/                                   # Album bounded context
│   ├── controller/AlbumController.java      # CRUD albums, agregar/quitar/reordenar tracks, download
│   ├── dto/                                 # AlbumDTO, AlbumWithTracksDTO
│   ├── entity/                              # Album, AlbumTrack, AlbumArtist
│   ├── repository/                          # AlbumRepository, AlbumTrackRepository, AlbumArtistRepository
│   └── service/AlbumService.java
├── artist/                                  # Artist bounded context
│   ├── controller/ArtistController.java     # CRUD artistas, tracks/albums, download
│   ├── dto/ArtistDTO.java
│   ├── entity/Artist.java
│   ├── repository/ArtistRepository.java
│   └── service/                             # ArtistService, ArtistLinkService
└── common/                                  # Infraestructura compartida
    ├── cache/                               # CacheConfig, CoverService (Caffeine)
    ├── config/                              # SecurityConfig, RateLimitConfig, JwtSecretValidator, StorageHealthIndicator
    ├── dto/PageResponse.java                # Respuesta paginada genérica
    ├── exception/                           # ResourceNotFoundException, ResourceAlreadyExistsException, UnauthorizedException, StorageLimitExceededException
    ├── handler/GlobalExceptionHandler.java  # @RestControllerAdvice
    ├── security/                            # JwtTokenProvider, JwtAuthenticationFilter, ReactiveUserDetailsServiceImpl
    ├── service/                             # ReactiveFileService, ZipDownloadService
    └── util/                                # FileUtils, ImageUtils, RangeHeaderParser, OwnershipValidator, SortHelper, JsonParamParser, ParserUtils, ResponseHeaderHelper
```

## Prerrequisitos

- Java 21+
- Docker (para PostgreSQL)
- Maven 3.9+

## Inicio Rápido

### 1. Arrancar PostgreSQL

Desde la raíz del proyecto (`ProyectoDAW/`):

```bash
docker compose -f docker-compose.local.yml up -d
```

### 2. Configurar almacenamiento

En perfil `dev` corriendo el backend directo en Windows (sin Docker), los audios se guardan por defecto en `C:/temp/music-storage`. En `dev` y `prod` corriendo dentro de Docker, la ruta unificada es `/var/music/audio` (montada como volumen named `music_storage` y configurada vía `STORAGE_PATH` en `docker-compose.override.yml` / `docker-compose.prod.yml`). Creá la carpeta según cómo vayas a ejecutar:

```bash
mkdir C:/temp/music-storage
```

O cambialo en `application-dev.yml`:

```yaml
app:
  storage:
    base-path: tu/ruta/personalizada
```

### 3. Compilar y ejecutar

```bash
mvn spring-boot:run
```

Activa el perfil `dev` por defecto. Para producción:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

La aplicación queda disponible en `http://localhost:8080`.

## Endpoints API

### Autenticación (`/api/auth`)

Públicos excepto `/me` y `/storage`.

| Método | Path | Descripción | Auth | Rate Limit |
|--------|------|-------------|------|------------|
| POST | `/api/auth/register` | Registrar usuario nuevo | No | 5 req/min |
| POST | `/api/auth/login` | Login, devuelve JWT | No | 10 req/min |
| GET | `/api/auth/me` | Usuario actual autenticado | Sí | - |
| GET | `/api/auth/storage` | Uso de almacenamiento (used/total/available) | Sí | - |

**Request body - Registro:**

```json
{
  "username": "juan",
  "email": "juan@example.com",
  "password": "secreto123"
}
```

**Request body - Login:**

```json
{
  "username": "juan",
  "password": "secreto123"
}
```

**Response - Login:**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 86400000,
  "user": {
    "id": 1,
    "username": "juan",
    "email": "juan@example.com",
    "roleName": "STANDARD"
  }
}
```

### Tracks (`/api/tracks`)

Requiere autenticación.

| Método | Path | Descripción |
|--------|------|-------------|
| POST | `/api/tracks` | Subir track (multipart) |
| GET | `/api/tracks` | Listar tracks (paginado, con filtros) |
| GET | `/api/tracks/count` | Cantidad total de tracks |
| GET | `/api/tracks/{id}` | Metadata del track |
| PUT | `/api/tracks/{id}` | Actualizar track (multipart) |
| DELETE | `/api/tracks/{id}` | Eliminar track |
| GET | `/api/tracks/{id}/stream` | Streaming con soporte Range (206) |
| GET | `/api/tracks/{id}/download` | Descargar archivo de audio |

**GET `/api/tracks` - Parámetros de query:**

| Parámetro | Tipo | Default | Descripción |
|-----------|------|---------|-------------|
| `search` | String | - | Buscar por título |
| `artistIds` | List\<Long\> | - | Filtrar por IDs de artistas |
| `albumIds` | List\<Long\> | - | Filtrar por IDs de álbumes |
| `page` | int | 0 | Página (0-indexed) |
| `size` | int | 20 | Tamaño de página |
| `sortBy` | String | `"title"` | Campo de ordenamiento |
| `sortDirection` | String | `"asc"` | Dirección (`asc`/`desc`) |

**POST `/api/tracks` - Campos multipart:**

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `title` | String | Sí | Título del track |
| `file` | FilePart | Sí | Archivo de audio |
| `duration` | String | Sí | Duración en segundos |
| `cover` | FilePart | No | Imagen de portada |
| `artistIds` | String (JSON) | No | Array JSON de IDs de artistas (ej: `"[1,2]"`) |
| `album` | String | No | Nombre del álbum |
| `releaseDate` | String | No | Fecha de lanzamiento |

### Albums (`/api/albums`)

Requiere autenticación.

| Método | Path | Descripción |
|--------|------|-------------|
| POST | `/api/albums` | Crear álbum (multipart) |
| GET | `/api/albums` | Listar álbumes (paginado, con filtros) |
| GET | `/api/albums/list` | Listado simplificado (para selectores) |
| GET | `/api/albums/{id}` | Detalle del álbum con sus tracks |
| PUT | `/api/albums/{id}` | Actualizar álbum (multipart) |
| DELETE | `/api/albums/{id}` | Eliminar álbum (cascada) |
| POST | `/api/albums/{id}/tracks` | Agregar track al álbum (multipart) |
| DELETE | `/api/albums/{id}/tracks/{trackId}` | Quitar track del álbum |
| PUT | `/api/albums/{id}/tracks/reorder` | Reordenar tracks (JSON body) |
| GET | `/api/albums/{id}/download` | Descargar álbum como ZIP |

**GET `/api/albums` - Parámetros de query:**

| Parámetro | Tipo | Default | Descripción |
|-----------|------|---------|-------------|
| `search` | String | - | Buscar por título |
| `artistIds` | List\<Long\> | - | Filtrar por IDs de artistas |
| `page` | int | 0 | Página (0-indexed) |
| `size` | int | 20 | Tamaño de página |
| `sortBy` | String | `"title"` | Campo de ordenamiento |
| `sortDirection` | String | `"asc"` | Dirección (`asc`/`desc`) |

**POST `/api/albums` - Campos multipart:**

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `title` | String | Sí | Título del álbum |
| `artistIds` | String (JSON) | No | Array JSON de IDs de artistas |
| `releaseDate` | String | No | Fecha de lanzamiento |
| `cover` | FilePart | No | Imagen de portada |

**POST `/api/albums/{id}/tracks` - Campos multipart:**

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `title` | String | Sí | Título del track |
| `file` | FilePart | Sí | Archivo de audio |
| `duration` | String | Sí | Duración en segundos |
| `artistIds` | String (JSON) | No | Array JSON de IDs de artistas |
| `position` | String | No | Posición/orden en el álbum |
| `releaseDate` | String | No | Fecha de lanzamiento |

**PUT `/api/albums/{id}/tracks/reorder` - Request body:**

```json
[3, 1, 2]
```

### Artists (`/api/artists`)

Requiere autenticación.

| Método | Path | Descripción |
|--------|------|-------------|
| POST | `/api/artists` | Crear artista (multipart) |
| GET | `/api/artists` | Listar artistas (paginado, con búsqueda) |
| GET | `/api/artists/list` | Listado simplificado (para selectores) |
| GET | `/api/artists/{id}` | Detalle del artista |
| PUT | `/api/artists/{id}` | Actualizar artista (multipart) |
| DELETE | `/api/artists/{id}` | Eliminar artista |
| GET | `/api/artists/{id}/tracks` | Tracks del artista (paginado) |
| GET | `/api/artists/{id}/albums` | Albums del artista (paginado) |
| GET | `/api/artists/{id}/download` | Descargar tracks del artista como ZIP |

**GET `/api/artists` - Parámetros de query:**

| Parámetro | Tipo | Default | Descripción |
|-----------|------|---------|-------------|
| `search` | String | - | Buscar por nombre |
| `page` | int | 0 | Página (0-indexed) |
| `size` | int | 20 | Tamaño de página |

**POST `/api/artists` - Campos multipart:**

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `name` | String | Sí | Nombre del artista |
| `image` | FilePart | No | Imagen del artista |

### Actuator (Puerto 8081)

| Método | Path | Descripción | Auth |
|--------|------|-------------|------|
| GET | `/actuator/health` | Health check (incluye disco + R2DBC) | No |
| GET | `/actuator/health/liveness` | Probe de Liveness (K8s) | No |
| GET | `/actuator/health/readiness` | Probe de Readiness (K8s) | No |
| GET | `/actuator/prometheus` | Métricas Prometheus | Sí |
| GET | `/actuator/info` | Info de la aplicación | Sí |
| GET | `/actuator/metrics` | Métricas de la aplicación | Sí |

> El límite de subida (500 MB) se aplica vía `MultipartWebFluxConfig`, que lee `app.storage.max-file-size` y configura el `MultipartHttpMessageReader` de WebFlux. En stack reactivo, `spring.servlet.multipart.*` **no se aplica** y debe ignorarse.

## Modelo de Datos

```sql
roles              (id, name, storage_limit_bytes)
users              (id, username, email, password_hash, role_id, created_at, updated_at)
artists            (id, name, image_path, user_id, created_at, updated_at)
albums             (id, title, release_date, cover_path, user_id, created_at, updated_at)
tracks             (id, title, album, duration, file_path, file_size, cover_path, user_id, release_date, created_at, updated_at)
album_tracks       (id, album_id, track_id, position, created_at)
track_artists      (id, track_id, artist_id, position, created_at)
album_artists      (id, album_id, artist_id, position, created_at)

```

**Roles por defecto:**
- `ADMIN` (id=1): Almacenamiento ilimitado (verifica espacio en disco)
- `STANDARD` (id=2): 512 MB de límite

## Autenticación

- **JWT HS256** con expiración de 24 horas
- Token enviado via header `Authorization: Bearer <token>`
- Para endpoints de streaming (`/stream`): también acepta `?token=` como query param (para players HTML)
- **Passwords**: Hasheados con BCrypt
- **Aislamiento**: Todas las queries filtran por `user_id`

**Roles:**

| Rol | Almacenamiento | Descripción |
|-----|----------------|-------------|
| ADMIN | Sin límite (verifica disco) | Administrador del sistema |
| STANDARD | 512 MB | Usuario estándar |

## Streaming de Audio

`GET /api/tracks/{id}/stream` soporta **HTTP Range Requests** (206 Partial Content):

- Sin header `Range`: archivo completo
- Con `Range: bytes=0-1023`: primeros 1024 bytes
- Usa `RandomAccessFile` + `Flux.generate` para lectura por chunks (64KB random, 256KB secuencial)
- `subscribeOn(Schedulers.boundedElastic())` para no bloquear el event loop de Netty
- `ZeroCopyHttpOutputMessage` cuando está disponible (zero-copy transfer)

```bash
curl -H "Authorization: Bearer <token>" \
     -H "Range: bytes=0-1048575" \
     http://localhost:8080/api/tracks/1/stream
```

## Caché

Cobertura de imágenes vía **Caffeine** con TTL de 1 hora:

| Cache | Entradas | Descripción |
|-------|----------|-------------|
| `artistImageCache` | 300 | Imágenes de artistas |
| `trackCoverCache` | 500 | Portadas de tracks |
| `albumCoverCache` | 200 | Portadas de álbumes |
| `artistTrackCoverCache` | 300 | Portadas de tracks por artista |
| `fileMetadataCache` | 2000 | MIME types y tamaños de archivos |

## Configuración

### Variables de Entorno

| Variable | Default (dev) | Default (prod) | Descripción |
|----------|---------------|----------------|-------------|
| `DB_HOST` | localhost | (sin default) | Host PostgreSQL |
| `DB_PORT` | 5432 | (sin default) | Puerto PostgreSQL |
| `DB_NAME` | musicdb | (sin default) | Nombre de base de datos |
| `DB_USER` | postgres | (sin default) | Usuario PostgreSQL |
| `DB_PASSWORD` | postgres | (sin default) | Contraseña PostgreSQL |
| `JWT_SECRET` | placeholder genérico (mín 32 chars) | **requerido** (falla sin env) | Secreto para firmar JWT HS256 |
| `STORAGE_PATH` | `C:/temp/music-storage` (sin Docker) / `/var/music/audio` (con Docker) | `/var/music/audio` | Ruta de almacenamiento de archivos |
| `CORS_ORIGINS` | `http://localhost:5173` | **requerido** (falla sin env) | Orígenes CORS permitidos (separados por coma) |
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` | Perfil Spring activo |

> El archivo `back_proyecto/.env.example` contiene el mismo set de variables con valores de muestra para dev local sin Docker.

### Mapa: dónde se setea cada variable → dónde se lee

| Variable | Fuentes posibles (en orden de prioridad) | Lectura |
|----------|------------------------------------------|---------|
| `SPRING_PROFILES_ACTIVE` | `docker-compose.override.yml`, `docker-compose.prod.yml`, env shell, raíz `.env` | `application.yml` → `spring.profiles.active` |
| `JWT_SECRET` | `docker-compose.override.yml` (dev), `docker-compose.prod.yml` (prod, requerido), env shell | `application-dev.yml` / `application-prod.yml` → `app.jwt.secret` → `JwtTokenProvider`, `JwtSecretValidator` |
| `STORAGE_PATH` | `docker-compose.override.yml` (`/var/music/audio`), `docker-compose.prod.yml` (`/var/music/audio`), env shell | `application-dev.yml` / `application-prod.yml` → `app.storage.base-path` → `ReactiveFileService`, `StorageService`, `CoverService`, `StorageHealthIndicator` |
| `CORS_ORIGINS` | `docker-compose.override.yml`, `docker-compose.prod.yml` (requerido), env shell | `application-dev.yml` / `application-prod.yml` → `app.cors.allowed-origins` → `SecurityConfig` |
| `DB_NAME` / `DB_USER` / `DB_PASSWORD` | raíz `.env` (default `musicdb` / `postgres` / `postgres`), `docker-compose.yml`, env shell | `application.yml` → `spring.r2dbc.{name,username,password}` |
| `DB_HOST` / `DB_PORT` | `docker-compose.yml` (prod → `postgres:5432`), env shell | `application.yml` → `spring.r2dbc.url` |

### Perfiles

| Perfil | Descripción |
|--------|-------------|
| **dev** | Pool R2DBC chico (2-5 conexiones), log DEBUG, `app.jwt.secret` con default, `app.storage.base-path` en `C:/temp/music-storage`, `app.cors.allowed-origins` con default `http://localhost:5173` |
| **prod** | Pool R2DBC grande (10-50 conexiones, acquisition-timeout 5s), log INFO con rotación (50MB × 30, cap 1GB), timeouts Netty (10s connect, 60s idle), `bootstrap-mode: deferred`, `app.jwt.secret` y `app.cors.allowed-origins` **requeridos** sin default, `app.storage.base-path` con default `/var/music/audio` |

### Configuración de puertos

| Puerto | Servicio |
|--------|----------|
| 8080 | Aplicación principal (API + endpoints públicos) |
| 8081 | Actuator (health, metrics, prometheus) — interno, **no expuesto al host** por `docker-compose.yml` base |

> En `docker-compose.yml` base, el puerto 8081 queda dentro de la red Docker (accesible solo desde otros contenedores). Si necesitás exponerlo al host (por ejemplo, para Prometheus local), agregá el mapping `8081:8081` en `docker-compose.override.yml`.

> El esquema de la base de datos (`src/main/resources/schema.sql`) se inicializa montando el archivo en `/docker-entrypoint-initdb.d/` del contenedor de PostgreSQL, no por Spring. Se ejecuta una sola vez, la primera vez que se crea el volumen de datos.

## Docker

### docker-compose.yml

Los archivos docker-compose están en la raíz del proyecto (`ProyectoDAW/`):

| Archivo | Propósito |
|---------|-----------|
| `docker-compose.yml` | Base: definición de servicios (postgres, backend, frontend) |
| `docker-compose.override.yml` | Dev: se carga automático con `docker compose up` |
| `docker-compose.prod.yml` | Prod: se usa con `-f docker-compose.yml -f docker-compose.prod.yml` |
| `docker-compose.local.yml` | Local: solo PostgreSQL, sin apps dockerizadas |

**docker-compose.yml (base):**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${DB_NAME:-musicdb}
      POSTGRES_USER: ${DB_USER:-postgres}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-postgres}
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./back_proyecto/src/main/resources/schema.sql:/docker-entrypoint-initdb.d/schema.sql

  backend:
    build: ./back_proyecto
    ports:
      - "8080:8080"
      - "8081:8081"
    depends_on:
      postgres:
        condition: service_healthy

  frontend:
    build:
      context: ./front_proyecto
      args:
        VITE_API_URL: /api
    ports:
      - "80:80"
    depends_on:
      backend:
        condition: service_healthy

volumes:
  postgres_data:
```

### Construir y ejecutar

Desde la raíz del proyecto (`ProyectoDAW/`):

```bash
docker compose up --build -d
```

## Ejemplos de Uso

### Registro

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"juan","email":"juan@example.com","password":"secreto123"}'
```

### Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"juan","password":"secreto123"}'

# → {"token":"eyJhbGciOiJIUzI1NiJ9...", "tokenType":"Bearer", "expiresIn":86400000, "user":{...}}
```

### Subir track

```bash
curl -X POST http://localhost:8080/api/tracks \
  -H "Authorization: Bearer <token>" \
  -F "title=Mi Canción" \
  -F "file=@cancion.mp3" \
  -F "duration=240" \
  -F "artistIds=[1,2]" \
  -F "cover=@cover.jpg"
```

### Listar tracks con filtros

```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/tracks?search=rock&page=0&size=10&sortBy=title&sortDirection=asc"
```

### Crear artista

```bash
curl -X POST http://localhost:8080/api/artists \
  -H "Authorization: Bearer <token>" \
  -F "name=Mi Banda" \
  -F "image=@foto.jpg"
```

### Crear álbum

```bash
curl -X POST http://localhost:8080/api/albums \
  -H "Authorization: Bearer <token>" \
  -F "title=Mi Álbum" \
  -F "artistIds=[1]" \
  -F "cover=@cover.jpg" \
  -F "releaseDate=2026-01-15"
```

### Agregar track a álbum

```bash
curl -X POST http://localhost:8080/api/albums/1/tracks \
  -H "Authorization: Bearer <token>" \
  -F "title=Tema 1" \
  -F "file=@tema1.mp3" \
  -F "duration=180" \
  -F "artistIds=[1]" \
  -F "position=1"
```

### Reordenar tracks de un álbum

```bash
curl -X PUT http://localhost:8080/api/albums/1/tracks/reorder \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '[3, 1, 2]'
```

### Descargar álbum como ZIP

```bash
curl -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/albums/1/download \
  -o album.zip
```

### Verificar uso de almacenamiento

```bash
curl -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/auth/storage

# → {"usedBytes":1048576,"limitBytes":536870912,"availableBytes":535822336,"roleName":"STANDARD"}
```

## Desarrollo

```bash
# Compilar
mvn clean compile

# Empaquetar (sin tests)
mvn clean package -DskipTests

# Ejecutar JAR
java -jar target/music-streaming-backend-1.0.0-SNAPSHOT.jar
```
