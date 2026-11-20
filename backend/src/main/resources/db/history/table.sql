CREATE TABLE IF NOT EXISTS __TABLE__ (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
