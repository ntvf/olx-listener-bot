-- Isolated schema for the used-IKEA deal channel. Kept separate from the rental
-- channel tables (channel_feeds / feed_offers) so the two funnels never interfere.
-- The channels table is shared (a Telegram channel is registered once, on bot promotion).

CREATE TABLE furniture_feeds
(
    id              BIGSERIAL PRIMARY KEY,
    channel_chat_id BIGINT        NOT NULL REFERENCES channels (chat_id),
    -- one broad "q=ikea + warszawa" search; the IKEA model of each listing is detected in code
    -- (FurnitureClassifier), which also defines the median segment.
    feed_url        VARCHAR(2048) NOT NULL,
    label           VARCHAR(64),
    active          BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_furniture_feeds_active ON furniture_feeds (active);

CREATE TABLE furniture_offers
(
    id                 BIGSERIAL PRIMARY KEY,
    feed_id            BIGINT      NOT NULL REFERENCES furniture_feeds (id) ON DELETE CASCADE,
    offer_hash         VARCHAR(64) NOT NULL,
    url                VARCHAR(2048),
    title              VARCHAR(512),
    price              NUMERIC(12, 2),
    currency           VARCHAR(8),
    model              VARCHAR(32),
    -- true for parts/accessories (a door, drawer, cover…) or sub-floor prices: kept out of
    -- the model median and never posted, but stored so they aren't re-enriched every poll.
    part               BOOLEAN     NOT NULL DEFAULT false,
    image_url          VARCHAR(2048),
    first_seen         TIMESTAMP   NOT NULL DEFAULT NOW(),
    listing_created_at TIMESTAMP,
    published_at       TIMESTAMP   NOT NULL,
    posted_at          TIMESTAMP,
    CONSTRAINT uq_furniture_offer UNIQUE (feed_id, offer_hash)
);

CREATE INDEX idx_furniture_offers_first_seen ON furniture_offers (first_seen);
CREATE INDEX idx_furniture_offers_published_at ON furniture_offers (published_at);
CREATE INDEX idx_furniture_offers_feed_model ON furniture_offers (feed_id, model);
