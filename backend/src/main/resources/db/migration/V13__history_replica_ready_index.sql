ALTER TABLE ux_order_projection
 ADD COLUMN replica_pending TINYINT GENERATED ALWAYS AS (CASE WHEN replica_revision < revision THEN 1 ELSE 0 END) STORED;
CREATE INDEX ux_projection_replica_ready ON ux_order_projection(replica_pending,replica_next_at,changed_at,order_id);
