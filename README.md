# FonoPort - Backend

Backend reactivo para plataforma de streaming de música estilo Spotify. Construido con Spring WebFlux, R2DBC y PostgreSQL, siguiendo arquitectura hexagonal por bounded contexts.

## Tabla de contenidos

- [Stack Tecnológico](#stack-tecnológico)
- [Características](#características)
- [Estructura del Proyecto](#estructura-del-proyecto)
- [Prerrequisitos](#prerrequisitos)
- [Inicio Rápido](#inicio-rápido)
- [API Endpoints (Backend)](#api-endpoints-backend)
- [Modelo de Datos](#modelo-de-datos)
- [Autenticación](#autenticación)
- [Streaming de Audio](#streaming-de-audio)
- [Caché](#caché)
- [Configuración y Despliegue](#configuración-y-despliegue)
- [Docker](#docker)
- [Ejemplos de Uso](#ejemplos-de-uso)
- [Scripts](#scripts)
- [Documentación relacionada](#documentación-relacionada)

---

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

---

## Características

### Streaming y archivos

- **Streaming de audio con Range requests** - Soporte completo para HTTP Range (206 Partial Content) con lectura por chunks (64 KB random, 256 KB secuencial)
- **Carga multipart** - Carga de archivos de audio (hasta 500 MB), portadas de tracks/álbumes e imágenes de artistas
- **Procesamiento de imágenes** - Redimensionamiento automático de portadas con Caffeine cache (TTL 1h)
- **Descarga ZIP** - Álbumes y artistas descargables como archivos ZIP con estructura de carpetas
- **Protección path traversal** - `FilenameSanitizer.safeResolve()` valida que cualquier ruta resuelta quede dentro del directorio base de almacenamiento

### Autenticación y autorización

- **Autenticación JWT** - Tokens HS256 con expiración de 24 horas
- **Rate limiting** - Implementación custom en endpoints de login (10/min) y registro (5/min) por IP
- **Aislamiento de datos** - Todas las queries filtran por `user_id`; `OwnershipValidator` protege recursos

### Almacenamiento y datos

- **Gestión de almacenamiento** - Límites por rol con tracking de uso; ADMIN sin límite, STANDARD con 512 MB
- **Búsqueda sin acentos** - Extensión PostgreSQL `unaccent()` para buscar "cafe" y encontrar "café"
- **Filtrado y ordenamiento avanzado** - Tracks y álbumes soportan búsqueda por texto, filtrado por artista/álbum, y sorting por título, artista, año o duración

### Resiliencia y operaciones

- **Transacciones reactivas** - `R2dbcConfig` provee un `TransactionalOperator` que se aplica en el registro de usuarios y en el borrado en cascada de artistas, garantizando atomicidad en la cadena de eliminaciones
- **Manejo global de excepciones** - `GlobalExceptionHandler` (`@RestControllerAdvice`) mapea `ResourceNotFound` → 404, `ResourceAlreadyExists`/`DataIntegrityViolation` → 409, `StorageLimitExceeded` → 413, validaciones/`MethodArgumentTypeMismatch`/`DateTimeParse`/`NumberFormat`/`MissingServletRequestPart` → 400 con detalle, `SecurityException` (path traversal) → 500
- **Apagado graceful** - Timeout de 30 segundos para cierre ordenado
- **Health monitoring** - `StorageHealthIndicator` verifica espacio en disco + Actuator en puerto 8081 con `show-details: always`
- **Build info en Actuator** - `spring-boot-maven-plugin` genera `build-info` durante el empaquetado, expuesto por `/actuator/info` junto a info de Java

### Contenedor

- **Ejecución no-root en contenedor** - Imagen basada en `appuser:appgroup` (UID/GID 1000); `entrypoint.sh` ajusta permisos de `/var/music-storage` y `/var/log/musicapp` y arranca la JVM vía `su-exec`

---

## Estructura del Proyecto

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
    ├── cache/                               # CacheConfig, CacheProperties, CoverService (Caffeine)
    ├── config/                              # SecurityConfig, RateLimitConfig, RateLimitProperties,
                                             # JwtSecretValidator, StorageHealthIndicator,
                                             # R2dbcConfig, MultipartWebFluxConfig, StorageInitializer
    ├── dto/PageResponse.java                # Respuesta paginada genérica
    ├── exception/                           # ResourceNotFoundException, ResourceAlreadyExistsException,
                                             # UnauthorizedException, StorageLimitExceededException,
                                             # BadRequestException
    ├── handler/GlobalExceptionHandler.java  # @RestControllerAdvice
    ├── security/                            # JwtTokenProvider, JwtAuthenticationFilter, ReactiveUserDetailsServiceImpl
    ├── service/                             # ReactiveFileService, ZipDownloadService
    └── util/                                # FileUtils, FilenameSanitizer, ImageUtils, RangeHeaderParser,
                                             # OwnershipValidator, SortHelper, MetadataParser,
                                             # ResponseHeaderHelper
```

## Prerrequisitos

- Java 21+
- Docker (para PostgreSQL)
- Maven 3.9+

## Inicio Rápido

### Opción A — Backend en IDE + PostgreSQL en Docker (perfil `local`)

```bash
# 1. Arrancar PostgreSQL
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d postgres

# 2. Crear el directorio de almacenamiento local (en perfil local NO lo crea el contenedor)
mkdir music-storage

# 3. Arrancar el backend
mvn spring-boot:run
```

Activa el perfil `local` por defecto. Para `dev`:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

La aplicación queda disponible en `http://localhost:8080`.

### Opción B — Stack dockerizado (`dev` / `prod`)

```bash
# Dev
docker compose -f docker-compose.yml -f docker-compose.dev.yml up

# Prod (requiere JWT_SECRET, CORS_ORIGINS y CADDY_DOMAIN en el entorno)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

En los perfiles dockerizados **no** hace falta crear el directorio de almacenamiento: el `entrypoint.sh` del backend crea `/var/music-storage` y le asigna los permisos correctos al usuario no-root antes de arrancar la JVM.

## API Endpoints (Backend)

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
| POST | `/api/tracks` | Cargar track (multipart) |
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

`management.endpoint.health.show-details: always` expone el detalle de cada componente a cualquier consumidor (no requiere auth para `/health`).

| Método | Path | Descripción | Auth |
|--------|------|-------------|------|
| GET | `/actuator/health` | Health check (incluye disco + R2DBC + componentes) | No |
| GET | `/actuator/health/liveness` | Probe de Liveness (K8s) | No |
| GET | `/actuator/health/readiness` | Probe de Readiness (K8s) | No |
| GET | `/actuator/prometheus` | Métricas Prometheus | Sí |
| GET | `/actuator/info` | Info de la aplicación (incluye `build-info` con versión, grupo, artefacto y timestamp de build) | Sí |
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
- **Registro transaccional**: el alta de un usuario corre dentro de un `TransactionalOperator` (ver `R2dbcConfig`); un `DataIntegrityViolationException` por username/email duplicado se traduce a `ResourceAlreadyExistsException` (409)

**Roles:**

| Rol | Almacenamiento | Descripción |
|-----|----------------|-------------|
| ADMIN | Sin límite (verifica disco) | Administrador del sistema |
| STANDARD | 512 MB | Usuario estándar |

## Streaming de Audio

`GET /api/tracks/{id}/stream` soporta **HTTP Range Requests** (206 Partial Content):

- Sin header `Range`: archivo completo
- Con `Range: bytes=0-1023`: primeros 1024 bytes
- Usa `RandomAccessFile` + `Flux.generate` para lectura por chunks (64 KB random, 256 KB secuencial)
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

## Configuración y Despliegue

Las tablas de esta sección se mantienen sincronizadas con el `README.md` de la raíz del proyecto. Se repiten aquí para que el backend sea consultable de forma autónoma.

### Variables de Entorno

| Variable | Default local/dev | Obligatoria en prod | Descripción |
|---|---|---|---|
| `DB_HOST` | `localhost` | – | Host de PostgreSQL (en Docker se sobrescribe a `postgres`) |
| `DB_PORT` | `5432` | – | Puerto de PostgreSQL |
| `DB_NAME` | `musicdb` | – | Nombre de la base de datos |
| `DB_USER` | `postgres` | – | Usuario PostgreSQL |
| `DB_PASSWORD` | `postgres` | – | Contraseña PostgreSQL |
| `STORAGE_PATH` | `/var/music-storage` (Docker, gestionado por `entrypoint.sh`) / `./music-storage` (IDE, crearlo manualmente) | – | Path de almacenamiento de audios |
| `SPRING_PROFILES_ACTIVE` | `local` | – | `local` \| `dev` \| `prod` |
| `JWT_SECRET` | dev key (mín. 32 caracteres) | sí | Secreto HS256 para firmar JWT |
| `CORS_ORIGINS` | `http://localhost:5173` (local) / `http://localhost` (dev) | sí | Orígenes CORS separados por coma |

### Perfiles Spring

| Perfil | Descripción |
|---|---|
| **local** | Default. Conexión a `${DB_HOST:localhost}:${DB_PORT:5432}`. Pool R2DBC 2–5, `max-idle-time 30s`, `max-life-time 1800s`. Defaults permisivos para JWT y CORS (IDE). Log DEBUG para `com.musicstreaming` y `org.springframework.security`. |
| **dev** | Stack dockerizado, conexión a `postgres:5432` en la red Docker. Mismo pool que local. `CORS_ORIGINS` default `http://localhost` (frontend en puerto 80 dentro de Docker). Log DEBUG. |
| **prod** | Pool R2DBC 10–50, `acquisition-timeout 5s`. `server.netty.connection-timeout 10s`, `idle-timeout 60s`. `JWT_SECRET` y `CORS_ORIGINS` **obligatorios** (la app falla al arrancar si faltan). Log INFO → `/var/log/musicapp/application.log` con rotación 50 MB × 30, cap 1 GB. |

### Puertos Publicados

| Puerto | Servicio | Entorno |
|---|---|---|
| 5432 | PostgreSQL | local, dev, prod |
| 8080 | Backend (API) | dev, prod |
| 8081 | Backend (Actuator) | dev, prod |
| 80 | Frontend (Nginx) | dev, prod |
| 80, 443 | Caddy (HTTPS) | solo prod |

> El esquema de la base de datos (`src/main/resources/schema.sql`) se inicializa montando el archivo en `/docker-entrypoint-initdb.d/` del contenedor de PostgreSQL, no por Spring. Se ejecuta una sola vez, la primera vez que se crea el volumen de datos.

## Docker

| Entorno | Comando |
|---|---|
| `local` | `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d postgres` |
| `dev` | `docker compose -f docker-compose.yml -f docker-compose.dev.yml up` |
| `prod` | `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d` |

Archivos Docker relevantes:

| Archivo | Propósito |
|---|---|
| `docker-compose.yml` | Base: definición común de servicios |
| `docker-compose.local.yml` | Local: solo PostgreSQL, apps en el host con IDE |
| `docker-compose.dev.yml` | Dev: stack completo dockerizado |
| `docker-compose.prod.yml` | Prod: stack completo + Caddy con TLS automático |
| `infra/caddy/Caddyfile` | Reverse proxy con HTTPS automático |
| `back_proyecto/Dockerfile` | Multi-stage: Maven build → `eclipse-temurin:21-jre-alpine` con usuario no-root |
| `back_proyecto/entrypoint.sh` | Prepara `/var/music-storage` y `/var/log/musicapp`; cambia ownership a `appuser:appgroup` y arranca la JVM vía `su-exec` |
| `front_proyecto/Dockerfile` | Multi-stage: Node 22 build → Nginx Alpine |
| `front_proyecto/nginx.conf` | SPA fallback, proxy `/api/` → backend, gzip, caché inmutable, CSP estricta |

### Dockerfile del backend (multi-stage)

- **Stage 1 — `maven:3.9-eclipse-temurin-21`**: descarga dependencias offline, compila con `mvn package -DskipTests -B` y produce `target/app.jar`.
- **Stage 2 — `eclipse-temurin:21-jre-alpine`**:
  - Crea grupo y usuario no-root `appuser:appgroup` (UID/GID 1000)
  - Instala `su-exec` y `wget` desde apk
  - Pre-crea `/var/music-storage` y `/var/log/musicapp` con ownership correcto
  - Copia `app.jar` y `entrypoint.sh` (chmod 755)
  - `EXPOSE 8080 8081`
  - `ENTRYPOINT ["/entrypoint.sh"]`, `CMD ["java", "-jar", "app.jar"]`

### entrypoint.sh

```bash
STORAGE_PATH="${STORAGE_PATH:-/var/music-storage}"
mkdir -p "$STORAGE_PATH" && chown -R appuser:appgroup "$STORAGE_PATH"
mkdir -p /var/log/musicapp && chown -R appuser:appgroup /var/log/musicapp
exec su-exec appuser:appgroup "$@"
```

El contenedor siempre arranca como root para poder crear y asignar permisos a los volúmenes, pero cede el control al usuario `appuser` antes de lanzar la JVM.

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

### Cargar track

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

## Scripts

```bash
# Compilar
mvn clean compile

# Empaquetar (sin tests)
mvn clean package -DskipTests

# Ejecutar JAR
java -jar target/app.jar
```

---

## Documentación relacionada

- Volver al [índice principal](../README.md)
- Ver también: [Frontend README](../front_proyecto/README.md)
