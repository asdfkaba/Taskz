# Database backups

A daily `pg_dump` of the `spring` database and a gzip-compressed archive of the attachment files,
run by a systemd user timer on the server, next to the `tasks` service. They go to
`~/backups/postgres/spring-<UTC time>.dump` and `~/backups/postgres/attachments-<UTC time>.tar.gz`;
backups older than 14 days are deleted. Old backups are only deleted after a new one worked and
could be read back.

Each archive holds all attachments, not only the new ones. With many large files, 14 days of
archives take up to 14 times their size; lower `KEEP_DAYS` if the disk gets tight.

## Install

On the server, as the user that runs the `tasks` service:

```bash
# 1. Script and units (from a checkout or copied over with scp)
cp deploy/backup/pg-backup.sh ~/tasks/ && chmod 700 ~/tasks/pg-backup.sh
mkdir -p ~/.config/systemd/user
cp deploy/backup/pg-backup.service deploy/backup/pg-backup.timer ~/.config/systemd/user/

# 2. Database user: replace CHANGE_ME with spring.datasource.username
nano ~/.config/systemd/user/pg-backup.service

# 3. Password for pg_dump (host:port:database:user:password), readable only by you
echo 'localhost:5432:spring:DB_USER:DB_PASSWORD' >> ~/.pgpass && chmod 600 ~/.pgpass

# 4. Try it once, then switch the timer on
systemctl --user daemon-reload
systemctl --user start pg-backup.service
journalctl --user -u pg-backup.service -n 20
ls -lh ~/backups/postgres
systemctl --user enable --now pg-backup.timer
systemctl --user list-timers pg-backup.timer
```

User timers only run while the user has a session, unless lingering is on. The `tasks` service
needs it as well, so it is probably on already: `loginctl show-user "$USER" -p Linger`
(otherwise `sudo loginctl enable-linger "$USER"`).

Settings, if needed, as further `Environment=` lines in `pg-backup.service`: `DB_NAME`
(default `spring`), `DB_HOST`, `DB_PORT`, `BACKUP_DIR`, `KEEP_DAYS` (default 14),
`ATTACHMENTS_DIR` (default `~/tasks/attachments`, the app's `attachments.dir`).

Updating from the database-only version: copy `pg-backup.sh` to `~/tasks/` again; the units stay.

Use a `pg_dump` with the same major version as the server (`pg_dump --version`,
`psql -c 'select version()'`). A newer `pg_dump` works, but its dumps may not restore cleanly into
the older server.

## Off the server

The dumps are on the same disk as the database. They help after a bad migration or a mistake, not
when the server or its disk is lost. For that, fetch them regularly to another machine, e.g.:

```bash
rsync -a --ignore-existing USER@SERVER:backups/postgres/ ~/backups/taskz/
```

## Restore

Stop the app, restore the database into an empty one and the attachments of the same time, start
the app again:

```bash
systemctl --user stop tasks
dropdb -h localhost -U DB_USER spring      # or rename it first: ALTER DATABASE spring RENAME TO spring_old
createdb -h localhost -U DB_USER spring
pg_restore -h localhost -U DB_USER -d spring --no-owner ~/backups/postgres/spring-<time>.dump
mv ~/tasks/attachments ~/tasks/attachments-old    # delete it once all is well
tar -xzf ~/backups/postgres/attachments-<time>.tar.gz -C ~/tasks
systemctl --user start tasks
```

To look at a dump without touching the live data, restore it into another database
(`createdb spring_check` and `-d spring_check`).
