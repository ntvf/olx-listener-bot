package io.chatbots.olx.furniture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.chatbots.olx.furniture.entity.FurnitureCatalogCrawl;
import io.chatbots.olx.furniture.entity.FurnitureCatalogPrice;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import jakarta.annotation.PreDestroy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rebuilds the IKEA new-retail (catalog) price table from ikea.pl. IKEA has no price feed, but its
 * public <b>sitemap</b> lists every product URL and each product page carries a JSON-LD {@code Offer}
 * with price, currency and dimensions — a structured feed in all but name. The product page's
 * {@code name} reads like an OLX title ("BILLY Regał - biały 80x28x202 cm"), so the very same
 * {@link FurnitureClassifier} and {@link FurnitureVariantParser} that key a used listing key the
 * catalog row — a scraped BILLY 80 lands on the identical {@code model+variant} the used BILLY 80s
 * carry, no SKU mapping needed.
 *
 * <p>The full run touches thousands of pages, so it is deliberately decoupled: it runs on its own
 * single daemon thread (like {@link FurniturePhotoEnricher}) with a polite pause between fetches,
 * and never on the poll/publish hot path. A whole-table replace at the end keeps the anchor
 * consistent; a brief empty window only makes the publisher omit the "vs new" line, never post a
 * wrong one. The scheduled entry point re-scrapes only when the data is older than {@code maxAge}
 * (≈1.5 months), so a restart never re-triggers a multi-hour crawl.
 */
@Slf4j
public class FurnitureCatalogScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";
    private static final String SITEMAP_INDEX = "https://www.ikea.com/sitemaps/sitemap.xml";
    /** Country product sitemaps in the index look like {@code .../prod-pl-PL_3.xml}. */
    private static final String PL_PRODUCT_SITEMAP = "prod-pl-PL_";
    /** Staging rows flushed per batch — the resume granularity if the crawl is interrupted. */
    private static final int STAGING_FLUSH = 50;
    /** Longer than the crawl staging key column; a stray long URL is skipped rather than failing a batch. */
    private static final int MAX_URL_LENGTH = 512;

    private final FurnitureCatalogPriceRepository repository;
    private final FurnitureCatalogCrawlRepository crawlRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final Duration maxAge;
    private final long fetchPauseMs;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "furniture-catalog-scraper");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FurnitureCatalogScraper(FurnitureCatalogPriceRepository repository,
                                   FurnitureCatalogCrawlRepository crawlRepository,
                                   boolean enabled, Duration maxAge, long fetchPauseMs) {
        this.repository = repository;
        this.crawlRepository = crawlRepository;
        this.enabled = enabled;
        this.maxAge = maxAge;
        this.fetchPauseMs = fetchPauseMs;
    }

    /**
     * Scheduler entry point (runs at boot and daily). Resumes an interrupted crawl if the staging
     * table still holds rows; otherwise re-scrapes only when the catalog is empty or older than
     * {@code maxAge}.
     */
    public void refreshIfStale() {
        if (!enabled) return;
        if (crawlRepository.count() > 0) { // a crawl was interrupted (deploy/restart) — finish it
            refreshNow();
            return;
        }
        Instant newest = repository.findMaxScrapedAt();
        if (newest != null && newest.isAfter(Instant.now().minus(maxAge))) return;
        refreshNow();
    }

    /** Trigger entry point: resume/refresh, reusing whatever the staging table already holds. */
    public void refreshNow() {
        submit(false);
    }

    /** Force entry point: wipe the staging cache and re-fetch every product from scratch. */
    public void forceRefresh() {
        submit(true);
    }

    private void submit(boolean force) {
        if (!enabled) {
            log.info("Furniture catalog scraper disabled (furniture.catalog.enabled=false)");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Furniture catalog scrape already in progress — skipping");
            return;
        }
        worker.submit(() -> {
            try {
                crawl(force);
            } catch (Exception e) {
                log.error("Furniture catalog scrape failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * Fetches every matching product page and stages each result as it goes, so a restart resumes
     * from the URLs not yet in the staging table instead of starting over. The live catalog keeps the
     * previous run's prices until this crawl finishes, then is replaced in one swap and the staging
     * table cleared. {@code force} wipes the staging cache first for a clean full re-scrape.
     */
    void crawl(boolean force) {
        if (force) {
            log.info("Furniture catalog crawl: force refresh — clearing staging cache");
            crawlRepository.deleteAllInBatch();
        }
        Set<String> models = FurnitureClassifier.models();
        List<String> urls = collectProductUrls(models);
        Set<String> done = crawlRepository.findAllUrls();
        int total = urls.size();
        log.info("Furniture catalog crawl: {} product URLs match {} models ({} already staged, resuming)",
                total, models.size(), done.size());

        List<FurnitureCatalogCrawl> buffer = new ArrayList<>();
        int processed = done.size();
        for (String url : urls) {
            if (done.contains(url)) continue;
            buffer.add(stagingRow(url, fetchProduct(url)));
            processed++;
            if (buffer.size() >= STAGING_FLUSH) {
                crawlRepository.saveAll(buffer);
                buffer.clear();
            }
            if (processed % 200 == 0) {
                log.info("Furniture catalog crawl progress: {}/{} fetched", processed, total);
            }
            pause();
        }
        if (!buffer.isEmpty()) crawlRepository.saveAll(buffer);

        aggregateAndSwap();
    }

    private FurnitureCatalogCrawl stagingRow(String url, Product p) {
        return FurnitureCatalogCrawl.builder()
                .url(url)
                .model(p == null ? null : p.model())
                .variant(p == null ? null : p.variant())
                .price(p == null ? null : p.price())
                .currency(p == null ? null : p.currency())
                .fetchedAt(Instant.now())
                .build();
    }

    /** Aggregate the staged rows into the live catalog (min per key), swap them in, then clear staging. */
    void aggregateAndSwap() {
        List<FurnitureCatalogCrawl> staged = crawlRepository.findPriced();
        // min price per (model, variant), and per model (for the variant-null "from" row)
        Map<String, FurnitureCatalogPrice> byKey = new LinkedHashMap<>();
        Map<String, FurnitureCatalogPrice> modelMin = new LinkedHashMap<>();
        for (FurnitureCatalogCrawl c : staged) {
            // variant-null goes only to modelMin; a byKey (model, null) row would collide with it on the variant-is-null unique index
            if (c.getVariant() != null) accumulate(byKey, keyOf(c.getModel(), c.getVariant()), c, c.getVariant());
            accumulate(modelMin, c.getModel(), c, null);
        }
        List<FurnitureCatalogPrice> rows = new ArrayList<>(byKey.values());
        rows.addAll(modelMin.values());
        if (rows.isEmpty()) {
            log.warn("Furniture catalog crawl produced no priced rows — keeping the existing catalog");
        } else {
            repository.deleteAllInBatch();
            repository.saveAll(rows);
            log.info("Furniture catalog crawl done: {} priced products → {} (model,variant) rows + {} model rows",
                    staged.size(), byKey.size(), modelMin.size());
        }
        crawlRepository.deleteAllInBatch(); // crawl complete — clear staging for the next run
    }

    /** Keeps the cheapest sighting for a key (colours/finishes of one size vary; the min is the honest "from"). */
    private void accumulate(Map<String, FurnitureCatalogPrice> into, String key, FurnitureCatalogCrawl c, String variant) {
        FurnitureCatalogPrice existing = into.get(key);
        if (existing != null && existing.getPrice().compareTo(c.getPrice()) <= 0) return;
        into.put(key, FurnitureCatalogPrice.builder()
                .model(c.getModel()).variant(variant).price(c.getPrice()).currency(c.getCurrency())
                .sourceUrl(c.getUrl()).scrapedAt(Instant.now()).build());
    }

    private static String keyOf(String model, String variant) {
        return model + " " + (variant == null ? "" : variant);
    }

    /** Every {@code /p/<model>-…} product URL in the PL sitemaps whose leading slug token is a known model. */
    List<String> collectProductUrls(Set<String> models) {
        List<String> out = new ArrayList<>();
        for (String sitemap : sitemapUrls()) {
            if (!sitemap.contains(PL_PRODUCT_SITEMAP)) continue;
            for (String loc : locs(sitemap)) {
                if (loc.length() <= MAX_URL_LENGTH && models.contains(slugModel(loc))) out.add(loc);
            }
        }
        return out;
    }

    private List<String> sitemapUrls() {
        return locs(SITEMAP_INDEX);
    }

    /** The leading, model-bearing token of a {@code /p/<slug>-<article>/} URL, folded like the model set. */
    static String slugModel(String url) {
        int p = url.indexOf("/p/");
        if (p < 0) return "";
        String slug = url.substring(p + 3);
        int dash = slug.indexOf('-');
        String first = dash < 0 ? slug : slug.substring(0, dash);
        return FurnitureClassifier.fold(first);
    }

    private List<String> locs(String sitemapUrl) {
        try {
            Document doc = Jsoup.connect(sitemapUrl)
                    .userAgent(USER_AGENT)
                    .parser(Parser.xmlParser())
                    .maxBodySize(0)
                    .timeout(30_000)
                    .get();
            List<String> out = new ArrayList<>();
            for (Element loc : doc.select("loc")) {
                String text = loc.text().trim();
                if (!text.isEmpty()) out.add(text);
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to read sitemap {}: {}", sitemapUrl, e.toString());
            return List.of();
        }
    }

    /** One priced product parsed from a product page's JSON-LD, or null when unreachable/unpriced. */
    record Product(String model, String variant, BigDecimal price, String currency, String url) {
    }

    Product fetchProduct(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                    .timeout(20_000)
                    .get();
            for (Element script : doc.select("script[type=application/ld+json]")) {
                Product p = parseProduct(script.data(), url);
                if (p != null) return p;
            }
        } catch (Exception e) {
            log.debug("Failed to fetch catalog product {}: {}", url, e.toString());
        }
        return null;
    }

    /** Parse the JSON-LD Product block into a keyed price, reusing the listing classifier/variant parser. */
    Product parseProduct(String jsonLd, String url) {
        try {
            JsonNode node = objectMapper.readTree(jsonLd);
            JsonNode product = findProductNode(node);
            if (product == null) return null;

            JsonNode offers = product.path("offers");
            if (offers.isArray() && !offers.isEmpty()) offers = offers.get(0);
            String amount = offers.path("price").asText(null);
            if (StringUtils.isBlank(amount)) return null;
            BigDecimal price;
            try {
                price = new BigDecimal(amount.replace(",", "."));
            } catch (NumberFormatException e) {
                return null;
            }
            if (price.signum() <= 0) return null;
            String currency = offers.path("priceCurrency").asText("PLN");

            String name = product.path("name").asText("");
            String model = StringUtils.defaultIfBlank(FurnitureClassifier.modelFor(name, null), slugModelUpper(url));
            if (StringUtils.isBlank(model)) return null;
            String description = product.path("description").asText("");
            String variant = FurnitureVariantParser.parse(name, description).label();
            return new Product(model, variant, price, currency, url);
        } catch (Exception e) {
            log.debug("Failed to parse catalog JSON-LD on {}: {}", url, e.toString());
            return null;
        }
    }

    /** Fallback model from the URL slug (already folded) when the name carries no known model token. */
    private static String slugModelUpper(String url) {
        String folded = slugModel(url);
        return folded.isEmpty() ? null : folded.toUpperCase(java.util.Locale.ROOT);
    }

    private JsonNode findProductNode(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = findProductNode(item);
                if (found != null) return found;
            }
            return null;
        }
        String type = node.path("@type").isArray()
                ? node.path("@type").get(0).asText("")
                : node.path("@type").asText("");
        if ("Product".equalsIgnoreCase(type) && node.hasNonNull("offers")) return node;
        return findProductNode(node.get("@graph"));
    }

    private void pause() {
        if (fetchPauseMs <= 0) return;
        try {
            Thread.sleep(fetchPauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }
}
