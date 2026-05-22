# Music Streaming Backend

Backend reactivo para plataforma de streaming de música estilo Spotify. Construido con Spring WebFlux, R2DBC y PostgreSQL, siguiendo arquitectura hexagonal.

## Stack

- **Java 21** con Virtual Threads
- **Spring Boot 3.4.x** con **WebFlux** (Netty)
- **Spring Data R2DBC** + **PostgreSQL 16**
- **Spring Security WebFlux** + **JWT** (HS256) autenticación embebida
- **Caffeine** caché en memoria (cover images)
- **Resilience4j** circuit breaker
- **Micrometer** + **Prometheus** métricas
- **Docker Compose** para PostgreSQL
- **Lombok**, **Jakarta Validation**, **Jackson**

## Arquitectura

Hexagonal (puertos & adaptadores):

```
src/main/java/com/musicstreaming/
├── Application.java                     # Entry point
├── config/                              # Config (seguridad, caché)
├── domain/
│   ├── model/                           # Entidades: User, Track, Album, Artist
│   ├── repository/                      # Repositorios R2DBC
│   └── service/                         # Lógica de negocio (Auth, Audio, Album, Artist)
└── adapter/
    ├── controller/                      # REST controllers + GlobalExceptionHandler
    ├── dto/                             # Request/Response DTOs
    └── security/                        # JWT, filtros, UserDetails
```

## Prerrequisitos

- Java 21+
- Docker (para PostgreSQL)
- Maven 3.9+

## Quick Start

### 1. Arrancar PostgreSQL

```bash
docker-compose up -d
```

Crea automáticamente la base de datos `musicdb` y ejecuta `schema.sql`.

### 2. Configurar almacenamiento

Por defecto los audios se guardan en `C:/temp/music-storage`. Creá la carpeta:

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

## Endpoints

### Auth

| Método | Path | Descripción |
|--------|------|-------------|
| POST | `/api/auth/register` | Registrarse |
| POST | `/api/auth/login` | Login (devuelve JWT) |
| GET | `/api/auth/me` | Usuario actual |

### Tracks

| Método | Path | Descripción |
|--------|------|-------------|
| POST | `/api/tracks` | Subir track (multipart) |
| GET | `/api/tracks` | Listar tracks (paginado) |
| GET | `/api/tracks/{id}` | Metadata del track |
| GET | `/api/tracks/{id}/stream` | Streaming con soporte Range (206) |
| DELETE | `/api/tracks/{id}` | Eliminar track |
| GET | `/api/tracks/count` | Cantidad total de tracks |

### Albums

| Método | Path | Descripción |
|--------|------|-------------|
| POST | `/api/albums` | Crear álbum (multipart) |
| GET | `/api/albums` | Listar álbumes (paginado) |
| GET | `/api/albums/{id}` | Detalle del álbum con sus tracks |
| DELETE | `/api/albums/{id}` | Eliminar álbum (cascada) |
| POST | `/api/albums/{id}/tracks` | Agregar track al álbum (multipart) |

### Artists

| Método | Path | Descripción |
|--------|------|-------------|
| GET | `/api/artists` | Listar artistas (búsqueda con `?search=`) |
| GET | `/api/artists/{id}` | Detalle del artista |
| POST | `/api/artists` | Crear artista (multipart) |
| PUT | `/api/artists/{id}` | Actualizar artista (multipart) |
| DELETE | `/api/artists/{id}` | Eliminar artista |

### Actuator

| Método | Path | Descripción |
|--------|------|-------------|
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Métricas Prometheus |

## Modelo de datos

```sql
users           (id, username, email, password_hash, created_at, updated_at)
tracks          (id, title, duration, file_path, file_size, mime_type, cover_path, user_id, created_at, updated_at)
albums          (id, title, release_date, cover_path, user_id, created_at, updated_at)
artists         (id, name, image_path, user_id, created_at, updated_at)
album_tracks    (id, album_id, track_id, position)
track_artists   (id, track_id, artist_id, position)
album_artists   (id, album_id, artist_id, position)
playback_history (id, user_id, track_id, played_at, progress)
```

## Streaming de audio

`GET /api/tracks/{id}/stream` soporta **HTTP Range Requests** (206 Partial Content):

- Sin header `Range`: archivo completo
- Con `Range: bytes=0-1023`: primeros 1024 bytes
- Usa `RandomAccessFile` + `Flux.generate` para lectura por chunks (64KB)
- `subscribeOn(Schedulers.boundedElastic())` para no bloquear el event loop de Netty

```bash
curl -H "Authorization: Bearer <token>" \
     -H "Range: bytes=0-1048575" \
     http://localhost:8080/api/tracks/1/stream
```

## Caché

Cobertura de imágenes vía **Caffeine** con TTL de 1 hora:

- `AudioService`: caché de bytes de cover de tracks
- `AlbumService`: caché de bytes de cover de álbumes
- `ArtistService`: caché de bytes de imagen de artista

## Ejemplos de uso

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

# → {"token":"eyJhbGciOiJIUzI1NiJ9...", "expiresIn":86400000}
```

### Subir track

```bash
curl -X POST http://localhost:8080/api/tracks \
  -H "Authorization: Bearer <token>" \
  -F "title=Mi Canción" \
  -F "file=@cancion.mp3"
```

### Crear artista

```bash
curl -X POST http://localhost:8080/api/artists \
  -H "Authorization: Bearer <token>" \
  -F "name=Mi Banda" \
  -F "image=@foto.jpg"
```

### Crear álbum y agregar track

```bash
# Crear álbum
curl -X POST http://localhost:8080/api/albums \
  -H "Authorization: Bearer <token>" \
  -F "title=Mi Álbum" \
  -F "artistIds=1" \
  -F "cover=@cover.jpg"

# Agregar track al álbum
curl -X POST http://localhost:8080/api/albums/1/tracks \
  -H "Authorization: Bearer <token>" \
  -F "title=Tema 1" \
  -F "artistIds=1" \
  -F "file=@tema1.mp3"
```

## Configuración

| Variable | Default | Descripción |
|----------|---------|-------------|
| `DB_HOST` | localhost | Host PostgreSQL |
| `DB_PORT` | 5432 | Puerto PostgreSQL |
| `DB_NAME` | musicdb | Nombre de base de datos |
| `DB_USER` | postgres | Usuario PostgreSQL |
| `DB_PASSWORD` | postgres | Contraseña PostgreSQL |
| `JWT_SECRET` | *default dev* | Secreto para firmar JWT (min 32 chars) |
| `JWT_EXPIRATION` | 86400000 | Expiración del token en ms |
| `STORAGE_PATH` | /data/audio | Ruta de almacenamiento de archivos |
| `CORS_ORIGINS` | http://localhost:5173 | Orígenes CORS permitidos |
| `SPRING_PROFILES_ACTIVE` | dev | Perfil activo |

Perfiles:
- **dev**: PostgreSQL local, log DEBUG, storage en `C:/temp/music-storage`
- **prod**: Espera variables de entorno, log INFO, validación de secretos

## Desarrollo

```bash
# Tests
mvn test

# Compilar
mvn clean compile

# Empaquetar
mvn clean package -DskipTests

# Ejecutar JAR
java -jar target/music-streaming-backend-1.0.0-SNAPSHOT.jar
```
