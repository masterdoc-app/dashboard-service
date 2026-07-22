# dashboard-service

Part of Equipment + Technologist (draft-only) epic. See `masterdoc/docs/superpowers/specs/2026-07-22-equipment-technologist-design.md`.

```bash
./gradlew test
./gradlew run
```

## Deploy (VPS)

```bash
cd deploy && docker compose up -d --build --wait
```

Default port via `PORT` (see `Application.kt`). Gateway reaches the service at `http://host.docker.internal:<port>`.
