-- Only explicitly linked sessions may be recommended as purchasable activities.
ALTER TABLE ux_experience_session ADD COLUMN activity_id BIGINT NULL;
ALTER TABLE ux_experience_session ADD CONSTRAINT ux_session_activity_fk FOREIGN KEY(activity_id) REFERENCES ux_activity(id);
CREATE UNIQUE INDEX ux_session_activity ON ux_experience_session(activity_id);

CREATE TABLE ux_assistant_quota (id INT PRIMARY KEY);
INSERT INTO ux_assistant_quota(id) VALUES(1);
CREATE TABLE ux_assistant_session (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL,
 state_json LONGTEXT NOT NULL, active_run VARCHAR(36),
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES ux_account(id)
);
CREATE INDEX ux_assistant_sessions_user ON ux_assistant_session(user_id,updated_at);
CREATE TABLE ux_assistant_run (
 id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL, message VARCHAR(1000) NOT NULL,
 status VARCHAR(24) NOT NULL, mode VARCHAR(16) NOT NULL,
 checkpoint_json LONGTEXT NOT NULL, result_json LONGTEXT,
 error_code VARCHAR(64), owner VARCHAR(36), lease_until TIMESTAMP(3),
 created_at TIMESTAMP(3) NOT NULL, deadline TIMESTAMP(3) NOT NULL, completed_at TIMESTAMP(3),
 UNIQUE(session_id,request_key),
 FOREIGN KEY(session_id) REFERENCES ux_assistant_session(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES ux_account(id),
 CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','TIMED_OUT'))
);
CREATE INDEX ux_assistant_ready ON ux_assistant_run(status,lease_until,created_at);
CREATE INDEX ux_assistant_user_runs ON ux_assistant_run(user_id,created_at);
CREATE TABLE ux_assistant_step (
 run_id VARCHAR(36) NOT NULL, step_no INT NOT NULL,
 tool_name VARCHAR(48) NOT NULL, arguments_json TEXT NOT NULL,
 result_json LONGTEXT NOT NULL, outcome VARCHAR(24) NOT NULL,
 elapsed_ms BIGINT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(run_id,step_no),
 FOREIGN KEY(run_id) REFERENCES ux_assistant_run(id) ON DELETE CASCADE
);
