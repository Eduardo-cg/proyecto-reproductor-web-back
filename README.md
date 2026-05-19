# Music Streaming Backend

Backend reactivo para plataforma de streaming de música estilo Spotify.

## Stack

- **Java 21** con Virtual Threads
- **Spring Boot 3.4.x** con **WebFlux** (Netty)
- **Spring Data R2DBC** + **PostgreSQL 16**
- **JWT** (HS256) autenticación embebida
- **Caffeine** caché en memoria
- **Resilience4j** circuit breaker
- **Micrometer** + **Prometheus** métricas
- **Docker Compose** para PostgreSQL

## Arquitectura

Hexagonal (puertos & adaptadores):

```
src/main/java/com/musicstreaming/
├── Application.java              # Entry point
├── config/                       # Configuración (cache, seguridad)
├── domain/
│   ├── model/                    # Entidades de dominio
│   ├── repository/               # Puertos de repositorio (R2DBC)
│   └── service/                  # Lógica de negocio
└── adapter/
    ├── controller/               # REST controllers
    ├── dto/                      # Request/Response DTOs
    └── security/                 # JWT, filtros, UserDetails
```

## Prerrequisitos

- Java 21+
- Docker (para PostgreSQL)
- Maven 3.9+ (o usar el bundlado de IntelliJ)

## Quick Start

### 1. Arrancar PostgreSQL

```bash
docker-compose up -d
```

Crea automaticamente la base de datos `musicdb` y ejecuta `schema.sql`.

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

### 4. Endpoints

| Método | Path | Descripción |
|--------|------|-------------|
| POST | `/api/auth/register` | Registrarse |
| POST | `/api/auth/login` | Login (devuelve JWT) |
| GET | `/api/auth/me` | Usuario actual |
| | | |
| POST | `/api/tracks` | Subir track (multipart) |
| GET | `/api/tracks` | Listar tracks |
| GET | `/api/tracks/{id}` | Metadata del track |
| GET | `/api/tracks/{id}/stream` | Streamming con soporte Range |
| DELETE | `/api/tracks/{id}` | Eliminar track |
| | | |
| GET | `/api/playlists` | Listar playlists del usuario |
| POST | `/api/playlists` | Crear playlist |
| GET | `/api/playlists/{id}` | Detalle de playlist con tracks |
| PUT | `/api/playlists/{id}` | Actualizar playlist |
| DELETE | `/api/playlists/{id}` | Eliminar playlist |
| POST | `/api/playlists/{id}/tracks/{trackId}` | Agregar track |
| DELETE | `/api/playlists/{id}/tracks/{trackId}` | Sacar track |
| | | |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Métricas Prometheus |

## Modelo de datos

```sql
users (id, username, email, password, display_name, avatar_url, created_at, updated_at)
tracks (id, title, artist, album, duration, genre, file_path, file_size, mime_type, uploaded_by, created_at, updated_at)
playlists (id, name, description, cover_image, user_id, created_at, updated_at)
playlist_tracks (id, playlist_id, track_id, position, added_at)
playback_history (id, user_id, track_id, played_at)
```

## Streaming de audio

El endpoint `GET /tracks/{id}/stream` soporta **HTTP Range Requests** (206 Partial Content):

- Sin header `Range`: devuelve el archivo completo
- Con `Range: bytes=0-1023`: devuelve solo los primeros 1024 bytes
- Usa `RandomAccessFile` + `Flux.generate` para lectura por chunks sin cargar todo el archivo en memoria
- `subscribeOn(Schedulers.boundedElastic())` para no bloquear el event loop de Netty

```bash
# Primeros 1MB
curl -H "Authorization: Bearer <token>" \
     -H "Range: bytes=0-1048575" \
     http://localhost:8080/api/tracks/1/stream
```

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
  -F "artist=Artista" \
  -F "album=Álbum Uno" \
  -F "genre=Rock" \
  -F "duration=240" \
  -F "file=@cancion.mp3"
```

### Crear playlist y agregar track

```bash
curl -X POST http://localhost:8080/api/playlists \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"name":"Mis Favoritas","description":"Mis canciones favoritas"}'

curl -X POST http://localhost:8080/api/playlists/1/tracks/1 \
  -H "Authorization: Bearer <token>"
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
| `STORAGE_PATH` | /data/audio | Ruta de almacenamiento de archivos |
| `CORS_ORIGINS` | varios orígenes locales | Orígenes CORS permitidos |
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
