package com.deanxbox.stats4us.web;

import com.deanxbox.stats4us.Stats4UsMod;
import com.deanxbox.stats4us.config.Stats4UsConfig;
import com.deanxbox.stats4us.stats.StatsService;
import com.deanxbox.stats4us.stats.StatsSnapshotDto;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

public final class StatsWebServer {
    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private final MinecraftServer minecraftServer;
    private final StatsService statsService;
    private final Stats4UsConfig config;

    private HttpServer httpServer;
    private ExecutorService executor;
    private String address = "disabled";

    public StatsWebServer(final MinecraftServer minecraftServer, final StatsService statsService, final Stats4UsConfig config) {
        this.minecraftServer = minecraftServer;
        this.statsService = statsService;
        this.config = config;
    }

    public void start() {
        try {
            InetAddress bindAddress = InetAddress.getByName(config.web.bindAddress);
            InetSocketAddress socketAddress = new InetSocketAddress(bindAddress, config.web.port);
            httpServer = HttpServer.create(socketAddress, 0);
            httpServer.createContext("/", this::handleIndex);
            httpServer.createContext("/favicon.png", this::handleFavicon);
            httpServer.createContext("/api/stats", this::handleStats);
            executor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "Stats4Us Web");
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(executor);
            httpServer.start();
            address = displayAddress();
            Stats4UsMod.LOGGER.info(
                "Stats4Us web dashboard listening on {}:{}; open {}",
                config.web.bindAddress,
                config.web.port,
                address
            );
        } catch (IOException exception) {
            Stats4UsMod.LOGGER.error("Failed to start Stats4Us web dashboard on {}:{}.", config.web.bindAddress, config.web.port, exception);
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            Stats4UsMod.LOGGER.info("Stats4Us web dashboard stopped.");
        }
    }

    public boolean isRunning() {
        return httpServer != null;
    }

    public String address() {
        return address;
    }

    private String displayAddress() {
        String host = "0.0.0.0".equals(config.web.bindAddress) || "::".equals(config.web.bindAddress)
            ? detectedHostAddress()
            : config.web.bindAddress;
        host = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + host + ":" + config.web.port + "/";
    }

    private String detectedHostAddress() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)
                        || address.isLoopbackAddress()
                        || address.isAnyLocalAddress()
                        || address.isMulticastAddress()
                        || address.isLinkLocalAddress()) {
                        continue;
                    }
                    if (address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                    if (fallback == null) {
                        fallback = address.getHostAddress();
                    }
                }
            }

            if (fallback != null) {
                return fallback;
            }

            InetAddress localHost = InetAddress.getLocalHost();
            if (localHost instanceof Inet4Address
                && !localHost.isLoopbackAddress()
                && !localHost.isAnyLocalAddress()
                && !localHost.isMulticastAddress()
                && !localHost.isLinkLocalAddress()) {
                return localHost.getHostAddress();
            }
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.debug("Failed to detect local Stats4Us dashboard address.", exception);
        }

        return "localhost";
    }

    private void handleIndex(final HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }

        send(exchange, 200, "text/html", indexHtml());
    }

    private void handleFavicon(final HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }

        try (InputStream stream = StatsWebServer.class.getResourceAsStream("/assets/stats4us/icon.png")) {
            if (stream == null) {
                send(exchange, 404, "text/plain", "Not found");
                return;
            }
            send(exchange, 200, "image/png", stream.readAllBytes());
        }
    }

    private void handleStats(final HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json", "{\"error\":\"Method not allowed\"}");
            return;
        }

        CompletableFuture<StatsSnapshotDto> future = new CompletableFuture<>();
        minecraftServer.execute(() -> {
            try {
                future.complete(statsService.snapshot());
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });

        try {
            StatsSnapshotDto snapshot = future.get(config.web.requestTimeoutSeconds, TimeUnit.SECONDS);
            send(exchange, 200, "application/json", GSON.toJson(snapshot));
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.warn("Failed to create Stats4Us web snapshot.", exception);
            send(exchange, 500, "application/json", "{\"error\":\"Failed to collect statistics\"}");
        }
    }

    private void send(final HttpExchange exchange, final int status, final String contentType, final String body) throws IOException {
        send(exchange, status, contentType + "; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(final HttpExchange exchange, final int status, final String contentType, byte[] bytes) throws IOException {
        if (exchange.getRequestHeaders().getFirst("Accept-Encoding") != null
            && exchange.getRequestHeaders().getFirst("Accept-Encoding").contains("gzip")) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(bytes);
            }
            bytes = compressed.toByteArray();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().set("Vary", "Accept-Encoding");
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; connect-src 'self'; img-src 'self' data:; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    private String indexHtml() {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Stats4Us</title>
              <link rel="icon" href="/favicon.png" type="image/png">
              <style>
                :root {
                  --bg: #0d1117;
                  --panel: rgba(22, 27, 34, .94);
                  --panel-2: #101820;
                  --line: rgba(139, 148, 158, .24);
                  --text: #e6edf3;
                  --muted: #8b949e;
                  --green: #49d17d;
                  --gold: #f0b44c;
                  --red: #ff6b6b;
                  --stone: #687076;
                  --shadow: 0 18px 50px rgba(0, 0, 0, .35);
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  min-height: 100vh;
                  color: var(--text);
                  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  background:
                    radial-gradient(circle at top left, rgba(73, 209, 125, .16), transparent 32rem),
                    radial-gradient(circle at top right, rgba(240, 180, 76, .14), transparent 26rem),
                    linear-gradient(135deg, #071017 0%, var(--bg) 46%, #14100b 100%);
                }
                header {
                  padding: 42px clamp(18px, 4vw, 56px) 24px;
                  border-bottom: 1px solid var(--line);
                  background: linear-gradient(180deg, rgba(255,255,255,.05), rgba(255,255,255,0));
                }
                .brand {
                  display: flex;
                  align-items: center;
                  gap: 18px;
                }
                .cube {
                  width: 54px;
                  height: 54px;
                  border-radius: 13px;
                  background:
                    linear-gradient(135deg, rgba(255,255,255,.25), transparent 35%),
                    linear-gradient(45deg, #5b8c3a 0 48%, #7c5a36 48% 70%, #5d6468 70%);
                  box-shadow: inset 0 -10px rgba(0,0,0,.18), var(--shadow);
                }
                h1 {
                  margin: 0;
                  font-size: clamp(2rem, 4vw, 4rem);
                  letter-spacing: -0.06em;
                }
                .subtitle {
                  margin: 8px 0 0;
                  color: var(--muted);
                  font-size: 1.05rem;
                }
                main {
                  padding: 28px clamp(18px, 4vw, 56px) 56px;
                }
                .toolbar, .summary, .player {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 22px;
                  box-shadow: var(--shadow);
                  backdrop-filter: blur(14px);
                }
                .toolbar {
                  padding: 16px;
                  position: sticky;
                  top: 12px;
                  z-index: 5;
                }
                .toolbar-group {
                  display: flex;
                  justify-content: center;
                  gap: 10px;
                  margin: 0 auto;
                  width: min(100%, 430px);
                }
                .toolbar-group select { flex: 1; min-width: 0; }
                input, select, button {
                  color: var(--text);
                  background: #0b1320;
                  border: 1px solid var(--line);
                  border-radius: 14px;
                  padding: 12px 14px;
                  font: inherit;
                }
                button {
                  cursor: pointer;
                  background: linear-gradient(180deg, #1f7a45, #155f35);
                  border-color: rgba(73, 209, 125, .45);
                  font-weight: 700;
                }
                button:disabled { cursor: wait; opacity: .65; }
                input:focus-visible, select:focus-visible, button:focus-visible {
                  outline: 3px solid rgba(73, 209, 125, .55);
                  outline-offset: 2px;
                }
                .tabs {
                  display: flex;
                  gap: 10px;
                  flex-wrap: wrap;
                  margin: 18px 0 0;
                }
                .tab {
                  background: rgba(255,255,255,.04);
                  border-color: var(--line);
                }
                .tab.active {
                  background: linear-gradient(180deg, #215f3c, #143f2a);
                  border-color: rgba(73, 209, 125, .55);
                }
                .view {
                  display: none;
                }
                .view.active {
                  display: block;
                }
                .summary {
                  display: grid;
                  grid-template-columns: repeat(4, minmax(0, 1fr));
                  gap: 1px;
                  overflow: hidden;
                  margin: 22px 0;
                }
                .metric {
                  padding: 20px;
                  background: rgba(255,255,255,.025);
                }
                .metric span {
                  display: block;
                  color: var(--muted);
                  font-size: .82rem;
                  text-transform: uppercase;
                  letter-spacing: .1em;
                }
                .metric strong {
                  display: block;
                  margin-top: 8px;
                  font-size: 1.5rem;
                }
                .players {
                  display: grid;
                  gap: 18px;
                }
                .leaderboards, .stat-grid {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
                  gap: 18px;
                }
                .charts {
                  display: block;
                }
                .leaderboard-tools {
                  display: flex;
                  justify-content: space-between;
                  gap: 14px;
                  align-items: center;
                  flex-wrap: wrap;
                  margin-bottom: 18px;
                  padding: 14px 16px;
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 18px;
                  box-shadow: var(--shadow);
                }
                .visibility-select { width: min(100%, 310px); }
                .toggle {
                  background: rgba(255,255,255,.04);
                  border-color: var(--line);
                }
                .toggle.active {
                  background: linear-gradient(180deg, #7b5a18, #5a3d10);
                  border-color: rgba(240, 180, 76, .55);
                }
                .board, .stat-card, .chart-panel {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 22px;
                  box-shadow: var(--shadow);
                  overflow: hidden;
                  min-width: 0;
                }
                .board-head, .stat-card, .chart-panel {
                  padding: 18px;
                }
                .board-head {
                  border-bottom: 1px solid var(--line);
                  background: linear-gradient(90deg, rgba(73,209,125,.07), rgba(240,180,76,.035));
                  display: grid;
                  gap: 10px;
                }
                .board h2, .stat-card h3 {
                  margin: 0 0 10px;
                  overflow-wrap: anywhere;
                }
                .board select, .board input {
                  width: 100%;
                  min-width: 0;
                }
                .stat-card p {
                  margin: 8px 0 0;
                  color: var(--muted);
                  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                  font-size: .82rem;
                  overflow-wrap: anywhere;
                }
                .top-player {
                  color: var(--gold);
                  font-weight: 800;
                }
                .chart-controls {
                  display: grid;
                  gap: 10px;
                  margin-bottom: 16px;
                }
                .chart-controls select, .chart-controls input {
                  width: 100%;
                  min-width: 0;
                }
                .chart-panel h2 {
                  margin: 0 0 14px;
                }
                .chart-subtitle {
                  margin: -6px 0 14px;
                  color: var(--muted);
                  line-height: 1.45;
                  overflow-wrap: anywhere;
                }
                .chart-panel.wide {
                  grid-column: 1 / -1;
                }
                .column-chart {
                  display: grid;
                  grid-template-columns: max-content minmax(0, 1fr);
                  gap: 10px;
                  align-items: start;
                }
                .column-y-axis {
                  display: flex;
                  flex-direction: column;
                  justify-content: space-between;
                  height: 260px;
                  padding-top: 24px;
                  color: var(--muted);
                  font-size: .72rem;
                  text-align: right;
                }
                .column-scroll {
                  overflow-x: auto;
                  padding-bottom: 6px;
                }
                .column-plot {
                  display: grid;
                  gap: clamp(8px, 1.4vw, 18px);
                  min-width: max(100%, 520px);
                  height: 330px;
                  padding: 0 10px 8px;
                  border-bottom: 1px solid var(--line);
                  background: repeating-linear-gradient(to top, rgba(139,148,158,.1) 0 1px, transparent 1px 25%);
                }
                .column-item {
                  display: grid;
                  grid-template-rows: 24px 236px 54px;
                  min-width: 0;
                  text-align: center;
                }
                .column-value {
                  color: var(--gold);
                  font-size: .72rem;
                  font-weight: 800;
                  overflow: hidden;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                }
                .column-bar-area {
                  display: flex;
                  align-items: end;
                  justify-content: center;
                }
                .column-fill {
                  width: min(52px, 78%);
                  min-height: 2px;
                  border-radius: 9px 9px 3px 3px;
                  background: linear-gradient(180deg, var(--gold), var(--green));
                  box-shadow: 0 8px 22px rgba(73,209,125,.16);
                }
                .column-label {
                  padding-top: 9px;
                  overflow: hidden;
                  text-overflow: ellipsis;
                  overflow-wrap: anywhere;
                  font-weight: 700;
                  font-size: .78rem;
                  line-height: 1.2;
                }
                .donut-wrap {
                  display: grid;
                  grid-template-columns: minmax(220px, 320px);
                  gap: 14px;
                  justify-content: center;
                  justify-items: center;
                  width: min(100%, 560px);
                  margin: 0 auto;
                }
                .donut-wrap > div:last-child { width: 100%; }
                .donut {
                  width: 160px;
                  height: 160px;
                  border-radius: 50%;
                  position: relative;
                  box-shadow: inset 0 0 0 1px var(--line);
                }
                .donut::after {
                  content: "";
                  position: absolute;
                  inset: 42px;
                  border-radius: 50%;
                  background: var(--panel);
                  border: 1px solid var(--line);
                }
                .legend-row {
                  display: grid;
                  grid-template-columns: 14px minmax(0, 1fr) auto;
                  gap: 10px;
                  align-items: center;
                  margin: 8px 0;
                }
                .legend-row span:nth-child(2) {
                  min-width: 0;
                  overflow: hidden;
                  text-overflow: ellipsis;
                }
                .legend-color {
                  width: 14px;
                  height: 14px;
                  border-radius: 4px;
                }
                .insight-grid {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                  gap: 12px;
                  margin-bottom: 16px;
                }
                .insight-card {
                  padding: 14px;
                  border: 1px solid var(--line);
                  border-radius: 16px;
                  background: rgba(255,255,255,.035);
                }
                .insight-card span {
                  display: block;
                  color: var(--muted);
                  font-size: .76rem;
                  letter-spacing: .08em;
                  text-transform: uppercase;
                }
                .insight-card strong {
                  display: block;
                  margin-top: 7px;
                  font-size: 1.35rem;
                }
                .line-svg {
                  width: 100%;
                  height: 220px;
                  display: block;
                  border: 1px solid var(--line);
                  border-radius: 16px;
                  background:
                    linear-gradient(rgba(139,148,158,.08) 1px, transparent 1px),
                    linear-gradient(90deg, rgba(139,148,158,.08) 1px, transparent 1px),
                    rgba(255,255,255,.025);
                  background-size: 100% 25%, 12.5% 100%;
                }
                .heatmap {
                  display: grid;
                  gap: 8px;
                  overflow-x: auto;
                  padding-bottom: 4px;
                }
                .heat-row {
                  display: grid;
                  grid-template-columns: minmax(120px, 180px) 1fr minmax(80px, auto);
                  gap: 10px;
                  align-items: center;
                }
                .heat-header {
                  color: var(--muted);
                  font-size: .78rem;
                  text-transform: uppercase;
                  letter-spacing: .08em;
                }
                .heat-cells {
                  display: grid;
                  grid-template-columns: repeat(24, minmax(5px, 1fr));
                  gap: 3px;
                }
                .heat-cell {
                  height: 16px;
                  border-radius: 4px;
                  background: rgba(139,148,158,.14);
                }
                .heat-total {
                  color: var(--gold);
                  font-weight: 800;
                  text-align: right;
                  white-space: nowrap;
                }
                .activity-explainer {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                  gap: 10px;
                  margin-bottom: 14px;
                }
                .activity-pill {
                  padding: 10px 12px;
                  border: 1px solid var(--line);
                  border-radius: 14px;
                  background: rgba(255,255,255,.035);
                }
                .activity-pill span {
                  display: block;
                  color: var(--muted);
                  font-size: .75rem;
                  letter-spacing: .08em;
                  text-transform: uppercase;
                }
                .activity-pill strong {
                  display: block;
                  margin-top: 4px;
                }
                .activity-scale {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 10px;
                  align-items: center;
                  margin: 0 0 14px;
                  color: var(--muted);
                }
                .scale-swatch {
                  display: inline-block;
                  width: 32px;
                  height: 12px;
                  border-radius: 999px;
                  background: rgba(73, 209, 125, var(--alpha));
                  border: 1px solid rgba(73, 209, 125, .2);
                }
                .chart-note {
                  margin: 0 0 14px;
                  color: var(--muted);
                  line-height: 1.5;
                }
                .page-tools {
                  display: flex;
                  justify-content: space-between;
                  gap: 14px;
                  align-items: center;
                  flex-wrap: wrap;
                  margin-bottom: 18px;
                  padding: 14px 16px;
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 18px;
                  box-shadow: var(--shadow);
                }
                .player-search-tools {
                  margin-bottom: 18px;
                }
                .player-search-tools input {
                  width: 100%;
                }
                .player-browser {
                  display: grid;
                  grid-template-columns: minmax(240px, 360px) 1fr;
                  gap: 18px;
                  align-items: start;
                }
                .player-list, .player-detail {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 22px;
                  box-shadow: var(--shadow);
                  overflow: hidden;
                }
                .player-list {
                  position: sticky;
                  top: 92px;
                  max-height: calc(100vh - 112px);
                  overflow-y: auto;
                }
                .player-list-head {
                  padding: 16px 18px;
                  border-bottom: 1px solid var(--line);
                  color: var(--muted);
                  font-size: .82rem;
                  text-transform: uppercase;
                  letter-spacing: .1em;
                }
                .player-button {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 12px;
                  width: 100%;
                  padding: 14px 18px;
                  border: 0;
                  border-bottom: 1px solid rgba(139,148,158,.14);
                  border-radius: 0;
                  background: transparent;
                  text-align: left;
                  font-weight: 700;
                }
                .player-button:hover, .player-button.active {
                  background: rgba(73, 209, 125, .08);
                }
                .player-meta {
                  color: var(--muted);
                  font-size: .78rem;
                  font-weight: 500;
                  margin-top: 4px;
                }
                .player {
                  overflow: hidden;
                }
                .player-head {
                  display: flex;
                  justify-content: space-between;
                  gap: 16px;
                  padding: 20px;
                  border-bottom: 1px solid var(--line);
                  background: linear-gradient(90deg, rgba(73,209,125,.08), rgba(240,180,76,.04));
                }
                .player h2 {
                  margin: 0;
                  font-size: 1.3rem;
                }
                .uuid {
                  margin-top: 4px;
                  color: var(--muted);
                  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                  font-size: .82rem;
                }
                .badge {
                  align-self: start;
                  padding: 6px 10px;
                  border-radius: 999px;
                  color: #06210f;
                  background: var(--green);
                  font-weight: 800;
                  font-size: .78rem;
                  text-transform: uppercase;
                }
                .badge.offline {
                  color: #f1f5f9;
                  background: var(--stone);
                }
                .featured {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
                  gap: 10px;
                  padding: 16px 20px;
                  border-bottom: 1px solid var(--line);
                }
                .chip {
                  background: rgba(255,255,255,.04);
                  border: 1px solid var(--line);
                  border-radius: 16px;
                  padding: 12px;
                }
                .chip span {
                  color: var(--muted);
                  display: block;
                  font-size: .78rem;
                }
                .chip strong {
                  display: block;
                  margin-top: 5px;
                }
                table {
                  width: 100%;
                  border-collapse: collapse;
                }
                th, td {
                  padding: 11px 20px;
                  text-align: left;
                  border-bottom: 1px solid rgba(139,148,158,.14);
                }
                th {
                  color: var(--muted);
                  font-size: .75rem;
                  letter-spacing: .1em;
                  text-transform: uppercase;
                  background: rgba(0,0,0,.14);
                }
                th button {
                  width: 100%;
                  padding: 0;
                  border: 0;
                  border-radius: 0;
                  background: transparent;
                  color: inherit;
                  text-align: left;
                  font: inherit;
                  letter-spacing: inherit;
                  text-transform: inherit;
                }
                th button::after { content: ' ↕'; opacity: .45; }
                th button[data-direction="asc"]::after { content: ' ↑'; opacity: 1; }
                th button[data-direction="desc"]::after { content: ' ↓'; opacity: 1; }
                td.value {
                  font-weight: 800;
                  color: var(--gold);
                  white-space: nowrap;
                }
                .empty, .error {
                  padding: 26px;
                  text-align: center;
                  color: var(--muted);
                }
                .error {
                  color: var(--red);
                }
                @media (max-width: 760px) {
                  .player-browser, .charts { grid-template-columns: 1fr; }
                  .summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                  .donut-wrap { grid-template-columns: 1fr; justify-items: center; }
                  .player-head { flex-direction: column; }
                  .player-list { position: static; max-height: 45vh; }
                  th:nth-child(1), td:nth-child(1) { display: none; }
                }
                @media (max-width: 520px) {
                  .toolbar-group { flex-direction: column; }
                  .toolbar-group button, .visibility-select { width: 100%; }
                  .column-chart { grid-template-columns: 1fr; }
                  .column-y-axis { display: none; }
                }
              </style>
            </head>
            <body>
              <header>
                <div class="brand">
                  <div class="cube" aria-hidden="true"></div>
                  <div>
                    <h1>Stats4Us</h1>
                    <p class="subtitle">Minecraft server statistics, in one place</p>
                  </div>
                </div>
              </header>
              <main>
                <section class="toolbar">
                  <div class="toolbar-group">
                    <select id="category" aria-label="Filter by category"><option value="">All categories</option></select>
                    <button id="refresh" type="button">Refresh</button>
                  </div>
                </section>
                <nav class="tabs" role="tablist" aria-label="Dashboard sections">
                  <button class="tab active" role="tab" aria-selected="true" aria-controls="playersView" tabindex="0" data-route="players">Players</button>
                  <button class="tab" role="tab" aria-selected="false" aria-controls="leaderboardsView" tabindex="-1" data-route="leaderboards">Leaderboards</button>
                  <button class="tab" role="tab" aria-selected="false" aria-controls="chartsView" tabindex="-1" data-route="charts">Graphs & Charts</button>
                  <button class="tab" role="tab" aria-selected="false" aria-controls="allStatsView" tabindex="-1" data-route="stats">All Stats</button>
                </nav>
                <section class="summary" id="summary" aria-live="polite"></section>
                <section class="view active" id="playersView" role="tabpanel">
                  <section class="page-tools player-search-tools">
                    <input id="playerSearch" aria-label="Search players" placeholder="Search players..." autocomplete="off">
                  </section>
                  <section class="player-browser">
                    <aside class="player-list" id="playerList"><div class="empty">Loading players...</div></aside>
                    <section class="player-detail" id="playerDetail"><div class="empty">Select a player to view their statistics.</div></section>
                  </section>
                </section>
                <section class="view" id="leaderboardsView" role="tabpanel">
                  <section class="leaderboard-tools">
                    <div>
                      <strong>Leaderboard filters</strong>
                      <div class="player-meta">Choose which statistics are available in each leaderboard.</div>
                    </div>
                    <select class="visibility-select" id="leaderboardVisibility" aria-label="Leaderboard statistic visibility">
                      <option value="all">Show all stats</option>
                      <option value="nonzero" selected>Hide zero-only stats</option>
                      <option value="multi">Require 2+ players</option>
                    </select>
                  </section>
                  <section class="leaderboards" id="leaderboards"></section>
                </section>
                <section class="view" id="chartsView" role="tabpanel">
                  <section class="page-tools">
                    <div>
                      <strong>Chart filters</strong>
                      <div class="player-meta">Choose which statistics are available to graph.</div>
                    </div>
                    <select class="visibility-select" id="chartVisibility" aria-label="Chart statistic visibility">
                      <option value="all">Show all stats</option>
                      <option value="nonzero" selected>Hide zero-only stats</option>
                      <option value="multi">Require 2+ players</option>
                    </select>
                  </section>
                  <section class="charts" id="charts"></section>
                </section>
                <section class="view" id="allStatsView" role="tabpanel">
                  <section class="page-tools">
                    <div>
                      <strong>All stats filters</strong>
                      <div class="player-meta">Hide statistics where every player is currently at zero.</div>
                    </div>
                    <button class="toggle active" id="hideZeroStats" type="button" aria-pressed="true">Show zero stats</button>
                  </section>
                  <section class="page-tools player-search-tools">
                    <input id="allStatsSearch" aria-label="Search all statistics" placeholder="Search category, name, key, type, or value..." autocomplete="off">
                  </section>
                  <section class="stat-grid" id="allStats"></section>
                </section>
              </main>
              <script>
                let snapshot = null;
                let selectedPlayerId = null;
                let leaderboardVisibility = 'nonzero';
                let chartVisibility = 'nonzero';
                let hideZeroStats = true;
                let allStatsSearch = '';
                let selectedTrendStatKey = null;
                let comparisonStatSearch = '';
                let comparisonMode = 'column';
                let playerStatSearch = '';
                let loading = false;
                const selectedLeaderboardStats = {};
                const leaderboardSearches = {};
                const tableSorts = {};
                const playerSearch = document.querySelector('#playerSearch');
                const category = document.querySelector('#category');
                const playerList = document.querySelector('#playerList');
                const playerDetail = document.querySelector('#playerDetail');
                const leaderboards = document.querySelector('#leaderboards');
                const charts = document.querySelector('#charts');
                const allStats = document.querySelector('#allStats');
                const summary = document.querySelector('#summary');
                const leaderboardVisibilitySelect = document.querySelector('#leaderboardVisibility');
                const chartVisibilitySelect = document.querySelector('#chartVisibility');
                const hideZeroStatsButton = document.querySelector('#hideZeroStats');
                const allStatsSearchInput = document.querySelector('#allStatsSearch');
                const refreshButton = document.querySelector('#refresh');
                const tabs = [...document.querySelectorAll('.tab')];
                const routes = {
                  players: { view: 'playersView', title: 'Players' },
                  leaderboards: { view: 'leaderboardsView', title: 'Leaderboards' },
                  charts: { view: 'chartsView', title: 'Graphs & Charts' },
                  stats: { view: 'allStatsView', title: 'All Stats' }
                };
                refreshButton.addEventListener('click', load);
                playerSearch.addEventListener('input', render);
                category.addEventListener('change', render);
                leaderboardVisibilitySelect.addEventListener('change', event => {
                  leaderboardVisibility = event.target.value;
                  render();
                });
                chartVisibilitySelect.addEventListener('change', event => {
                  chartVisibility = event.target.value;
                  render();
                });
                allStatsSearchInput.addEventListener('input', event => {
                  allStatsSearch = event.target.value;
                  render();
                });
                hideZeroStatsButton.addEventListener('click', () => {
                  hideZeroStats = !hideZeroStats;
                  hideZeroStatsButton.classList.toggle('active', hideZeroStats);
                  hideZeroStatsButton.setAttribute('aria-pressed', hideZeroStats);
                  hideZeroStatsButton.textContent = hideZeroStats ? 'Show zero stats' : 'Hide zero stats';
                  render();
                });
                tabs.forEach((tab, index) => {
                  tab.addEventListener('click', () => switchTab(tab.dataset.route, selectedPlayerId, true));
                  tab.addEventListener('keydown', event => {
                    let nextIndex = index;
                    if (event.key === 'ArrowLeft') nextIndex = (index - 1 + tabs.length) % tabs.length;
                    else if (event.key === 'ArrowRight') nextIndex = (index + 1) % tabs.length;
                    else if (event.key === 'Home') nextIndex = 0;
                    else if (event.key === 'End') nextIndex = tabs.length - 1;
                    else return;
                    event.preventDefault();
                    tabs[nextIndex].focus();
                    switchTab(tabs[nextIndex].dataset.route, selectedPlayerId, true);
                  });
                });
                window.addEventListener('hashchange', routeFromHash);

                function routeHash(route, playerId) {
                  return `#${route}${route === 'players' && playerId ? `/${encodeURIComponent(playerId)}` : ''}`;
                }

                function routeFromHash() {
                  const [route = 'players', encodedPlayerId] = location.hash.slice(1).split('/', 2);
                  let playerId = null;
                  try {
                    playerId = encodedPlayerId ? decodeURIComponent(encodedPlayerId) : null;
                  } catch {}
                  switchTab(route || 'players', playerId);
                }

                function switchTab(route, playerId, navigate = false) {
                  route = routes[route] ? route : 'players';
                  if (route === 'players') {
                    selectedPlayerId = snapshot
                      ? snapshot.players.find(player => player.uuid === playerId)?.uuid ?? snapshot.players[0]?.uuid ?? null
                      : playerId;
                  }

                  const hash = routeHash(route, selectedPlayerId);
                  if (navigate && location.hash !== hash) {
                    location.hash = hash;
                    return;
                  }

                  tabs.forEach(tab => {
                    const active = tab.dataset.route === route;
                    tab.classList.toggle('active', active);
                    tab.setAttribute('aria-selected', String(active));
                    tab.tabIndex = active ? 0 : -1;
                  });
                  document.querySelectorAll('.view').forEach(view => view.classList.toggle('active', view.id === routes[route].view));
                  document.title = `${routes[route].title} · Stats4Us`;
                  if (location.hash !== hash) history.replaceState(null, '', hash);
                  render();
                }

                function esc(value) {
                  return String(value ?? '').replace(/[&<>"']/g, char => ({
                    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
                  }[char]));
                }

                async function load() {
                  if (loading) return;
                  loading = true;
                  refreshButton.disabled = true;
                  refreshButton.textContent = 'Refreshing…';
                  if (!snapshot) playerList.innerHTML = '<div class="empty">Loading players...</div>';
                  try {
                    const response = await fetch('/api/stats', { cache: 'no-store' });
                    if (!response.ok) throw new Error(await response.text());
                    snapshot = await response.json();
                    for (const player of snapshot.players) {
                      player.statRows = Object.fromEntries(player.stats.map(stat => [stat.key, stat]));
                    }
                    fillCategories();
                    routeFromHash();
                  } catch (error) {
                    if (!snapshot) playerList.innerHTML = '<div class="error">Unable to load statistics. Check the server log.</div>';
                    console.error(error);
                  } finally {
                    loading = false;
                    refreshButton.disabled = false;
                    refreshButton.textContent = 'Refresh';
                  }
                }

                function fillCategories() {
                  const selected = category.value;
                  const categories = new Set();
                  for (const stat of snapshot.catalog) categories.add(stat.category);
                  category.innerHTML = '<option value="">All categories</option>' +
                    [...categories].sort().map(value => `<option value="${esc(value)}">${esc(value)}</option>`).join('');
                  category.value = selected;
                }

                function renderSummary() {
                  summary.innerHTML = [
                    ['Players', snapshot.totalPlayers],
                    ['Online', snapshot.onlinePlayers],
                    ['Available stats', snapshot.totalAvailableStats],
                    ['Updated', new Date(snapshot.generatedAt).toLocaleString()]
                  ].map(([label, value]) => `<div class="metric"><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`).join('');
                }

                function render() {
                  if (!snapshot) return;
                  const selectedCategory = category.value;
                  const catalog = filteredCatalog(selectedCategory);
                  const filtered = snapshot.players.map(player => {
                    const stats = player.stats.filter(stat => !selectedCategory || stat.category === selectedCategory);
                    return { ...player, stats };
                  }).filter(player => player.stats.length || !selectedCategory);

                  renderSummary();
                  const activeView = document.querySelector('.view.active')?.id;
                  if (activeView === 'playersView') renderPlayers(filtered);
                  if (activeView === 'leaderboardsView') renderLeaderboards(catalog);
                  if (activeView === 'chartsView') renderCharts(catalog);
                  if (activeView === 'allStatsView') {
                    const visibleStats = hideZeroStats ? filterStatsByVisibility(catalog, 'nonzero') : catalog;
                    renderAllStats(filterStatsByText(visibleStats, allStatsSearch));
                  }
                }

                function filteredCatalog(selectedCategory) {
                  return snapshot.catalog.filter(stat => {
                    return !selectedCategory || stat.category === selectedCategory;
                  });
                }
 
                function filterStatsByText(stats, query) {
                  const trimmed = String(query ?? '').trim().toLowerCase();
                  if (!trimmed) return stats;
                  return stats.filter(stat => `${stat.category} ${stat.name} ${stat.key} ${stat.type} ${stat.value}`.toLowerCase().includes(trimmed));
                }

                function filterStatsByVisibility(stats, mode) {
                  if (mode === 'all') return stats;
                  const requiredPlayers = mode === 'multi' ? 2 : 1;
                  return stats.filter(stat => playersAboveZero(stat.key) >= requiredPlayers);
                }

                function renderPlayers(filtered) {
                  const playerQuery = playerSearch.value.trim().toLowerCase();
                  const visiblePlayers = playerQuery
                    ? filtered.filter(player => `${player.name} ${player.uuid}`.toLowerCase().includes(playerQuery))
                    : filtered;

                  if (!visiblePlayers.length) {
                    playerList.innerHTML = '<div class="empty">No players match your filters.</div>';
                    playerDetail.innerHTML = '<div class="empty">Select a player to view their statistics.</div>';
                    return;
                  }

                  if (!visiblePlayers.some(player => player.uuid === selectedPlayerId)) {
                    selectedPlayerId = visiblePlayers[0].uuid;
                    history.replaceState(null, '', routeHash('players', selectedPlayerId));
                  }

                  playerList.innerHTML = `
                    <div class="player-list-head">${visiblePlayers.length} Players</div>
                    ${visiblePlayers.map(player => `
                      <button class="player-button ${player.uuid === selectedPlayerId ? 'active' : ''}" data-player="${esc(player.uuid)}" data-name="${esc(String(player.name ?? '').toLowerCase())}">
                        <span>
                          <span>${esc(player.name)}</span>
                          <span class="player-meta">${esc(player.stats.length)} shown stats</span>
                        </span>
                        <span class="badge ${player.online ? '' : 'offline'}">${player.online ? 'Online' : 'Offline'}</span>
                      </button>
                    `).join('')}
                  `;

                  document.querySelectorAll('.player-button').forEach(button => {
                    button.addEventListener('click', () => switchTab('players', button.dataset.player, true));
                  });

                  const player = visiblePlayers.find(item => item.uuid === selectedPlayerId) ?? visiblePlayers[0];
                  const visibleStats = filterStatsByText(player.stats, playerStatSearch);
                  const playerRows = sortedRows(visibleStats, 'player-detail', {
                    category: stat => stat.category,
                    statistic: stat => stat.name,
                    value: stat => Number(stat.raw)
                  });
                  playerDetail.innerHTML = `
                    <article class="player">
                      <div class="player-head">
                        <div>
                          <h2>${esc(player.name)}</h2>
                          <div class="uuid">${esc(player.uuid)}</div>
                        </div>
                        <div class="badge ${player.online ? '' : 'offline'}">${player.online ? 'Online' : 'Offline'}</div>
                      </div>
                      <div class="featured">
                        ${player.featured.map(stat => `<div class="chip"><span>${esc(stat.name)}</span><strong>${esc(stat.formatted)}</strong></div>`).join('')}
                      </div>
                      <div class="page-tools"><input id="playerStatSearch" aria-label="Search statistic" placeholder="Search statistic..." value="${esc(playerStatSearch)}" autocomplete="off"></div>
                      ${playerRows.length ? `<table>
                        <thead><tr>${sortHeader('player-detail', 'category', 'Category')}${sortHeader('player-detail', 'statistic', 'Statistic')}${sortHeader('player-detail', 'value', 'Value')}</tr></thead>
                        <tbody>
                          ${playerRows.map(stat => `<tr><td>${esc(stat.category)}</td><td>${esc(stat.name)}</td><td class="value">${esc(stat.formatted)}</td></tr>`).join('')}
                        </tbody>
                      </table>` : '<div class="empty">No statistics match the current filters.</div>'}
                    </article>
                  `;
                  document.querySelector('#playerStatSearch').addEventListener('input', event => {
                    const cursor = event.target.selectionStart;
                    playerStatSearch = event.target.value;
                    render();
                    const nextSearch = document.querySelector('#playerStatSearch');
                    nextSearch?.focus();
                    nextSearch?.setSelectionRange(cursor, cursor);
                  });
                  bindTableSorts();
                }

                function renderLeaderboards(catalog) {
                  catalog = filterStatsByVisibility(catalog, leaderboardVisibility);

                  if (!catalog.length) {
                    leaderboards.innerHTML = '<div class="empty">No leaderboard sections match your filters.</div>';
                    return;
                  }

                  const byCategory = groupBy(catalog, stat => stat.category);
                  leaderboards.innerHTML = Object.entries(byCategory).map(([categoryName, stats]) => {
                    const categorySearch = leaderboardSearches[categoryName] ?? '';
                    const visibleStats = filterStatsByText(stats, categorySearch);
                    if (!visibleStats.length) {
                      return `
                        <article class="board">
                          <div class="board-head">
                            <h2>${esc(categoryName)}</h2>
                            <input class="leaderboard-search" data-category="${esc(categoryName)}" placeholder="Search ${esc(categoryName)} stats..." value="${esc(categorySearch)}">
                          </div>
                          <div class="empty">No stats in this section match your search.</div>
                        </article>
                      `;
                    }
                    const selectedKey = visibleStats.some(stat => stat.key === selectedLeaderboardStats[categoryName])
                      ? selectedLeaderboardStats[categoryName]
                      : visibleStats[0].key;
                    selectedLeaderboardStats[categoryName] = selectedKey;
                    const selectedStat = visibleStats.find(stat => stat.key === selectedKey);
                    return `
                      <article class="board">
                        <div class="board-head">
                          <h2>${esc(categoryName)}</h2>
                          <input class="leaderboard-search" data-category="${esc(categoryName)}" placeholder="Search ${esc(categoryName)} stats..." value="${esc(categorySearch)}">
                          <select class="leaderboard-stat" data-category="${esc(categoryName)}">
                            ${visibleStats.map(stat => `<option value="${esc(stat.key)}" ${stat.key === selectedKey ? 'selected' : ''}>${esc(stat.name)}</option>`).join('')}
                          </select>
                        </div>
                        ${leaderboardTable(selectedStat)}
                      </article>
                    `;
                  }).join('');

                  document.querySelectorAll('.leaderboard-stat').forEach(select => {
                    select.addEventListener('change', () => {
                      selectedLeaderboardStats[select.dataset.category] = select.value;
                      render();
                    });
                  });
                  document.querySelectorAll('.leaderboard-search').forEach(input => {
                    input.addEventListener('input', event => {
                      const categoryName = input.dataset.category;
                      const cursor = event.target.selectionStart;
                      leaderboardSearches[input.dataset.category] = input.value;
                      render();
                      const nextInput = [...document.querySelectorAll('.leaderboard-search')]
                        .find(item => item.dataset.category === categoryName);
                      if (nextInput) {
                        nextInput.focus();
                        nextInput.setSelectionRange(cursor, cursor);
                      }
                    });
                  });
                  bindTableSorts();
                }

                function leaderboardTable(stat) {
                  const defaultRows = snapshot.players
                    .map(player => ({
                      player,
                      raw: Number(player.values?.[stat.key] ?? 0),
                      formatted: player.statRows?.[stat.key]?.formatted
                    }))
                    .sort((a, b) => b.raw - a.raw || String(a.player.name).localeCompare(String(b.player.name)))
                    .slice(0, 10)
                    .map((row, index) => ({ ...row, rank: index + 1 }));
                  const tableId = `leaderboard:${stat.key}`;
                  const rows = sortedRows(defaultRows, tableId, {
                    rank: row => row.rank,
                    player: row => row.player.name,
                    value: row => row.raw
                  });

                  return `
                    <table>
                      <thead><tr>${sortHeader(tableId, 'rank', '#')}${sortHeader(tableId, 'player', 'Player')}${sortHeader(tableId, 'value', 'Value')}</tr></thead>
                      <tbody>
                        ${rows.map(row => `<tr><td>${row.rank}</td><td>${esc(row.player.name)}</td><td class="value">${esc(row.formatted ?? row.raw)}</td></tr>`).join('')}
                      </tbody>
                    </table>
                  `;
                }

                function playersAboveZero(statKey) {
                  return snapshot.players.reduce((count, player) => count + (Number(player.values?.[statKey] ?? 0) > 0 ? 1 : 0), 0);
                }

                function renderCharts(catalog) {
                  const chartCatalog = filterStatsByVisibility(catalog, chartVisibility);
                  const comparisonCatalog = filterStatsByText(chartCatalog, comparisonStatSearch);
                  if (!selectedTrendStatKey || !comparisonCatalog.some(stat => stat.key === selectedTrendStatKey)) {
                    selectedTrendStatKey = comparisonCatalog[0]?.key ?? null;
                  }

                  const selectedComparisonStat = comparisonCatalog.find(stat => stat.key === selectedTrendStatKey);
                  charts.innerHTML = `
                    <article class="chart-panel wide">
                      <h2>Player comparison</h2>
                      <div class="chart-subtitle">${selectedComparisonStat ? `${esc(selectedComparisonStat.category)} — ${esc(selectedComparisonStat.name)}` : 'Search for a statistic to compare players.'}</div>
                      <div class="chart-controls">
                        <input id="comparisonStatSearch" placeholder="Search stats or categories, e.g. blocks mined, general" value="${esc(comparisonStatSearch)}">
                        <select id="trendStat">
                          ${comparisonCatalog.map(stat => `<option value="${esc(stat.key)}" ${stat.key === selectedTrendStatKey ? 'selected' : ''}>${esc(stat.category)} — ${esc(stat.name)}</option>`).join('')}
                        </select>
                        <select id="comparisonMode">
                          <option value="column" ${comparisonMode === 'column' ? 'selected' : ''}>Column chart</option>
                          <option value="pie" ${comparisonMode === 'pie' ? 'selected' : ''}>Pie chart</option>
                          <option value="table" ${comparisonMode === 'table' ? 'selected' : ''}>Table</option>
                        </select>
                      </div>
                      ${selectedComparisonStat ? comparisonDisplay(playersForStat(selectedComparisonStat.key), comparisonMode) : '<div class="empty">No statistic matches this comparison search.</div>'}
                    </article>
                  `;

                  const trendSelect = document.querySelector('#trendStat');
                  if (trendSelect) {
                    trendSelect.addEventListener('change', event => {
                      selectedTrendStatKey = event.target.value;
                      render();
                    });
                  }
                  const comparisonSearch = document.querySelector('#comparisonStatSearch');
                  if (comparisonSearch) {
                    comparisonSearch.addEventListener('input', event => {
                      const cursor = event.target.selectionStart;
                      comparisonStatSearch = event.target.value;
                      render();
                      const nextSearch = document.querySelector('#comparisonStatSearch');
                      if (nextSearch) {
                        nextSearch.focus();
                        nextSearch.setSelectionRange(cursor, cursor);
                      }
                    });
                  }
                  const modeSelect = document.querySelector('#comparisonMode');
                  if (modeSelect) {
                    modeSelect.addEventListener('change', event => {
                      comparisonMode = event.target.value;
                      render();
                    });
                  }
                  bindTableSorts();
                }

                function playersForStat(statKey) {
                  return snapshot.players
                    .map(player => ({
                      player,
                      raw: Number(player.values?.[statKey] ?? 0),
                      formatted: player.statRows?.[statKey]?.formatted
                    }))
                    .sort((a, b) => b.raw - a.raw || String(a.player.name).localeCompare(String(b.player.name)));
                }

                function columnChart(rows, labelFn, valueFn, displayFn) {
                  if (!rows.length) {
                    return '<div class="empty">No rows to display.</div>';
                  }
                  const max = Math.max(1, ...rows.map(valueFn));
                  const ticks = Array.from({ length: 5 }, (_, index) => max * (4 - index) / 4);
                  return `
                    <div class="column-chart">
                      <div class="column-y-axis">${ticks.map(value => `<span>${esc(formatAxisValue(value))}</span>`).join('')}</div>
                      <div class="column-scroll">
                        <div class="column-plot" style="grid-template-columns:repeat(${rows.length},minmax(64px,1fr));">
                          ${rows.map(row => {
                            const value = valueFn(row);
                            const height = value <= 0 ? 0 : Math.max(2, value / max * 100);
                            return `
                              <div class="column-item">
                                <div class="column-value" title="${esc(displayFn(row))}">${esc(displayFn(row))}</div>
                                <div class="column-bar-area"><div class="column-fill" style="height:${height}%"></div></div>
                                <div class="column-label" title="${esc(labelFn(row))}">${esc(labelFn(row))}</div>
                              </div>
                            `;
                          }).join('')}
                        </div>
                      </div>
                    </div>
                  `;
                }

                function formatAxisValue(value) {
                  const absolute = Math.abs(value);
                  if (absolute >= 1000000000) return `${(value / 1000000000).toFixed(1).replace('.0', '')}B`;
                  if (absolute >= 1000000) return `${(value / 1000000).toFixed(1).replace('.0', '')}M`;
                  if (absolute >= 1000) return `${(value / 1000).toFixed(1).replace('.0', '')}K`;
                  return Math.round(value).toLocaleString();
                }

                function comparisonDisplay(rows, mode) {
                  const nonZeroRows = rows.filter(row => row.raw > 0);
                  const displayRows = (nonZeroRows.length ? nonZeroRows : rows).slice(0, 12)
                    .map((row, index) => ({ ...row, rank: index + 1 }));
                  if (mode === 'pie') {
                    return donutChartRows(displayRows.slice(0, 9), row => row.player.name, row => row.raw, row => row.formatted ?? row.raw);
                  }
                  if (mode === 'table') {
                    return comparisonTable(displayRows);
                  }
                  return columnChart(displayRows, row => row.player.name, row => row.raw, row => row.formatted ?? row.raw);
                }

                function comparisonTable(rows) {
                  if (!rows.length) {
                    return '<div class="empty">No players to display.</div>';
                  }
                  const tableId = 'comparison';
                  rows = sortedRows(rows, tableId, {
                    rank: row => row.rank,
                    player: row => row.player.name,
                    value: row => row.raw
                  });

                  return `
                    <table>
                      <thead><tr>${sortHeader(tableId, 'rank', '#')}${sortHeader(tableId, 'player', 'Player')}${sortHeader(tableId, 'value', 'Value')}</tr></thead>
                      <tbody>
                        ${rows.map(row => `<tr><td>${row.rank}</td><td>${esc(row.player.name)}</td><td class="value">${esc(row.formatted ?? row.raw)}</td></tr>`).join('')}
                      </tbody>
                    </table>
                  `;
                }

                function sortedRows(rows, tableId, accessors) {
                  const sort = tableSorts[tableId];
                  if (!sort) return rows;
                  const accessor = accessors[sort.key];
                  return [...rows].sort((left, right) => {
                    const a = accessor(left);
                    const b = accessor(right);
                    const result = typeof a === 'number' && typeof b === 'number'
                      ? a - b
                      : String(a ?? '').localeCompare(String(b ?? ''), undefined, { sensitivity: 'base', numeric: true });
                    return result * (sort.direction === 'asc' ? 1 : -1);
                  });
                }

                function sortHeader(tableId, key, label) {
                  const direction = tableSorts[tableId]?.key === key ? tableSorts[tableId].direction : '';
                  return `<th><button type="button" class="sort-button" data-table="${esc(tableId)}" data-key="${esc(key)}" data-direction="${direction}" aria-label="Sort by ${esc(label)}">${esc(label)}</button></th>`;
                }

                function bindTableSorts() {
                  document.querySelectorAll('.sort-button').forEach(button => {
                    button.addEventListener('click', () => {
                      const current = tableSorts[button.dataset.table];
                      tableSorts[button.dataset.table] = {
                        key: button.dataset.key,
                        direction: current?.key === button.dataset.key && current.direction === 'asc' ? 'desc' : 'asc'
                      };
                      render();
                    });
                  });
                }

                function donutChartRows(rows, labelFn, valueFn, displayFn) {
                  const colors = ['#49d17d', '#f0b44c', '#4dabf7', '#d084ff', '#ff6b6b', '#7dd3fc', '#a3e635', '#fb7185', '#c084fc'];
                  const nonZeroRows = rows.filter(row => valueFn(row) > 0);
                  const total = nonZeroRows.reduce((sum, row) => sum + valueFn(row), 0);
                  if (total <= 0) {
                    return '<div class="empty">No non-zero player data to graph.</div>';
                  }

                  let current = 0;
                  const gradient = nonZeroRows.map((row, index) => {
                    const start = current / total * 360;
                    current += valueFn(row);
                    const end = current / total * 360;
                    return `${colors[index % colors.length]} ${start}deg ${end}deg`;
                  }).join(', ');

                  return `
                    <div class="donut-wrap">
                      <div class="donut" style="background: conic-gradient(${gradient})"></div>
                      <div>
                        ${nonZeroRows.slice(0, 9).map((row, index) => `
                          <div class="legend-row">
                            <span class="legend-color" style="background:${colors[index % colors.length]}"></span>
                            <span title="${esc(labelFn(row))}">${esc(labelFn(row))}</span>
                            <strong title="${esc(displayFn(row))}">${esc(Math.round(valueFn(row) / total * 100))}%</strong>
                          </div>
                        `).join('')}
                      </div>
                    </div>
                  `;
                }

                function renderAllStats(catalog) {
                  if (!catalog.length) {
                    allStats.innerHTML = '<div class="empty">No stats match your filters.</div>';
                    return;
                  }

                  allStats.innerHTML = catalog.map(stat => {
                    const top = snapshot.players
                      .map(player => ({ player, raw: Number(player.values?.[stat.key] ?? 0), formatted: player.statRows?.[stat.key]?.formatted }))
                      .sort((a, b) => b.raw - a.raw || String(a.player.name).localeCompare(String(b.player.name)))[0];
                    return `
                      <article class="stat-card">
                        <h3>${esc(stat.name)}</h3>
                        <div>${esc(stat.category)}</div>
                        <p>${esc(stat.key)}</p>
                        <p>Top: <span class="top-player">${esc(top?.player?.name ?? 'Nobody')}</span> — ${esc(top?.formatted ?? top?.raw ?? 0)}</p>
                      </article>
                    `;
                  }).join('');
                }

                function groupBy(values, keyFn) {
                  return values.reduce((groups, value) => {
                    const key = keyFn(value);
                    (groups[key] ||= []).push(value);
                    return groups;
                  }, {});
                }

                routeFromHash();
                load();
                setInterval(load, 30000);
              </script>
            </body>
            </html>
            """;
    }
}
