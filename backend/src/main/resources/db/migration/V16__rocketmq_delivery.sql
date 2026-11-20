ALTER TABLE ux_close_task ADD COLUMN message_sent_at TIMESTAMP(3) NULL;
CREATE INDEX ux_close_send ON ux_close_task(done_at,message_sent_at,next_enqueue_at);
-- Pending Kafka work must be published again through RocketMQ after a stopped-writer cutover.
UPDATE ux_outbox SET sent_at=NULL,owner=NULL,lease_until=NULL,next_at=CURRENT_TIMESTAMP(3)
 WHERE request_id IN (SELECT id FROM ux_request WHERE state='ACCEPTED');
CREATE TABLE ux_mq_poison (
 id VARCHAR(36) PRIMARY KEY, topic VARCHAR(127) NOT NULL, broker VARCHAR(128) NOT NULL,
 queue_id INT NOT NULL, offset_id BIGINT NOT NULL, body TEXT NOT NULL,
 reason VARCHAR(128) NOT NULL, created_at TIMESTAMP(3) NOT NULL
);
