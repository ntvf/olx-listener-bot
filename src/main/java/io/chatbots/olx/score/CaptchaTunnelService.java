package io.chatbots.olx.score;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides a URL a human can open to reach the bot's noVNC screen and solve a Google CAPTCHA.
 * <p>
 * The server sits behind NAT, so by default this spawns an ephemeral Cloudflare quick tunnel
 * ({@code cloudflared tunnel --url http://localhost:6080}) when a CAPTCHA appears and kills it
 * once the score request finishes — nothing stays exposed. The tunnel URL is random per run and
 * the VNC session is still password-protected.
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

    private Process tunnelProcess;

    /** @return a browser URL for the human solver, or null if no access path could be provided */
    public synchronized String openAccessUrl() {
        if (StringUtils.isNotBlank(staticCaptchaUrl)) return staticCaptchaUrl;
        if (StringUtils.isBlank(tunnelCommand)) return null;

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
            return url + "/vnc.html";
        } catch (Exception e) {
            log.warn("Failed to open captcha tunnel with command '{}'", tunnelCommand, e);
            close();
            return null;
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (tunnelProcess != null) {
            log.info("Closing captcha tunnel");
            tunnelProcess.destroy();
            tunnelProcess = null;
        }
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
                    // discard
                }
            } catch (Exception ignored) {
                // process closed
            }
        }, "captcha-tunnel-drain");
        drainer.setDaemon(true);
        drainer.start();
    }
}
