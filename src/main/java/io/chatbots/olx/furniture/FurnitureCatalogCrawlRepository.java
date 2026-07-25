package io.chatbots.olx.furniture;

import io.chatbots.olx.furniture.entity.FurnitureCatalogCrawl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;

public interface FurnitureCatalogCrawlRepository extends JpaRepository<FurnitureCatalogCrawl, String> {

    /** URLs already fetched in the current crawl — the "resume" skip set. */
    @Query("SELECT c.url FROM FurnitureCatalogCrawl c")
    Set<String> findAllUrls();

    /** Fetched products that carried a usable price, for the end-of-crawl aggregation. */
    @Query("SELECT c FROM FurnitureCatalogCrawl c WHERE c.price IS NOT NULL AND c.model IS NOT NULL")
    List<FurnitureCatalogCrawl> findPriced();
}
