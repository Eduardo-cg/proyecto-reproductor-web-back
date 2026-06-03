#!/bin/sh
set -e

STORAGE_PATH="${STORAGE_PATH:-/var/music/audio}"

mkdir -p "$STORAGE_PATH"
chown -R appuser:appgroup "$STORAGE_PATH"

mkdir -p /var/log/musicapp
chown -R appuser:appgroup /var/log/musicapp

exec su-exec appuser:appgroup "$@"
