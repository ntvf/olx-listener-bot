CREATE TABLE listeners
(
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    chat_id            BIGINT       NOT NULL,
    user_first_name    VARCHAR(255),
    user_last_name     VARCHAR(255),
    user_name          VARCHAR(255),
    user_language_code VARCHAR(10),
    url                VARCHAR(2048),
    updated            TIMESTAMP,
    active             BOOLEAN      NOT NULL DEFAULT true
);

CREATE INDEX idx_listeners_chat_active ON listeners (chat_id, active);

CREATE TABLE listener_offer_hashes
(
    listener_id BIGINT      NOT NULL,
    hash        VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (listener_id, hash),
    CONSTRAINT fk_loh_listener FOREIGN KEY (listener_id) REFERENCES listeners (id) ON DELETE CASCADE
);

CREATE INDEX idx_loh_created_at ON listener_offer_hashes (created_at);
