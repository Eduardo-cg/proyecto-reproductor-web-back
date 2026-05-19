-- Schema for Music Streaming Platform
-- PostgreSQL with R2DBC

-- USERS
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);

-- TRACKS
CREATE TABLE IF NOT EXISTS tracks (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    artist VARCHAR(255) NOT NULL,
    album VARCHAR(255),
    duration INTEGER NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    mime_type VARCHAR(50) DEFAULT 'audio/mpeg',
    file_size BIGINT NOT NULL,
    cover_art_path VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_tracks_artist ON tracks(artist);
CREATE INDEX idx_tracks_album ON tracks(album);

-- PLAYBACK_HISTORY (Optional for future)
CREATE TABLE IF NOT EXISTS playback_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    track_id BIGINT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    played_at TIMESTAMP DEFAULT NOW(),
    progress_seconds INTEGER DEFAULT 0
);

CREATE INDEX idx_playback_history_user_id ON playback_history(user_id);
CREATE INDEX idx_playback_history_played_at ON playback_history(played_at);