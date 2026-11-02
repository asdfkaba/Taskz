#!/usr/bin/env bash
# Daily backup of the app, run by pg-backup.timer (see README.md in this folder).
#
# Writes <BACKUP_DIR>/spring-<UTC time>.dump in pg_dump's custom format (compressed,
# restore with pg_restore) and <BACKUP_DIR>/attachments-<UTC time>.tar.gz with the attachment
# files, and deletes backups older than KEEP_DAYS.
# The password comes from ~/.pgpass, so it is neither in this file nor in the process list.
set -euo pipefail

DB_NAME="${DB_NAME:-spring}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:?set DB_USER to the database user from spring.datasource.username}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/postgres}"
KEEP_DAYS="${KEEP_DAYS:-14}"
# The app's attachments.dir.
ATTACHMENTS_DIR="${ATTACHMENTS_DIR:-$HOME/tasks/attachments}"

umask 077 # dumps contain all user data
mkdir -p "$BACKUP_DIR"

stamp="$(date -u +%Y-%m-%dT%H%M%SZ)"
target="$BACKUP_DIR/$DB_NAME-$stamp.dump"
partial="$target.partial"
archive="$BACKUP_DIR/attachments-$stamp.tar.gz"
archive_partial="$archive.partial"
trap 'rm -f "$partial" "$archive_partial"' EXIT

# --no-password: fail instead of waiting for input when ~/.pgpass is missing or wrong.
pg_dump --host="$DB_HOST" --port="$DB_PORT" --username="$DB_USER" --no-password \
  --format=custom --file="$partial" "$DB_NAME"

# A dump that pg_restore can't read is no backup; keep the old ones in that case.
pg_restore --list "$partial" > /dev/null

mv "$partial" "$target"
echo "Backup written: $target ($(du -h "$target" | cut -f1))"

# Files after the database, so attachments uploaded meanwhile can't be in the dump without their
# file (the app stores the file before the row). Attachments deleted between dump and archive are
# the exception: their row is in the dump, their file is gone (download answers 404 after a
# restore). Uploads still in progress (.upload-*) are left out.
if [ -d "$ATTACHMENTS_DIR" ]; then
  # Exit status 1 means a file was deleted or changed while reading it (the app runs on); the
  # archive is fine apart from that file. 2 and up are real errors.
  tar_status=0
  tar --create --gzip --file="$archive_partial" --exclude='.upload-*' \
    --directory="$(dirname "$ATTACHMENTS_DIR")" "$(basename "$ATTACHMENTS_DIR")" || tar_status=$?
  if [ "$tar_status" -gt 1 ]; then
    exit "$tar_status"
  fi
  tar --list --gzip --file="$archive_partial" > /dev/null
  mv "$archive_partial" "$archive"
  echo "Backup written: $archive ($(du -h "$archive" | cut -f1))"
else
  echo "No attachments yet ($ATTACHMENTS_DIR is missing), nothing to archive"
fi

# Only after a good backup, so a failing one never deletes the last working ones.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name "$DB_NAME-*.dump" -o -name "attachments-*.tar.gz" \) \
  -mtime +"$KEEP_DAYS" -print -delete
