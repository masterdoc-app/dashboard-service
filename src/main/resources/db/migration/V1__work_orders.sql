CREATE TABLE work_orders (
    id TEXT PRIMARY KEY,
    org_id TEXT NOT NULL,
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    title TEXT NOT NULL,
    asset_id TEXT NOT NULL,
    site_id TEXT NOT NULL,
    due_at TEXT NOT NULL,
    duration_hours INTEGER NOT NULL,
    assignee_id TEXT,
    maintenance_map_id TEXT,
    maintenance_map_item_id TEXT,
    created_by TEXT,
    description TEXT,
    source TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    started_at TEXT,
    closed_at TEXT
);
CREATE INDEX work_orders_org_id_idx ON work_orders (org_id);
CREATE INDEX work_orders_org_assignee_idx ON work_orders (org_id, assignee_id);
CREATE INDEX work_orders_org_created_by_idx ON work_orders (org_id, created_by);
CREATE INDEX work_orders_org_status_idx ON work_orders (org_id, status);
