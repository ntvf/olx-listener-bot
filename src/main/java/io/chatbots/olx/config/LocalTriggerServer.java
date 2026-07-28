package io.chatbots.olx.config;

import com.sun.net.httpserver.HttpServer;
import io.chatbots.olx.channel.ChannelFeedPoller;
import io.chatbots.olx.channel.ChannelPublisher;
import io.chatbots.olx.checker.RegressionChecker;
import io.chatbots.olx.furniture.FurnitureCatalogScraper;
import io.chatbots.olx.furniture.FurnitureFeedPoller;
import io.chatbots.olx.furniture.FurniturePhotoEnricher;
import io.chatbots.olx.furniture.FurniturePublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A loopback-only trigger for firing the pipeline's schedulers on demand, so a change can be watched
 * in real time instead of waiting out the 5–10 min tick. Built on the JDK's own {@code HttpServer}
 * (no web starter, no Tomcat) and bound to {@code 127.0.0.1}, so it is reachable <b>only from the
 * box itself</b> — i.e. over SSH ({@code curl localhost:PORT/trigger/ikea-enrich}), never from the
 * network. Disabled unless {@code admin.trigger-port} (env {@code ADMIN_TRIGGER_PORT}) is set > 0.
 */
@Slf4j
@Component
public class LocalTriggerServer {

    private final int port;
    private final Map<String, Runnable> actions = new LinkedHashMap<>();
    private HttpServer server;

    public LocalTriggerServer(@Value("${admin.trigger-port:0}") int port,
                              FurnitureFeedPoller furniturePoller,
                              FurniturePublisher furniturePublisher,
                              FurniturePhotoEnricher furniturePhotoEnricher,
                              FurnitureCatalogScraper furnitureCatalogScraper,
                              ChannelFeedPoller channelPoller,
                              ChannelPublisher channelPublisher,
                              RegressionChecker regressionChecker) {
        this.port = port;
        actions.put("ikea-poll", furniturePoller::pollAll);
        actions.put("ikea-publish", furniturePublisher::publishDue);
        actions.put("ikea-enrich", furniturePhotoEnricher::tick);
        actions.put("ikea-catalog", furnitureCatalogScraper::refreshNow);
        actions.put("ikea-catalog-force", furnitureCatalogScraper::forceRefresh);
        actions.put("rental-poll", channelPoller::pollAll);
        actions.put("rental-publish", channelPublisher::publishDue);
        actions.put("regression-check", regressionChecker::checkSitesForRegression);
    }

    @PostConstruct
    public void start() throws IOException {
        if (port <= 0) {
            log.info("Local trigger server disabled (set ADMIN_TRIGGER_PORT to enable)");
            return;
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/trigger", exchange -> {
            String action = exchange.getRequestURI().getPath().replaceFirst("^/trigger/?", "");
            String body;
            int status;
            if (action.isBlank() || "help".equals(action)) {
                body = usage();
                status = 200;
            } else if (!actions.containsKey(action)) {
                body = "unknown trigger '" + action + "'\n\n" + usage();
                status = 404;
            } else {
                try {
                    actions.get(action).run();
                    body = "triggered: " + action + "\n";
                    status = 200;
                } catch (Exception e) {
                    log.warn("Local trigger '{}' failed", action, e);
                    body = "failed: " + action + ": " + e + "\n";
                    status = 500;
                }
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        // A small pool so a long poll/publish request doesn't wedge the next trigger.
        server.setExecutor(Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "local-trigger");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.info("Local trigger server listening on 127.0.0.1:{} — triggers: {}", port, actions.keySet());
    }

    private String usage() {
        return "POST/GET one of:\n" + String.join("\n",
                actions.keySet().stream().map(a -> "  curl localhost:" + port + "/trigger/" + a).toList()) + "\n";
    }

    @PreDestroy
    public void stop() {
        if (server != null) server.stop(0);
    }
}
