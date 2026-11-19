-- Source-side change intent is committed with the original business write.
CREATE TABLE ux_core_copy_user (
 user_id BIGINT PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 1,
 applied_revision BIGINT NOT NULL DEFAULT 0,
 changed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 owner VARCHAR(36), lease_until TIMESTAMP(3), next_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 last_error VARCHAR(64), INDEX core_copy_ready(applied_revision,next_at)
);
CREATE TRIGGER core_account_insert AFTER INSERT ON ux_account FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_account_update AFTER UPDATE ON ux_account FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_request_insert AFTER INSERT ON ux_request FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.user_id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_request_update AFTER UPDATE ON ux_request FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.user_id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_order_insert AFTER INSERT ON ux_order FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.user_id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_order_update AFTER UPDATE ON ux_order FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.user_id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_outbox_insert AFTER INSERT ON ux_outbox FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) SELECT user_id FROM ux_request WHERE id=NEW.request_id ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_outbox_update AFTER UPDATE ON ux_outbox FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) SELECT user_id FROM ux_request WHERE id=NEW.request_id ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_payment_insert AFTER INSERT ON ux_payment FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.user_id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_payment_update AFTER UPDATE ON ux_payment FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.user_id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_receipt_insert AFTER INSERT ON ux_payment_receipt FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) SELECT user_id FROM ux_payment WHERE id=NEW.payment_id ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_receipt_update AFTER UPDATE ON ux_payment_receipt FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) SELECT user_id FROM ux_payment WHERE id=NEW.payment_id ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_refund_insert AFTER INSERT ON ux_refund FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) SELECT user_id FROM ux_payment WHERE id=NEW.payment_id ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_refund_update AFTER UPDATE ON ux_refund FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) SELECT user_id FROM ux_payment WHERE id=NEW.payment_id ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_close_insert AFTER INSERT ON ux_close_task FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.user_id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
CREATE TRIGGER core_close_update AFTER UPDATE ON ux_close_task FOR EACH ROW
 INSERT INTO ux_core_copy_user(user_id) VALUES(NEW.user_id) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3);
