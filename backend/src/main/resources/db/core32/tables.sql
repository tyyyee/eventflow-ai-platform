CREATE TABLE IF NOT EXISTS cx_user__N__ (
 user_id BIGINT PRIMARY KEY, username VARCHAR(24) NOT NULL, display_name VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS cx_request__N__ (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL,
 activity_id BIGINT NOT NULL, order_id VARCHAR(36) NOT NULL, state VARCHAR(16) NOT NULL,
 reason VARCHAR(40), created_at TIMESTAMP(3) NOT NULL, deadline TIMESTAMP(3) NOT NULL,
 completed_at TIMESTAMP(3), reservation_epoch VARCHAR(36), UNIQUE(user_id,request_key),
 INDEX cx_request_due(state,deadline), INDEX cx_request_user(user_id,created_at,id)
);
CREATE TABLE IF NOT EXISTS cx_order__N__ (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL UNIQUE,
 activity_id BIGINT NOT NULL, price_cents INT NOT NULL, state VARCHAR(24) NOT NULL,
 confirm_until TIMESTAMP(3) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 UNIQUE(user_id,activity_id), INDEX cx_order_user(user_id,created_at,id),
 INDEX cx_order_due(state,confirm_until), CHECK(price_cents>=0)
);
CREATE TABLE IF NOT EXISTS cx_outbox__N__ (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL UNIQUE,
 activity_id BIGINT NOT NULL, created_at TIMESTAMP(3) NOT NULL, sent_at TIMESTAMP(3),
 owner VARCHAR(36), lease_until TIMESTAMP(3), attempts INT NOT NULL DEFAULT 0,
 next_at TIMESTAMP(3) NOT NULL, INDEX cx_outbox_ready(sent_at,next_at,lease_until)
);
CREATE TABLE IF NOT EXISTS cx_action__N__ (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL,
 kind VARCHAR(24) NOT NULL, done_at TIMESTAMP(3), attempts INT NOT NULL DEFAULT 0,
 next_at TIMESTAMP(3) NOT NULL, UNIQUE(user_id,request_id,kind),
 INDEX cx_action_ready(done_at,next_at)
);
CREATE TABLE IF NOT EXISTS cx_payment__N__ (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, request_id VARCHAR(36) NOT NULL UNIQUE,
 amount_cents INT NOT NULL, state VARCHAR(24) NOT NULL, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS cx_receipt__N__ (
 channel_id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, payment_id VARCHAR(36) NOT NULL,
 amount_cents INT NOT NULL, outcome VARCHAR(24) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 INDEX cx_receipt_payment(user_id,payment_id)
);
CREATE TABLE IF NOT EXISTS cx_refund__N__ (
 channel_id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, payment_id VARCHAR(36) NOT NULL,
 amount_cents INT NOT NULL, next_at TIMESTAMP(3) NOT NULL, done_at TIMESTAMP(3),
 attempts INT NOT NULL DEFAULT 0, INDEX cx_refund_ready(done_at,next_at)
);
CREATE TABLE IF NOT EXISTS cx_close__N__ (
 request_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL,
 due_at TIMESTAMP(3) NOT NULL, done_at TIMESTAMP(3), next_enqueue_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX cx_close_due(done_at,due_at), INDEX cx_close_enqueue(done_at,next_enqueue_at)
);
CREATE TABLE IF NOT EXISTS cx_check_log__N__ (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 event VARCHAR(32) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 INDEX cx_check_order(order_id,created_at), UNIQUE(order_id,event)
);
