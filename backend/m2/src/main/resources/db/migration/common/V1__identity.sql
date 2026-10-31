CREATE TABLE users (id CHAR(36) PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, display_name VARCHAR(100) NOT NULL, email VARCHAR(200) NOT NULL, password_hash VARCHAR(100) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE);
CREATE TABLE permissions (user_id CHAR(36) NOT NULL, permission VARCHAR(80) NOT NULL, PRIMARY KEY(user_id,permission), FOREIGN KEY(user_id) REFERENCES users(id));
CREATE TABLE sessions (token_hash CHAR(64) PRIMARY KEY, user_id CHAR(36) NOT NULL, expires_at TIMESTAMP(6) NOT NULL, FOREIGN KEY(user_id) REFERENCES users(id));
CREATE INDEX ix_sessions_expiry ON sessions(expires_at);
