CREATE TABLE channels
(
    chat_id  BIGINT PRIMARY KEY,
    title    VARCHAR(255),
    username VARCHAR(255),
    added_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE channel_feeds
(
    id              BIGSERIAL PRIMARY KEY,
    channel_chat_id BIGINT        NOT NULL REFERENCES channels (chat_id),
    feed_url        VARCHAR(2048) NOT NULL,
    label           VARCHAR(64),
    active          BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_channel_feeds_active ON channel_feeds (active);

CREATE TABLE feed_offers
(
    id              BIGSERIAL PRIMARY KEY,
    feed_id         BIGINT      NOT NULL REFERENCES channel_feeds (id) ON DELETE CASCADE,
    offer_hash      VARCHAR(64) NOT NULL,
    url             VARCHAR(2048),
    title           VARCHAR(512),
    price           NUMERIC(12, 2),
    currency        VARCHAR(8),
    extra_rent      NUMERIC(12, 2),
    area_m2         NUMERIC(8, 2),
    rooms           INT,
    location        VARCHAR(255),
    seller_id       VARCHAR(128),
    seller_business BOOLEAN,
    verdict         VARCHAR(16),
    image_url       VARCHAR(2048),
    first_seen      TIMESTAMP   NOT NULL DEFAULT NOW(),
    posted_at       TIMESTAMP,
    CONSTRAINT uq_feed_offer UNIQUE (feed_id, offer_hash)
);

CREATE INDEX idx_feed_offers_first_seen ON feed_offers (first_seen);
CREATE INDEX idx_feed_offers_seller ON feed_offers (seller_id);
