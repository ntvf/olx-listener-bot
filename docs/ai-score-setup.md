# "score" feature — Google AI Mode deal check

Post `score https://www.olx.ua/d/obyavlenie/...` to a chat with the bot and it will:

1. Scrape the listing (title, price, location, description).
2. Ask Google Search **AI Mode** whether it's a good deal for a flipper on the same
   regional market / online, a realistic resale price point, and how liquid the item is.
3. Post the parsed answer as a follow-up message.

There is no menu entry — only people who know the prefix can trigger it. The prefix
defaults to `score` but **this repo is public, so set your own code word** via
`AI_SCORE_PREFIX`. In groups, either disable the bot's privacy mode in BotFather or
mention it: `@your_bot score https://...`.

## Why the server needs a display

Google serves *"AI Mode is not currently available on your device or account"* to
headless browsers, so Playwright runs a **headed** Chromium. On the N100 that means a
virtual display (Xvfb). The same display is shared over noVNC so a human can solve a
CAPTCHA remotely when Google asks for one.

## One-time setup on the N100 (Ubuntu)

```sh
sudo apt-get update
sudo apt-get install -y xvfb x11vnc novnc websockify \
    libnss3 libnspr4 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 \
    libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
    libgbm1 libasound2t64 libpango-1.0-0 libcairo2

# cloudflared — used to open an ephemeral tunnel to noVNC when a CAPTCHA appears
# (the server is behind NAT, so there is no directly reachable address)
curl -LO https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
sudo dpkg -i cloudflared-linux-amd64.deb
```

(Playwright downloads its own Chromium on first run; the libs above are its runtime
dependencies. On older Ubuntu use the non-`t64` package names.)

### systemd units

`/etc/systemd/system/xvfb.service`:

```ini
[Unit]
Description=Virtual framebuffer display :99

[Service]
ExecStart=/usr/bin/Xvfb :99 -screen 0 1280x900x24
Restart=always

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/x11vnc.service`:

```ini
[Unit]
Description=VNC server for display :99
After=xvfb.service
Requires=xvfb.service

[Service]
# Set a password once: x11vnc -storepasswd /etc/x11vnc.pass
ExecStart=/usr/bin/x11vnc -display :99 -rfbauth /etc/x11vnc.pass -forever -shared -localhost -rfbport 5900
Restart=always

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/novnc.service`:

```ini
[Unit]
Description=noVNC web client on :6080
After=x11vnc.service
Requires=x11vnc.service

[Service]
ExecStart=/usr/bin/websockify --web=/usr/share/novnc 6080 localhost:5900
Restart=always

[Install]
WantedBy=multi-user.target
```

```sh
sudo x11vnc -storepasswd /etc/x11vnc.pass   # pick a VNC password
sudo systemctl enable --now xvfb x11vnc novnc
```

### Bot service environment

Add to the bot's systemd unit / environment:

```sh
DISPLAY=:99
# optional overrides
AI_SCORE_PREFIX=your-secret-word
AI_SCORE_PROFILE_DIR=/opt/olx-bot/.ai-chrome-profile
AI_SCORE_CAPTCHA_WAIT=240
AI_SCORE_ANSWER_TIMEOUT=90
AI_SCORE_ENABLED=true
# only if the server IS directly reachable (LAN/Tailscale/VPN) — disables the tunnel:
#AI_SCORE_CAPTCHA_URL=http://<server-address>:6080/vnc.html
# override the tunnel command if cloudflared lives elsewhere or you prefer another tool:
#AI_SCORE_TUNNEL_COMMAND=cloudflared tunnel --url http://localhost:6080 --no-autoupdate
```

## CAPTCHA flow (server behind NAT)

When Google shows its `/sorry/` page, the bot starts an **ephemeral Cloudflare quick
tunnel** to the local noVNC port and posts the generated
`https://<random>.trycloudflare.com/vnc.html` link to the chat. Open it, enter the VNC
password, solve the CAPTCHA in the browser window you see, and the bot continues
automatically (it waits `AI_SCORE_CAPTCHA_WAIT` seconds). The tunnel is killed as soon
as the score request finishes, and the URL is random per incident — nothing stays
exposed. Cookies live in the persistent profile, so CAPTCHAs should be rare afterwards.

If `AI_SCORE_CAPTCHA_URL` is set, it is posted as-is and no tunnel is started.

## Notes / limitations

- Queries are serialized — one AI Mode search at a time; a score takes ~20–60 s.
- Automating google.com is against Google's ToS. This is a low-volume personal tool and
  deliberately does **not** evade CAPTCHAs — a human solves them.
- Google rotates its markup; if answers stop parsing, update `ANSWER_SELECTORS` in
  `AiModeSearchService`.
- First run on the server downloads Chromium (~170 MB) into `~/.cache/ms-playwright`.
