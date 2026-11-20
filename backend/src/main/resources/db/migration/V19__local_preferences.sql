CREATE TABLE ux_life_preference (
 user_id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 preferences_json TEXT NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 updated_at TIMESTAMP(3) NOT NULL, FOREIGN KEY(user_id) REFERENCES ux_account(id)
);
