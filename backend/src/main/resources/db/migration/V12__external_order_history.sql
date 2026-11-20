ALTER TABLE ux_order_projection ADD COLUMN replica_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ux_order_projection ADD COLUMN replica_next_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE ux_order_projection ADD COLUMN replica_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE ux_order_projection ADD COLUMN replica_error VARCHAR(40);
CREATE TABLE ux_history_control (
 id INT PRIMARY KEY, read_mode VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
 backfill_cursor VARCHAR(36) NOT NULL DEFAULT '', backfill_done BOOLEAN NOT NULL DEFAULT FALSE,
 lease_owner VARCHAR(36), lease_until TIMESTAMP(3),
 verify_cursor VARCHAR(36) NOT NULL DEFAULT '', verify_checked BIGINT NOT NULL DEFAULT 0,
 verify_mismatches BIGINT NOT NULL DEFAULT 0, verified_at TIMESTAMP(3),
 CHECK(id=1), CHECK(read_mode IN ('LOCAL','SHARDED'))
);
INSERT INTO ux_history_control(id) VALUES(1);
