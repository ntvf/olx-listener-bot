package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.entity.FurnitureCatalogPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FurnitureCatalogPriceRepository extends JpaRepository<FurnitureCatalogPrice, Long> {

    /** The exact catalog row for a listing's model+variant, when one was scraped. */
    Optional<FurnitureCatalogPrice> findByModelAndVariant(String model, String variant);

    /** The model-level minimum ("from") row — the fallback when no exact variant matches. */
    @Query("SELECT c FROM FurnitureCatalogPrice c WHERE c.model = :model AND c.variant IS NULL")
    Optional<FurnitureCatalogPrice> findModelLevel(@Param("model") String model);

    /** Newest scrape timestamp across the table, or null when empty — drives the staleness check. */
    @Query("SELECT MAX(c.scrapedAt) FROM FurnitureCatalogPrice c")
    Instant findMaxScrapedAt();

    List<FurnitureCatalogPrice> findByModel(String model);
}
