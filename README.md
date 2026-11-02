# Taskz

Kanban boards for tasks, with Spring Boot, Vue.js and third-party OAuth login.

[demo.webm](https://github.com/user-attachments/assets/a11f1749-3f1b-47c8-a49f-b1b8cc9ba80e)

## Tests

Backend (includes migration tests against PostgreSQL 15 in Docker; they are skipped without Docker):

```bash
./mvnw test
```

Browser tests (Playwright against the built frontend and a mock API in `task-frontend/e2e/mock-server.mjs`):

```bash
cd task-frontend
npx playwright install chromium   # once; or set CHROMIUM_PATH to a local Chromium
npm run test:e2e
```

Both run in GitHub Actions on pull requests and before every deploy (`.github/workflows/tests.yml`).

## Database changes

The schema is managed by Flyway (`src/main/resources/db/migration`). Hibernate only validates it,
so a new or changed entity field needs a new migration `V<n>__<description>.sql`.

## Attachments

Files attached to tasks are stored on disk, in `~/tasks/attachments` of the user running the app.
Only PNG, JPEG, GIF, WebP, PDF and UTF-8 text (txt, md, csv) are accepted, recognized by content,
up to 10 MB per file and 500 MB per user. All of this can be changed with `attachments.*`
properties (see `application.properties.example`).

nginx accepts only 1 MB request bodies by default. Allow a bit more than the file limit in the
server block, e.g. `client_max_body_size 11m;`, and reload nginx.

## Backups

Daily `pg_dump` of the database and an archive of the attachments with a systemd timer: see
[deploy/backup](deploy/backup/README.md).
