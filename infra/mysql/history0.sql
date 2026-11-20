-- Fixed history topology v1. Initialize only a fresh query schema.
CREATE TABLE IF NOT EXISTS ux_order_read_00 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_01 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_02 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_03 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_04 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_05 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_06 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_07 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_08 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_09 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_10 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_11 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_12 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_13 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_14 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS ux_order_read_15 (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, confirm_until TIMESTAMP(3) NOT NULL,
 revision BIGINT NOT NULL, shop_id BIGINT, shop_name VARCHAR(200),
 offer_title VARCHAR(200), image_path VARCHAR(500),
 INDEX history_user(user_id,created_at,id)
);
