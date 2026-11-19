-- Core sharding is opt-in. Existing trading tables and history replicas are retained.
CREATE TABLE ux_core_control (
 id INT PRIMARY KEY, mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
 changed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CHECK(id=1), CHECK(mode IN ('LEGACY','MIGRATING','CORE','PAUSED'))
);
INSERT INTO ux_core_control(id) VALUES(1);
CREATE TABLE ux_core_reservation_epoch (
 activity_id BIGINT PRIMARY KEY, epoch VARCHAR(36) NOT NULL,
 changed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE ux_core_stock_hold (
 request_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, activity_id BIGINT NOT NULL,
 price_cents INT NOT NULL, state VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, released_at TIMESTAMP(3),
 UNIQUE(activity_id,user_id), INDEX core_hold_activity(activity_id,state),
 CHECK(state IN ('HELD','RELEASED')), CHECK(price_cents>=0)
);
CREATE TABLE ux_core_order_route (
 order_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL,
 request_id VARCHAR(36) NOT NULL UNIQUE, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE ux_core_channel_claim (
 channel_id VARCHAR(64) PRIMARY KEY, payment_id VARCHAR(36) NOT NULL,
 user_id BIGINT NOT NULL, amount_cents INT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 processed_at TIMESTAMP(3), next_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 attempts INT NOT NULL DEFAULT 0, INDEX core_channel_ready(processed_at,next_at)
);
CREATE TABLE ux_core_provider_refund (
 channel_id VARCHAR(64) PRIMARY KEY, amount_cents INT NOT NULL, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE ux_core_id_worker (
 worker_id INT PRIMARY KEY, owner VARCHAR(36) NOT NULL, expires_at TIMESTAMP(3) NOT NULL,
 reserved_until BIGINT NOT NULL DEFAULT 0,
 CHECK(worker_id>=0 AND worker_id<1024)
);
CREATE TABLE ux_core_migration (
 name VARCHAR(40) PRIMARY KEY, cursor_id VARCHAR(64) NOT NULL DEFAULT '',
 done BOOLEAN NOT NULL DEFAULT FALSE, changed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
