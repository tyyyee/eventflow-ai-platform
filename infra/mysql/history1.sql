-- Fixed history topology v1. Initialize only a fresh query schema.
CREATE TABLE IF NOT EXISTS ux_order_read_16 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_17 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_18 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_19 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_20 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_21 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_22 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_23 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_24 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_25 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_26 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_27 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_28 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_29 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_30 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_31 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
