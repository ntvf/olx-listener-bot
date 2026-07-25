package io.chatbots.olx.furniture.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The current IKEA new-retail (catalog) price for one {@code model+variant}, scraped from ikea.pl.
 * A second anchor for a deal post ("−66% vs new") beside the used-market median. The key mirrors a
 * listing's {@code model+variant} exactly (same {@link io.chatbots.olx.furniture.FurnitureClassifier}
 * and {@link io.chatbots.olx.furniture.FurnitureVariantParser}), so a catalog BILLY 80 joins the used
 * BILLY 80s. A {@code variant == null} row carries the model-level minimum ("kat. od …") for listings
 * whose variant matches no exact catalog row.
 */
@Entity
@Table(name = "furniture_catalog_prices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FurnitureCatalogPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String model;

    /** Null for the model-level minimum "from" price; else the same variant key a listing carries. */
    @Column(length = 48)
    private String variant;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "scraped_at", nullable = false)
    private Instant scrapedAt;
}
