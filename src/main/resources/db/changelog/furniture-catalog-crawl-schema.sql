-- Per-URL staging for a resumable IKEA catalog crawl. FurnitureCatalogScraper fetches thousands
-- of product pages over hours; a restart (deploy) used to lose all progress because prices were
-- only written at the end. Now each fetched product is recorded here as it is scraped, so a restart
-- resumes from the URLs not yet present. When every URL is done, the rows are aggregated (min price
-- per model+variant) into furniture_catalog_prices and this table is cleared. While a crawl is in
-- flight the live furniture_catalog_prices keeps the previous run's prices, so the "vs new" line
-- never goes blank mid-crawl.

CREATE TABLE furniture_catalog_crawl
(
    -- IKEA product URLs are short (~90 chars); 512 keeps this well under the btree key-size limit
    -- so it can be the primary key (the crawl's "already done" set is a lookup on it).
    url        VARCHAR(512) PRIMARY KEY,
    model      VARCHAR(32),
    -- null price = the page was fetched but carried no usable model/price; the row still marks the
    -- URL done so a resume does not re-fetch it.
    variant    VARCHAR(48),
    price      NUMERIC(12, 2),
    currency   VARCHAR(8),
    fetched_at TIMESTAMP NOT NULL DEFAULT NOW()
);
