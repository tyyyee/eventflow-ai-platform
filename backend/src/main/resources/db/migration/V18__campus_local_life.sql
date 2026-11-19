-- Additive local-life domain. Existing event/transaction identifiers are unchanged.
CREATE TABLE ux_local_profile (
 user_id BIGINT PRIMARY KEY, bio VARCHAR(200) NOT NULL DEFAULT '', avatar_id VARCHAR(36),
 role VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER', updated_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES ux_account(id), CHECK(role IN ('CUSTOMER','OPERATOR'))
);
CREATE TABLE ux_account_recovery (
 user_id BIGINT PRIMARY KEY, code_hash CHAR(64) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES ux_account(id)
);
CREATE TABLE ux_local_shop (
 shop_id BIGINT PRIMARY KEY, category VARCHAR(24) NOT NULL, campus VARCHAR(24) NOT NULL,
 area VARCHAR(60) NOT NULL, address VARCHAR(200) NOT NULL, latitude DECIMAL(10,7), longitude DECIMAL(10,7),
 avg_price_cents INT, hours_json TEXT NOT NULL, closed_dates_json TEXT NOT NULL,
 tags_json TEXT NOT NULL, photos_json TEXT NOT NULL, cover_path VARCHAR(250) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', priority INT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 1, seed_key VARCHAR(80) UNIQUE, updated_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(shop_id) REFERENCES ux_shop(id), CHECK(avg_price_cents IS NULL OR avg_price_cents >= 0),
 CHECK(status IN ('DRAFT','PUBLISHED','CLOSED'))
);
CREATE INDEX ux_local_shop_filter ON ux_local_shop(status,category,campus,shop_id);
CREATE TABLE ux_media (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, purpose VARCHAR(16) NOT NULL,
 target_id VARCHAR(36), content_type VARCHAR(40) NOT NULL, storage_key VARCHAR(100) NOT NULL,
 size_bytes BIGINT NOT NULL, width INT NOT NULL, height INT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, deleted_at TIMESTAMP(3), FOREIGN KEY(user_id) REFERENCES ux_account(id)
);
CREATE INDEX ux_media_owner ON ux_media(user_id,created_at);
CREATE TABLE ux_favorite (
 user_id BIGINT NOT NULL, target_type VARCHAR(16) NOT NULL, target_id BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, PRIMARY KEY(user_id,target_type,target_id),
 FOREIGN KEY(user_id) REFERENCES ux_account(id), CHECK(target_type IN ('SHOP','EVENT'))
);
CREATE TABLE ux_review (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL,
 rating INT NOT NULL, body VARCHAR(1000) NOT NULL, media_json TEXT NOT NULL,
 state VARCHAR(16) NOT NULL, active_slot INT, version BIGINT NOT NULL DEFAULT 1,
 moderation_reason VARCHAR(300) NOT NULL DEFAULT '', is_seed BOOLEAN NOT NULL DEFAULT FALSE,
 seed_key VARCHAR(80) UNIQUE, created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES ux_account(id), FOREIGN KEY(shop_id) REFERENCES ux_local_shop(shop_id),
 UNIQUE(user_id,shop_id,active_slot), CHECK(rating BETWEEN 1 AND 5),
 CHECK(state IN ('PUBLISHED','HIDDEN','DELETED'))
);
CREATE INDEX ux_review_shop ON ux_review(shop_id,state,created_at,id);
CREATE TABLE ux_review_like (
 user_id BIGINT NOT NULL, review_id VARCHAR(36) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(user_id,review_id), FOREIGN KEY(user_id) REFERENCES ux_account(id), FOREIGN KEY(review_id) REFERENCES ux_review(id)
);
CREATE TABLE ux_sign_record (
 user_id BIGINT NOT NULL, sign_date DATE NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(user_id,sign_date), FOREIGN KEY(user_id) REFERENCES ux_account(id)
);
CREATE TABLE ux_shop_checkin (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL,
 checkin_date DATE NOT NULL, note VARCHAR(200) NOT NULL, media_json TEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, UNIQUE(user_id,shop_id,checkin_date),
 FOREIGN KEY(user_id) REFERENCES ux_account(id), FOREIGN KEY(shop_id) REFERENCES ux_local_shop(shop_id)
);
CREATE TABLE ux_coupon_template (
 id VARCHAR(36) PRIMARY KEY, shop_id BIGINT NOT NULL, title VARCHAR(100) NOT NULL,
 minimum_cents INT NOT NULL, discount_cents INT NOT NULL, capacity INT NOT NULL, remaining INT NOT NULL,
 claim_from TIMESTAMP(3) NOT NULL, claim_until TIMESTAMP(3) NOT NULL,
 valid_from TIMESTAMP(3) NOT NULL, valid_until TIMESTAMP(3) NOT NULL, valid_days INT NOT NULL DEFAULT 7,
 terms VARCHAR(1000) NOT NULL, excluded_dates_json TEXT NOT NULL, state VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, seed_key VARCHAR(80) UNIQUE, created_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(shop_id) REFERENCES ux_local_shop(shop_id), CHECK(remaining>=0 AND remaining<=capacity),
 CHECK(discount_cents>0 AND minimum_cents>=discount_cents), CHECK(state IN ('DRAFT','PUBLISHED','PAUSED','ENDED'))
);
CREATE INDEX ux_coupon_claim ON ux_coupon_template(shop_id,state,claim_until);
CREATE TABLE ux_user_coupon (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, template_id VARCHAR(36) NOT NULL, shop_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL, minimum_cents INT NOT NULL, discount_cents INT NOT NULL,
 valid_from TIMESTAMP(3) NOT NULL, valid_until TIMESTAMP(3) NOT NULL, terms VARCHAR(1000) NOT NULL,
 excluded_dates_json TEXT NOT NULL, redeem_code VARCHAR(64) NOT NULL UNIQUE,
 state VARCHAR(16) NOT NULL, void_reason VARCHAR(300), updated_at TIMESTAMP(3) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 UNIQUE(user_id,template_id), FOREIGN KEY(user_id) REFERENCES ux_account(id),
 FOREIGN KEY(template_id) REFERENCES ux_coupon_template(id), FOREIGN KEY(shop_id) REFERENCES ux_local_shop(shop_id),
 CHECK(state IN ('AVAILABLE','USED','EXPIRED','VOID'))
);
CREATE INDEX ux_user_coupon_owner ON ux_user_coupon(user_id,created_at,id);
CREATE TABLE ux_coupon_redemption (
 id VARCHAR(36) PRIMARY KEY, coupon_id VARCHAR(36) NOT NULL UNIQUE, operator_id BIGINT NOT NULL,
 amount_cents INT NOT NULL, discount_cents INT NOT NULL, paid_cents INT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(coupon_id) REFERENCES ux_user_coupon(id)
);
CREATE TABLE ux_local_operation (
 operator_id BIGINT NOT NULL, operation_type VARCHAR(32) NOT NULL, request_key VARCHAR(64) NOT NULL,
 payload_hash CHAR(64) NOT NULL, result_json TEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(operator_id,operation_type,request_key)
);
CREATE TABLE ux_policy (
 id VARCHAR(36) PRIMARY KEY, title VARCHAR(120) NOT NULL, category VARCHAR(30) NOT NULL,
 keywords VARCHAR(250) NOT NULL, body VARCHAR(2000) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 state VARCHAR(16) NOT NULL, effective_at TIMESTAMP(3) NOT NULL, seed_key VARCHAR(80) UNIQUE,
 created_at TIMESTAMP(3) NOT NULL, CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED'))
);
CREATE TABLE ux_content_report (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, review_id VARCHAR(36) NOT NULL,
 reason VARCHAR(300) NOT NULL, state VARCHAR(16) NOT NULL, resolution VARCHAR(500), operator_id BIGINT,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL, UNIQUE(user_id,review_id),
 FOREIGN KEY(user_id) REFERENCES ux_account(id), FOREIGN KEY(review_id) REFERENCES ux_review(id)
);
CREATE TABLE ux_feedback (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL,
 title VARCHAR(120) NOT NULL, body VARCHAR(1500) NOT NULL, state VARCHAR(16) NOT NULL,
 resolution VARCHAR(1000), operator_id BIGINT, created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 UNIQUE(user_id,request_key), FOREIGN KEY(user_id) REFERENCES ux_account(id)
);
CREATE TABLE ux_local_audit (
 id VARCHAR(36) PRIMARY KEY, operator_id BIGINT NOT NULL, action VARCHAR(50) NOT NULL,
 target_id VARCHAR(50) NOT NULL, summary VARCHAR(1000) NOT NULL, created_at TIMESTAMP(3) NOT NULL
);
