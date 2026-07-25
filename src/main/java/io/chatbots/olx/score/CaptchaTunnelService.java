package io.chatbots.olx.score;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides a URL a human can open to reach the bot's noVNC screen and solve a Google CAPTCHA.
 * <p>
 * The server sits behind NAT, so by default this spawns a Cloudflare quick tunnel
 * ({@code cloudflared tunnel --url http://localhost:6080}) when a CAPTCHA appears. The tunnel is
 * <b>kept alive while it is needed</b>: a fresh CAPTCHA reuses the same live tunnel (so the URL you
 * were sent keeps working), and it is only torn down after {@code ai.score.tunnel-idle-minutes} with
 * no CAPTCHA — never the instant the AI call returns. That was the old bug: closing on return killed
 * the tunnel between the noVNC page load and its WebSocket handshake, so the page opened but VNC
 * could not connect. Nothing stays exposed once idle, and the VNC session is still password-protected.
 * The quick-tunnel URL is random each time a new one is spawned (bot restart, or after an idle close).
 * <p>
 * If {@code ai.score.captcha-url} is set (LAN, Tailscale, port forward), it is used as-is and no
 * tunnel is started.
 */
@Slf4j
public class CaptchaTunnelService {

    private static final Pattern TUNNEL_URL = Pattern.compile("https://[\\w.-]+\\.trycloudflare\\.com");
    private static final int TUNNEL_START_TIMEOUT_SECONDS = 30;

    @Value("${ai.score.captcha-url:}")
    private String staticCaptchaUrl;
    @Value("${ai.score.tunnel-command:cloudflared tunnel --url http://localhost:6080 --no-autoupdate}")
    private String tunnelCommand;
    // DNS for a fresh trycloudflare subdomain can lag 1-2 min; don't post a link that 404s
    @Value("${ai.score.tunnel-ready-wait-seconds:120}")
    private int tunnelReadyWaitSeconds;
    // How long the tunnel lingers after the last CAPTCHA, so the human can open + connect + solve
    // without a race; a new CAPTCHA within the window resets it. Then it auto-closes (not exposed 24/7).
    @Value("${ai.score.tunnel-idle-minutes:15}")
    private int tunnelIdleMinutes;

    private Process tunnelProcess;
    private String accessUrl;
    private final ScheduledExecutorService idleCloser = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "captcha-tunnel-idle-closer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingClose;

    /** @return a browser URL for the human solver, or null if no access path could be provided */
    public synchronized String openAccessUrl() {
        if (StringUtils.isNotBlank(staticCaptchaUrl)) return staticCaptchaUrl;
        if (StringUtils.isBlank(tunnelCommand)) return null;

        cancelPendingClose();
        // Reuse a still-live tunnel so the URL already sent to the solver keeps working across CAPTCHAs.
        if (tunnelProcess != null && tunnelProcess.isAlive() && accessUrl != null) {
            log.info("Reusing live captcha tunnel: {}", accessUrl);
            return accessUrl;
        }

        close();
        try {
            tunnelProcess = new ProcessBuilder(tunnelCommand.trim().split("\\s+"))
                    .redirectErrorStream(true)
                    .start();
            BufferedReader output = new BufferedReader(new InputStreamReader(tunnelProcess.getInputStream()));
            CompletableFuture<String> urlFuture = CompletableFuture.supplyAsync(() -> scanForUrl(output));
            String url = urlFuture.get(TUNNEL_START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (url == null) {
                log.warn("Tunnel process produced no trycloudflare URL: {}", tunnelCommand);
                close();
                return null;
            }
            drainAsync(output);
            log.info("Captcha tunnel opened: {}", url);
            // autoconnect + scale-to-fit so the remote screen is usable on a phone
            accessUrl = url + "/vnc.html?autoconnect=1&resize=scale";
            waitUntilReachable(accessUrl);
            return accessUrl;
        } catch (Exception e) {
            log.warn("Failed to open captcha tunnel with command '{}'", tunnelCommand, e);
            close();
            return null;
        }
    }

    /**
     * Signals that the current CAPTCHA attempt is done, but keeps the tunnel up for
     * {@code tunnel-idle-minutes} so the solver can still open and connect without a race. A new
     * CAPTCHA (a fresh {@link #openAccessUrl()}) within that window cancels the pending close.
     * Callers use this in their {@code finally} instead of {@link #close()}.
     */
    public synchronized void release() {
        if (tunnelProcess == null) return; // static URL or nothing running — nothing to keep alive
        cancelPendingClose();
        if (tunnelIdleMinutes <= 0) {
            close();
            return;
        }
        pendingClose = idleCloser.schedule(this::close, tunnelIdleMinutes, TimeUnit.MINUTES);
        log.info("Captcha tunnel idle; will close in {} min unless another CAPTCHA arrives", tunnelIdleMinutes);
    }

    public synchronized void close() {
        cancelPendingClose();
        accessUrl = null;
        if (tunnelProcess != null) {
            log.info("Closing captcha tunnel");
            tunnelProcess.destroy();
            tunnelProcess = null;
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        close();
        idleCloser.shutdownNow();
    }

    private void cancelPendingClose() {
        if (pendingClose != null) {
            pendingClose.cancel(false);
            pendingClose = null;
        }
    }

    /** Poll the public URL until Cloudflare's edge serves it, so the chat link works on first tap. */
    private void waitUntilReachable(String url) {
        if (tunnelReadyWaitSeconds <= 0) return;
        long deadline = System.currentTimeMillis() + tunnelReadyWaitSeconds * 1000L;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    log.info("Captcha tunnel is publicly reachable");
                    return;
                }
                log.debug("Tunnel not ready yet, status {}", response.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("Tunnel not ready yet: {}", e.toString());
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Tunnel URL still not reachable after {}s, posting it anyway", tunnelReadyWaitSeconds);
    }

    private String scanForUrl(BufferedReader output) {
        try {
            String line;
            while ((line = output.readLine()) != null) {
                Matcher matcher = TUNNEL_URL.matcher(line);
                if (matcher.find()) return matcher.group();
            }
        } catch (Exception e) {
            log.debug("Tunnel output ended", e);
        }
        return null;
    }

    // keep the pipe drained so cloudflared can't block on a full stdout buffer
    private void drainAsync(BufferedReader output) {
        Thread drainer = new Thread(() -> {
            try {
                while (output.readLine() != null) {
                }
            } catch (Exception ignored) {
            }
        }, "captcha-tunnel-drain");
        drainer.setDaemon(true);
        drainer.start();
    }
}
