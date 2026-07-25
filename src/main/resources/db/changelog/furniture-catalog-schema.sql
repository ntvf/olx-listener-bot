-- Current IKEA new-retail (catalog) prices, scraped from ikea.pl, used as a second
-- anchor in a deal post ("−66% vs new") alongside the used-market median. Keyed on the
-- same (model, variant) scheme FurnitureClassifier/FurnitureVariantParser derive for
-- listings, so a scraped BILLY 80 joins the used BILLY 80s. A variant-null row per model
-- holds the model-level minimum ("kat. od …") for listings whose variant does not match
-- an exact catalog row. Fully re-scraped on a schedule, so it is replace-in-place.

CREATE TABLE furniture_catalog_prices
(
    id         BIGSERIAL PRIMARY KEY,
    model      VARCHAR(32)   NOT NULL,
    -- null = the model-level minimum ("from") price, used when no exact variant matches.
    variant    VARCHAR(48),
    price      NUMERIC(12, 2) NOT NULL,
    currency   VARCHAR(8)    NOT NULL,
    source_url VARCHAR(2048),
    scraped_at TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- One price per (model, variant); a partial unique index treats the model-level row
-- (variant IS NULL) as a single distinct key, which a plain UNIQUE would not enforce.
CREATE UNIQUE INDEX uq_furniture_catalog_model_variant
    ON furniture_catalog_prices (model, variant);
CREATE UNIQUE INDEX uq_furniture_catalog_model_only
    ON furniture_catalog_prices (model) WHERE variant IS NULL;
