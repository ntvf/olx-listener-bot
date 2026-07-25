package io.chatbots.olx.furniture.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One fetched product in an in-flight catalog crawl — the staging that makes the crawl resumable.
 * Its presence marks the {@link #url} done, so a restart mid-crawl skips it instead of re-fetching.
 * A null {@link #price} means the page was fetched but carried no usable model/price (still done).
 * On completion these rows are aggregated (min per model+variant) into {@code furniture_catalog_prices}
 * and this table is cleared.
 */
@Entity
@Table(name = "furniture_catalog_crawl")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FurnitureCatalogCrawl {

    @Id
    @Column(length = 512)
    private String url;

    @Column(length = 32)
    private String model;

    @Column(length = 48)
    private String variant;

    private BigDecimal price;

    @Column(length = 8)
    private String currency;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
