CREATE UNIQUE INDEX work_orders_ppr_idempotency_idx
    ON work_orders (org_id, maintenance_map_item_id, due_at)
    WHERE type = 'ppr' AND maintenance_map_item_id IS NOT NULL;
