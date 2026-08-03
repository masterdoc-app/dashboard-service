#!/usr/bin/env bash
# Backfill work_orders.created_by across all orgs on the API VPS.
# Env: DRY_RUN=true|false, FALLBACK_CREATOR_ID=optional
set -euo pipefail

DRY_RUN="${DRY_RUN:-false}"
FALLBACK_CREATOR_ID="${FALLBACK_CREATOR_ID:-}"

DASH_COMPOSE=/opt/dashboard-service/deploy
CAT_COMPOSE=/opt/catalog-service/deploy

dash_psql() {
  (cd "$DASH_COMPOSE" && docker compose exec -T dashboard-postgres \
    psql -U dashboard -d dashboard -v ON_ERROR_STOP=1 "$@")
}
cat_psql() {
  (cd "$CAT_COMPOSE" && docker compose exec -T catalog-postgres \
    psql -U catalog -d catalog -v ON_ERROR_STOP=1 "$@")
}

echo "=== before: missing creators by org ==="
dash_psql -c "
SELECT org_id,
       COUNT(*) AS total,
       COUNT(*) FILTER (WHERE created_by IS NULL OR btrim(created_by) = '') AS missing
FROM work_orders
GROUP BY org_id
ORDER BY missing DESC, org_id;
"

dash_psql -Atc "
SELECT id || E'\t' || org_id || E'\t' || COALESCE(assignee_id, '') || E'\t' || COALESCE(created_by, '')
FROM work_orders
ORDER BY org_id, id;
" >/tmp/work_orders.tsv

cat_psql -Atc "
SELECT org_id || E'\t' || user_id FROM user_scopes ORDER BY org_id, user_id;
" >/tmp/user_scopes.tsv

echo "work_orders_rows=$(wc -l </tmp/work_orders.tsv)"
echo "user_scopes_rows=$(wc -l </tmp/user_scopes.tsv)"
echo "=== user_scopes sample ==="
head -20 /tmp/user_scopes.tsv || true

export DRY_RUN FALLBACK_CREATOR_ID
python3 <<'PY'
import os
from collections import Counter, defaultdict
from datetime import datetime, timezone

dry = os.environ.get("DRY_RUN", "false").lower() in ("1", "true", "yes")
fallback = (os.environ.get("FALLBACK_CREATOR_ID") or "").strip()

known = {
    "382715225649971203": "382715229189963779",
    "383177088934346755": "383177205334671363",
}

scopes = defaultdict(list)
with open("/tmp/user_scopes.tsv") as f:
    for line in f:
        line = line.rstrip("\n")
        if not line:
            continue
        org, uid = line.split("\t", 1)
        if org and uid and uid not in scopes[org]:
            scopes[org].append(uid)

peer_counts = defaultdict(Counter)
rows = []
with open("/tmp/work_orders.tsv") as f:
    for line in f:
        line = line.rstrip("\n")
        if not line:
            continue
        wid, org, assignee, created_by = line.split("\t", 3)
        row = {"id": wid, "org_id": org, "assignee_id": assignee, "created_by": created_by}
        rows.append(row)
        cb = created_by.strip()
        if cb:
            peer_counts[org][cb] += 1

peer = {org: counts.most_common(1)[0][0] for org, counts in peer_counts.items() if counts}

updates = []
unresolved = []
for row in rows:
    if row["created_by"].strip():
        continue
    org = row["org_id"]
    assignee = row["assignee_id"].strip()
    if assignee:
        updates.append((row["id"], assignee, "assignee"))
    elif peer.get(org):
        updates.append((row["id"], peer[org], "peer_created_by"))
    elif scopes.get(org):
        updates.append((row["id"], scopes[org][0], "user_scope"))
    elif known.get(org):
        updates.append((row["id"], known[org], "known_org_user"))
    elif fallback:
        updates.append((row["id"], fallback, "fallback"))
    else:
        unresolved.append(row)

by_reason = Counter(reason for _, _, reason in updates)
by_org = Counter()
id_to_org = {r["id"]: r["org_id"] for r in rows}
for wid, _, _ in updates:
    by_org[id_to_org[wid]] += 1

print(f"planned_updates={len(updates)} unresolved={len(unresolved)} dry_run={dry}")
print("by_reason", dict(by_reason))
print("by_org", dict(by_org))
if unresolved:
    print("UNRESOLVED (first 20):")
    for r in unresolved[:20]:
        print(f"  org={r['org_id']} id={r['id']}")

if dry:
    raise SystemExit(0 if not unresolved else 1)

if not updates:
    raise SystemExit(0 if not unresolved else 1)

now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
sql_lines = ["BEGIN;"]
for wid, creator, _ in updates:
    esc_id = wid.replace("'", "''")
    esc_c = creator.replace("'", "''")
    sql_lines.append(
        f"UPDATE work_orders SET created_by = '{esc_c}', updated_at = '{now}' "
        f"WHERE id = '{esc_id}' AND (created_by IS NULL OR btrim(created_by) = '');"
    )
sql_lines.append("COMMIT;")
open("/tmp/backfill_creators.sql", "w").write("\n".join(sql_lines) + "\n")
print(f"wrote {len(updates)} UPDATE statements")
PY

if [[ "$DRY_RUN" == "true" ]]; then
  echo "dry_run complete"
  exit 0
fi

if [[ -f /tmp/backfill_creators.sql ]]; then
  echo "=== applying updates ==="
  # File lives on the host; feed it to psql inside the container via stdin.
  (cd "$DASH_COMPOSE" && docker compose exec -T dashboard-postgres \
    psql -U dashboard -d dashboard -v ON_ERROR_STOP=1) </tmp/backfill_creators.sql
else
  echo "no SQL file (nothing to update)"
fi

echo "=== after: missing creators by org ==="
dash_psql -c "
SELECT org_id,
       COUNT(*) AS total,
       COUNT(*) FILTER (WHERE created_by IS NULL OR btrim(created_by) = '') AS missing
FROM work_orders
GROUP BY org_id
ORDER BY missing DESC, org_id;
"

LEFT="$(dash_psql -tAc "SELECT COUNT(*) FROM work_orders WHERE created_by IS NULL OR btrim(created_by) = '';")"
LEFT="$(echo -n "$LEFT" | tr -d '[:space:]')"
echo "remaining_missing=$LEFT"
[[ "$LEFT" == "0" ]] || { echo "FAIL: still missing creators"; exit 1; }
