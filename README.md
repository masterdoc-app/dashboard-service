# dashboard-service

Owns **maintenance maps** (ППР), **work orders**, weekly **board**, and PPR→WO scheduler.

Contracts:

- Equipment / Technologist: `masterdoc/docs/superpowers/specs/2026-07-22-equipment-technologist-design.md`
- Work orders / board: `masterdoc/docs/superpowers/specs/2026-07-25-work-orders-board-design.md`

```bash
./gradlew test
./gradlew run
```

Public via gateway (feature `board`): `POST/GET/PATCH /work-orders`, `GET /work-orders/board`.  
Internal only: `POST /internal/scheduler/tick` (not proxied).

Env: `PORT` (default 8092), `CATALOG_BASE_URL`, `BOARD_HORIZON_WEEKS` (default 4).

## Deploy (VPS)

Push to `main` / `master` → GitHub Actions **test + Compose deploy** to API VPS (`/opt/dashboard-service`).

Manual (emergency only):

```bash
cd deploy && docker compose up -d --build --wait
```

Secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_PRIVATE_KEY`.
