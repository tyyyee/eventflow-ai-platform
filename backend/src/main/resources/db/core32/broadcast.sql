-- Display/configuration snapshots only. Never broadcast mutable inventory.
CREATE TABLE IF NOT EXISTS cx_event (
 activity_id BIGINT PRIMARY KEY, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(120), image_path VARCHAR(500), price_cents INT NOT NULL,
 starts_at TIMESTAMP(3) NOT NULL, ends_at TIMESTAMP(3) NOT NULL
);
