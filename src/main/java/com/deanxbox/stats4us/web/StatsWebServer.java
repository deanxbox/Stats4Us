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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
            httpServer.createContext("/api/stats", this::handleStats);
            executor = Executors.newCachedThreadPool(runnable -> {
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
        if ("0.0.0.0".equals(config.web.bindAddress) || "::".equals(config.web.bindAddress)) {
            return "http://" + detectedHostAddress() + ":" + config.web.port + "/";
        }

        return "http://" + config.web.bindAddress + ":" + config.web.port + "/";
    }

    private String detectedHostAddress() {
        String publicAddress = detectedPublicAddress();
        if (publicAddress != null) {
            return publicAddress;
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }

            InetAddress localHost = InetAddress.getLocalHost();
            if (localHost != null && !localHost.isLoopbackAddress()) {
                return localHost.getHostAddress();
            }
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.debug("Failed to detect local Stats4Us dashboard address.", exception);
        }

        return "public-ip-unavailable";
    }

    private String detectedPublicAddress() {
        List<String> endpoints = List.of(
            "https://checkip.amazonaws.com/",
            "https://api.ipify.org/"
        );
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        for (String endpoint : endpoints) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
                String body = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body().trim();
                if (body.matches("\\d{1,3}(\\.\\d{1,3}){3}") || body.matches("[0-9a-fA-F:]{2,}")) {
                    return body;
                }
            } catch (Exception exception) {
                Stats4UsMod.LOGGER.debug("Failed to detect public Stats4Us dashboard address from {}.", endpoint, exception);
            }
        }

        Stats4UsMod.LOGGER.warn("Stats4Us could not reach public IP detection APIs; falling back to a local dashboard address.");
        return null;
    }

    private void handleIndex(final HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }

        send(exchange, 200, "text/html", indexHtml());
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
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
                  display: grid;
                  grid-template-columns: minmax(240px, 1fr) minmax(220px, 320px) auto;
                  gap: 14px;
                  padding: 16px;
                  position: sticky;
                  top: 12px;
                  z-index: 5;
                }
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
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(460px, 1fr));
                  gap: 18px;
                  align-items: start;
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
                .chart-panel.compact {
                  min-height: 0;
                }
                .bar-row {
                  display: grid;
                  grid-template-columns: minmax(120px, 180px) minmax(90px, 1fr) minmax(72px, auto);
                  align-items: center;
                  gap: 12px;
                  margin: 10px 0;
                }
                .bar-label {
                  overflow: hidden;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                  font-weight: 700;
                }
                .bar-track {
                  height: 13px;
                  overflow: hidden;
                  border-radius: 999px;
                  background: rgba(139,148,158,.18);
                }
                .bar-fill {
                  height: 100%;
                  min-width: 2px;
                  border-radius: inherit;
                  background: linear-gradient(90deg, var(--green), var(--gold));
                }
                .bar-value {
                  color: var(--gold);
                  font-weight: 800;
                  text-align: right;
                  white-space: nowrap;
                  overflow-wrap: anywhere;
                }
                .chart-panel.compact .bar-label {
                  white-space: normal;
                }
                .category-total-row {
                  display: grid;
                  gap: 7px;
                  margin: 13px 0;
                }
                .category-total-head {
                  display: flex;
                  justify-content: space-between;
                  gap: 14px;
                  align-items: baseline;
                }
                .category-total-label {
                  min-width: 0;
                  overflow: hidden;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                  font-weight: 800;
                }
                .category-total-value {
                  color: var(--gold);
                  font-weight: 900;
                  white-space: nowrap;
                }
                .donut-wrap {
                  display: grid;
                  grid-template-columns: minmax(130px, 160px) minmax(0, 1fr);
                  gap: 18px;
                  align-items: center;
                }
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
                  .toolbar, .summary, .player-browser, .charts { grid-template-columns: 1fr; }
                  .donut-wrap { grid-template-columns: 1fr; justify-items: center; }
                  .player-head { flex-direction: column; }
                  th:nth-child(1), td:nth-child(1) { display: none; }
                }
              </style>
            </head>
            <body>
              <header>
                <div class="brand">
                  <div class="cube" aria-hidden="true"></div>
                  <div>
                    <h1>Stats4Us</h1>
                    <p class="subtitle">Professional Minecraft server statistics dashboard</p>
                  </div>
                </div>
              </header>
              <main>
                <section class="toolbar">
                  <input id="search" placeholder="Search players, categories, or stats..." autocomplete="off">
                  <select id="category"><option value="">All categories</option></select>
                  <button id="refresh">Refresh</button>
                </section>
                <nav class="tabs" aria-label="Dashboard sections">
                  <button class="tab active" data-view="playersView">Players</button>
                  <button class="tab" data-view="leaderboardsView">Leaderboards</button>
                  <button class="tab" data-view="chartsView">Graphs & Charts</button>
                  <button class="tab" data-view="allStatsView">All Stats</button>
                </nav>
                <section class="summary" id="summary"></section>
                <section class="view active" id="playersView">
                  <section class="page-tools player-search-tools">
                    <input id="playerSearch" placeholder="Search players..." autocomplete="off">
                  </section>
                  <section class="player-browser">
                    <aside class="player-list" id="playerList"><div class="empty">Loading players...</div></aside>
                    <section class="player-detail" id="playerDetail"><div class="empty">Select a player to view their statistics.</div></section>
                  </section>
                </section>
                <section class="view" id="leaderboardsView">
                  <section class="leaderboard-tools">
                    <div>
                      <strong>Leaderboard filters</strong>
                      <div class="player-meta">Hide statistics where every player is currently at zero.</div>
                    </div>
                    <button class="toggle active" id="hideZeroLeaderboards">Hide zero-only stats</button>
                  </section>
                  <section class="leaderboards" id="leaderboards"></section>
                </section>
                <section class="view" id="chartsView">
                  <section class="charts" id="charts"></section>
                </section>
                <section class="view" id="allStatsView">
                  <section class="page-tools">
                    <div>
                      <strong>All stats filters</strong>
                      <div class="player-meta">Hide statistics where every player is currently at zero.</div>
                    </div>
                    <button class="toggle active" id="hideZeroStats">Hide zero stats</button>
                  </section>
                  <section class="stat-grid" id="allStats"></section>
                </section>
              </main>
              <script>
                let snapshot = null;
                let selectedPlayerId = null;
                let hideZeroLeaderboards = true;
                let hideZeroStats = true;
                let selectedTrendStatKey = null;
                let comparisonStatSearch = '';
                let comparisonMode = 'bar';
                let selectedActivityDay = 'latest';
                const selectedLeaderboardStats = {};
                const leaderboardSearches = {};
                const search = document.querySelector('#search');
                const playerSearch = document.querySelector('#playerSearch');
                const category = document.querySelector('#category');
                const playerList = document.querySelector('#playerList');
                const playerDetail = document.querySelector('#playerDetail');
                const leaderboards = document.querySelector('#leaderboards');
                const charts = document.querySelector('#charts');
                const allStats = document.querySelector('#allStats');
                const summary = document.querySelector('#summary');
                const hideZeroLeaderboardsButton = document.querySelector('#hideZeroLeaderboards');
                const hideZeroStatsButton = document.querySelector('#hideZeroStats');
                document.querySelector('#refresh').addEventListener('click', load);
                search.addEventListener('input', render);
                playerSearch.addEventListener('input', render);
                category.addEventListener('change', render);
                hideZeroLeaderboardsButton.addEventListener('click', () => {
                  hideZeroLeaderboards = !hideZeroLeaderboards;
                  hideZeroLeaderboardsButton.classList.toggle('active', hideZeroLeaderboards);
                  hideZeroLeaderboardsButton.textContent = hideZeroLeaderboards ? 'Hide zero-only stats' : 'Show zero-only stats';
                  render();
                });
                hideZeroStatsButton.addEventListener('click', () => {
                  hideZeroStats = !hideZeroStats;
                  hideZeroStatsButton.classList.toggle('active', hideZeroStats);
                  hideZeroStatsButton.textContent = hideZeroStats ? 'Hide zero stats' : 'Show zero stats';
                  render();
                });
                document.querySelectorAll('.tab').forEach(tab => {
                  tab.addEventListener('click', () => {
                    document.querySelectorAll('.tab').forEach(item => item.classList.toggle('active', item === tab));
                    document.querySelectorAll('.view').forEach(view => view.classList.toggle('active', view.id === tab.dataset.view));
                  });
                });

                function esc(value) {
                  return String(value ?? '').replace(/[&<>"']/g, char => ({
                    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
                  }[char]));
                }

                async function load() {
                  playerList.innerHTML = '<div class="empty">Loading players...</div>';
                  try {
                    const response = await fetch('/api/stats', { cache: 'no-store' });
                    if (!response.ok) throw new Error(await response.text());
                    snapshot = await response.json();
                    for (const player of snapshot.players) {
                      player.statRows = Object.fromEntries(player.stats.map(stat => [stat.key, stat]));
                    }
                    if (!selectedPlayerId || !snapshot.players.some(player => player.uuid === selectedPlayerId)) {
                      selectedPlayerId = snapshot.players[0]?.uuid ?? null;
                    }
                    fillCategories();
                    render();
                  } catch (error) {
                    playerList.innerHTML = '<div class="error">Unable to load statistics. Check the server log.</div>';
                    console.error(error);
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

                function renderSummary(filteredPlayers) {
                  summary.innerHTML = [
                    ['Players', snapshot.totalPlayers],
                    ['Online', snapshot.onlinePlayers],
                    ['Available stats', snapshot.totalAvailableStats],
                    ['Updated', new Date(snapshot.generatedAt).toLocaleString()]
                  ].map(([label, value]) => `<div class="metric"><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`).join('');
                }

                function render() {
                  if (!snapshot) return;
                  const query = search.value.trim().toLowerCase();
                  const selectedCategory = category.value;
                  const catalog = filteredCatalog(query, selectedCategory);
                  const filtered = snapshot.players.map(player => {
                    const stats = player.stats.filter(stat => {
                      const matchesCategory = !selectedCategory || stat.category === selectedCategory;
                      const haystack = `${player.name} ${player.uuid} ${stat.category} ${stat.name} ${stat.key}`.toLowerCase();
                      return matchesCategory && (!query || haystack.includes(query));
                    });
                    return { ...player, stats };
                  }).filter(player => player.stats.length || !query && !selectedCategory);

                  renderSummary(filtered);
                  renderPlayers(filtered);
                  renderLeaderboards(catalog);
                  renderCharts(catalog);
                  renderAllStats(hideZeroStats ? catalog.filter(stat => maxForStat(stat.key) > 0) : catalog);
                }

                function filteredCatalog(query, selectedCategory) {
                  return snapshot.catalog.filter(stat => {
                    const matchesCategory = !selectedCategory || stat.category === selectedCategory;
                    const haystack = `${stat.category} ${stat.name} ${stat.key} ${stat.type} ${stat.value}`.toLowerCase();
                    return matchesCategory && (!query || haystack.includes(query));
                  });
                }
 
                function filterStatsByText(stats, query) {
                  const trimmed = String(query ?? '').trim().toLowerCase();
                  if (!trimmed) return stats;
                  return stats.filter(stat => `${stat.category} ${stat.name} ${stat.key} ${stat.type} ${stat.value}`.toLowerCase().includes(trimmed));
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
                  }

                  playerList.innerHTML = `
                    <div class="player-list-head">${visiblePlayers.length} Players</div>
                    ${visiblePlayers.map(player => `
                      <button class="player-button ${player.uuid === selectedPlayerId ? 'active' : ''}" data-player="${esc(player.uuid)}" data-name="${esc(String(player.name ?? '').toLowerCase())}">
                        <span>
                          <span>${esc(player.name)}</span>
                          <span class="player-meta">${esc(player.shownStats)} shown stats</span>
                        </span>
                        <span class="badge ${player.online ? '' : 'offline'}">${player.online ? 'Online' : 'Offline'}</span>
                      </button>
                    `).join('')}
                  `;

                  document.querySelectorAll('.player-button').forEach(button => {
                    button.addEventListener('click', () => {
                      selectedPlayerId = button.dataset.player;
                      render();
                    });
                  });

                  const player = visiblePlayers.find(item => item.uuid === selectedPlayerId) ?? visiblePlayers[0];
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
                      <table>
                        <thead><tr><th>Category</th><th>Statistic</th><th>Value</th></tr></thead>
                        <tbody>
                          ${player.stats.map(stat => `<tr><td>${esc(stat.category)}</td><td>${esc(stat.name)}</td><td class="value">${esc(stat.formatted)}</td></tr>`).join('')}
                        </tbody>
                      </table>
                    </article>
                  `;
                }

                function renderLeaderboards(catalog) {
                  if (hideZeroLeaderboards) {
                    catalog = catalog.filter(stat => maxForStat(stat.key) > 0);
                  }

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
                }

                function leaderboardTable(stat) {
                  const rows = snapshot.players
                    .map(player => ({
                      player,
                      raw: Number(player.values?.[stat.key] ?? 0),
                      formatted: player.statRows?.[stat.key]?.formatted
                    }))
                    .sort((a, b) => b.raw - a.raw || String(a.player.name).localeCompare(String(b.player.name)))
                    .slice(0, 10);

                  return `
                    <table>
                      <thead><tr><th>#</th><th>Player</th><th>Value</th></tr></thead>
                      <tbody>
                        ${rows.map((row, index) => `<tr><td>${index + 1}</td><td>${esc(row.player.name)}</td><td class="value">${esc(row.formatted ?? row.raw)}</td></tr>`).join('')}
                      </tbody>
                    </table>
                  `;
                }

                function maxForStat(statKey) {
                  return Math.max(0, ...snapshot.players.map(player => Number(player.values?.[statKey] ?? 0)));
                }

                function renderCharts(catalog) {
                  const nonZeroCatalog = catalog.filter(stat => maxForStat(stat.key) > 0);
                  const chartCatalog = nonZeroCatalog.length ? nonZeroCatalog : catalog;
                  if (!chartCatalog.length) {
                    charts.innerHTML = '<div class="empty">No chart data matches your filters.</div>';
                    return;
                  }

                  const comparisonCatalog = filterStatsByText(chartCatalog, comparisonStatSearch);
                  if (!selectedTrendStatKey || !comparisonCatalog.some(stat => stat.key === selectedTrendStatKey)) {
                    selectedTrendStatKey = comparisonCatalog[0]?.key ?? null;
                  }

                  const selectedComparisonStat = comparisonCatalog.find(stat => stat.key === selectedTrendStatKey);
                  const history = recentHistory();
                  const activityDays = historyDays(history);
                  if (selectedActivityDay !== 'latest' && !activityDays.some(day => day.key === selectedActivityDay)) {
                    selectedActivityDay = 'latest';
                  }
                  const totals = categoryTotals(chartCatalog).filter(row => row.raw > 0);
                  charts.innerHTML = `
                    <article class="chart-panel wide">
                      <h2>Professional server overview</h2>
                      <p class="chart-note">Historical graphs are built from Stats4Us samples recorded while the mod is running. Existing world stat files provide current totals, but cannot reconstruct old timelines before tracking began.</p>
                      <div class="insight-grid">
                        ${insightCard('History samples', history.length)}
                        ${insightCard('Peak online', Math.max(0, ...history.map(sample => sample.onlinePlayers)))}
                        ${insightCard('Tracked players', snapshot.totalPlayers)}
                        ${insightCard('Visible stats', snapshot.totalAvailableStats)}
                      </div>
                    </article>
                    <article class="chart-panel">
                      <h2>Player activity over time</h2>
                      ${lineChart(history, sample => sample.onlinePlayers, value => value.toLocaleString(), 'Online players')}
                    </article>
                    <article class="chart-panel">
                      <h2>Total playtime growth</h2>
                      ${lineChart(history, sample => Number(sample.totals?.['minecraft:custom|minecraft:play_time'] ?? 0), formatMinecraftTime, 'Total playtime')}
                    </article>
                    <article class="chart-panel">
                      <h2>Player comparison</h2>
                      <div class="chart-subtitle">${selectedComparisonStat ? `${esc(selectedComparisonStat.category)} — ${esc(selectedComparisonStat.name)}` : 'Search for a statistic to compare players.'}</div>
                      <div class="chart-controls">
                        <input id="comparisonStatSearch" placeholder="Search stats or categories, e.g. blocks mined, general" value="${esc(comparisonStatSearch)}">
                        <select id="trendStat">
                          ${comparisonCatalog.map(stat => `<option value="${esc(stat.key)}" ${stat.key === selectedTrendStatKey ? 'selected' : ''}>${esc(stat.category)} — ${esc(stat.name)}</option>`).join('')}
                        </select>
                        <select id="comparisonMode">
                          <option value="bar" ${comparisonMode === 'bar' ? 'selected' : ''}>Bar chart</option>
                          <option value="pie" ${comparisonMode === 'pie' ? 'selected' : ''}>Pie chart</option>
                          <option value="table" ${comparisonMode === 'table' ? 'selected' : ''}>Table</option>
                        </select>
                      </div>
                      ${selectedComparisonStat ? comparisonDisplay(playersForStat(selectedComparisonStat.key), comparisonMode) : '<div class="empty">No statistic matches this comparison search.</div>'}
                    </article>
                    <article class="chart-panel compact">
                      <h2>Category totals</h2>
                      <div class="chart-subtitle">Combined total values for every visible non-zero category.</div>
                      ${categoryTotalsChart(totals)}
                    </article>
                    <article class="chart-panel wide">
                      <h2>Player activity timeline</h2>
                      <p class="chart-note">This shows playtime gained between history samples. Each cell is one sample interval for a player: grey means no added playtime, brighter green means more playtime was gained during that interval.</p>
                      <div class="chart-controls">
                        <select id="activityDay">
                          <option value="latest" ${selectedActivityDay === 'latest' ? 'selected' : ''}>Latest 24 samples</option>
                          ${activityDays.map(day => `<option value="${esc(day.key)}" ${day.key === selectedActivityDay ? 'selected' : ''}>${esc(day.label)}</option>`).join('')}
                        </select>
                      </div>
                      ${activityTimeline(history, selectedActivityDay)}
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
                  const activitySelect = document.querySelector('#activityDay');
                  if (activitySelect) {
                    activitySelect.addEventListener('change', event => {
                      selectedActivityDay = event.target.value;
                      render();
                    });
                  }
                }

                function insightCard(label, value) {
                  return `<div class="insight-card"><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`;
                }

                function recentHistory() {
                  return (snapshot.history ?? []).slice(-96);
                }
 
                function historyDays(history) {
                  const days = new Map();
                  for (const sample of history) {
                    const key = localDateKey(sample.timestamp);
                    if (key && !days.has(key)) {
                      days.set(key, dayLabel(sample.timestamp));
                    }
                  }
                  return [...days.entries()]
                    .map(([key, label]) => ({ key, label }))
                    .sort((a, b) => b.key.localeCompare(a.key));
                }
 
                function localDateKey(timestamp) {
                  if (!timestamp) return '';
                  const date = new Date(timestamp);
                  if (Number.isNaN(date.getTime())) return '';
                  const year = date.getFullYear();
                  const month = String(date.getMonth() + 1).padStart(2, '0');
                  const day = String(date.getDate()).padStart(2, '0');
                  return `${year}-${month}-${day}`;
                }
 
                function dayLabel(timestamp) {
                  if (!timestamp) return '';
                  const date = new Date(timestamp);
                  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
                }

                function historyTrackedCatalog(catalog, history) {
                  const trackedKeys = new Set();
                  for (const sample of history) {
                    for (const key of Object.keys(sample.totals ?? {})) {
                      trackedKeys.add(key);
                    }
                  }
                  return catalog.filter(stat => trackedKeys.has(stat.key));
                }

                function lineChart(rows, valueFn, displayFn, label) {
                  if (!rows.length) {
                    return '<div class="empty">No history recorded yet. Keep the server running and this graph will fill automatically.</div>';
                  }

                  const values = rows.map(row => Number(valueFn(row) ?? 0)).filter(value => Number.isFinite(value));
                  if (!values.length) {
                    return '<div class="empty">No valid values are available for this graph yet.</div>';
                  }
                  const min = Math.min(...values);
                  const max = Math.max(...values);
                  const range = Math.max(1, max - min);
                  const gradientId = `lineGradient-${Math.random().toString(36).slice(2)}`;
                  const points = values.map((value, index) => {
                    const x = rows.length === 1 ? 50 : index / (rows.length - 1) * 1000;
                    const y = 220 - ((value - min) / range * 180 + 20);
                    return `${x},${y}`;
                  }).join(' ');
                  const last = values[values.length - 1] ?? 0;
                  const first = values[0] ?? 0;
                  const delta = last - first;

                  return `
                    <svg class="line-svg" viewBox="0 0 1000 260" role="img" aria-label="${esc(label)} line graph">
                      <defs>
                        <linearGradient id="${gradientId}" x1="0" x2="1">
                          <stop offset="0%" stop-color="#49d17d"/>
                          <stop offset="100%" stop-color="#f0b44c"/>
                        </linearGradient>
                      </defs>
                      <polyline points="${points}" fill="none" stroke="url(#${gradientId})" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
                      <text x="20" y="32" fill="#8b949e" font-size="24">${esc(label)}</text>
                      <text x="20" y="62" fill="#e6edf3" font-size="26" font-weight="800">${esc(displayFn(last))}</text>
                      <text x="20" y="92" fill="${delta >= 0 ? '#49d17d' : '#ff6b6b'}" font-size="20">${delta >= 0 ? '+' : ''}${esc(displayFn(delta))} over range</text>
                      <text x="20" y="244" fill="#8b949e" font-size="18">${esc(formatShortDate(rows[0]?.timestamp))}</text>
                      <text x="840" y="244" fill="#8b949e" font-size="18">${esc(formatShortDate(rows[rows.length - 1]?.timestamp))}</text>
                    </svg>
                  `;
                }

                function formatShortDate(timestamp) {
                  if (!timestamp) return '';
                  const date = new Date(timestamp);
                  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
                }

                function formatMinecraftTime(ticks) {
                  const seconds = Math.max(0, Math.floor(Number(ticks) / 20));
                  const hours = Math.floor(seconds / 3600);
                  const minutes = Math.floor((seconds % 3600) / 60);
                  return hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
                }

                function activityTimeline(history, selectedDay) {
                  if (history.length < 2) {
                    return '<div class="empty">Need at least two history samples to calculate activity changes. Keep the server running and this will fill automatically.</div>';
                  }

                  const playKey = 'minecraft:custom|minecraft:play_time';
                  const allPairs = [];
                  for (let i = 1; i < history.length; i++) {
                    const previous = history[i - 1];
                    const current = history[i];
                    if (selectedDay === 'latest' || localDateKey(current.timestamp) === selectedDay) {
                      allPairs.push({ previous, current });
                    }
                  }
                  const pairs = selectedDay === 'latest' ? allPairs.slice(-24) : allPairs;
                  if (!pairs.length) {
                    return '<div class="empty">No activity samples are available for the selected day yet.</div>';
                  }

                  const deltasByPlayer = snapshot.players.map(player => {
                    const deltas = [];
                    for (const pair of pairs) {
                      const current = Number(pair.current.players?.[player.uuid]?.values?.[playKey] ?? 0);
                      const previous = Number(pair.previous.players?.[player.uuid]?.values?.[playKey] ?? current);
                      deltas.push(Math.max(0, current - previous));
                    }
                    const total = deltas.reduce((sum, delta) => sum + delta, 0);
                    return { player, deltas, total };
                  }).sort((a, b) => b.total - a.total || String(a.player.name).localeCompare(String(b.player.name))).slice(0, 12);
                  const max = Math.max(1, ...deltasByPlayer.flatMap(row => row.deltas));
                  const totalActivity = deltasByPlayer.reduce((sum, row) => sum + row.total, 0);
                  const mostActive = deltasByPlayer.find(row => row.total > 0);
                  const columns = Math.max(1, pairs.length);
                  const firstTimestamp = pairs[0]?.previous?.timestamp;
                  const lastTimestamp = pairs[pairs.length - 1]?.current?.timestamp;
                  const windowLabel = selectedDay === 'latest' ? 'Latest samples' : dayLabel(lastTimestamp);

                  return `
                    <div class="activity-explainer">
                      <div class="activity-pill"><span>Selected range</span><strong>${esc(windowLabel)}</strong></div>
                      <div class="activity-pill"><span>Time window</span><strong>${esc(formatShortDate(firstTimestamp))} → ${esc(formatShortDate(lastTimestamp))}</strong></div>
                      <div class="activity-pill"><span>Cell meaning</span><strong>Playtime gained between samples</strong></div>
                      <div class="activity-pill"><span>Most active shown</span><strong>${esc(mostActive ? `${mostActive.player.name} · ${formatMinecraftTime(mostActive.total)}` : 'No activity yet')}</strong></div>
                      <div class="activity-pill"><span>Total shown</span><strong>${esc(formatMinecraftTime(totalActivity))}</strong></div>
                    </div>
                    <div class="activity-scale">
                      <span>No gain</span>
                      <span class="scale-swatch" style="--alpha:.12"></span>
                      <span class="scale-swatch" style="--alpha:.35"></span>
                      <span class="scale-swatch" style="--alpha:.65"></span>
                      <span class="scale-swatch" style="--alpha:1"></span>
                      <span>More playtime gained</span>
                    </div>
                    <div class="heatmap">
                      <div class="heat-row heat-header">
                        <div>Player</div>
                        <div class="heat-cells" style="grid-template-columns: repeat(${columns}, minmax(5px, 1fr));">
                          ${pairs.map((pair, index) => {
                            const showLabel = index === 0 || index === pairs.length - 1 || index % 6 === 0;
                            return `<span title="${esc(formatShortDate(pair.current.timestamp))}">${showLabel ? esc(formatShortDate(pair.current.timestamp).split(',').pop()?.trim() ?? '') : ''}</span>`;
                          }).join('')}
                        </div>
                        <div class="heat-total">Total</div>
                      </div>
                      ${deltasByPlayer.map(row => `
                        <div class="heat-row">
                          <div class="bar-label" title="${esc(row.player.name)}">${esc(row.player.name)}</div>
                          <div class="heat-cells" style="grid-template-columns: repeat(${columns}, minmax(5px, 1fr));">
                            ${row.deltas.map((delta, index) => {
                              const alpha = delta <= 0 ? .08 : Math.max(.18, delta / max);
                              const pair = pairs[index];
                              return `<span class="heat-cell" title="${esc(row.player.name)} gained ${esc(formatMinecraftTime(delta))} from ${esc(formatShortDate(pair?.previous?.timestamp))} to ${esc(formatShortDate(pair?.current?.timestamp))}" style="background: rgba(73, 209, 125, ${alpha})"></span>`;
                            }).join('')}
                          </div>
                          <div class="heat-total">${esc(formatMinecraftTime(row.total))}</div>
                        </div>
                      `).join('')}
                    </div>
                  `;
                }

                function topPlayersForStat(statKey) {
                  return playersForStat(statKey).slice(0, 12);
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

                function categoryTotals(catalog) {
                  return Object.entries(groupBy(catalog, stat => stat.category))
                    .map(([categoryName, stats]) => ({
                      category: categoryName,
                      raw: stats.reduce((sum, stat) => sum + snapshot.players.reduce((playerSum, player) => playerSum + Number(player.values?.[stat.key] ?? 0), 0), 0)
                    }))
                    .sort((a, b) => b.raw - a.raw || a.category.localeCompare(b.category));
                }

                function barChart(rows, labelFn, valueFn, displayFn) {
                  if (!rows.length) {
                    return '<div class="empty">No rows to display.</div>';
                  }
                  const max = Math.max(1, ...rows.map(valueFn));
                  return rows.map(row => {
                    const value = valueFn(row);
                    const width = value <= 0 ? 0 : Math.max(2, value / max * 100);
                    return `
                      <div class="bar-row">
                        <div class="bar-label" title="${esc(labelFn(row))}">${esc(labelFn(row))}</div>
                        <div class="bar-track"><div class="bar-fill" style="width:${width}%"></div></div>
                        <div class="bar-value">${esc(displayFn(row))}</div>
                      </div>
                    `;
                  }).join('');
                }
 
                function categoryTotalsChart(rows) {
                  if (!rows.length) {
                    return '<div class="empty">No non-zero category totals to display.</div>';
                  }
                  const max = Math.max(1, ...rows.map(row => row.raw));
                  return rows.map(row => {
                    const width = row.raw <= 0 ? 0 : Math.max(2, row.raw / max * 100);
                    return `
                      <div class="category-total-row">
                        <div class="category-total-head">
                          <div class="category-total-label" title="${esc(row.category)}">${esc(row.category)}</div>
                          <div class="category-total-value">${esc(row.raw.toLocaleString())}</div>
                        </div>
                        <div class="bar-track"><div class="bar-fill" style="width:${width}%"></div></div>
                      </div>
                    `;
                  }).join('');
                }

                function comparisonDisplay(rows, mode) {
                  const nonZeroRows = rows.filter(row => row.raw > 0);
                  const displayRows = (nonZeroRows.length ? nonZeroRows : rows).slice(0, 12);
                  if (mode === 'pie') {
                    return donutChartRows(displayRows, row => row.player.name, row => row.raw, row => row.formatted ?? row.raw);
                  }
                  if (mode === 'table') {
                    return comparisonTable(displayRows);
                  }
                  return barChart(displayRows, row => row.player.name, row => row.raw, row => row.formatted ?? row.raw);
                }

                function comparisonTable(rows) {
                  if (!rows.length) {
                    return '<div class="empty">No players to display.</div>';
                  }

                  return `
                    <table>
                      <thead><tr><th>#</th><th>Player</th><th>Value</th></tr></thead>
                      <tbody>
                        ${rows.map((row, index) => `<tr><td>${index + 1}</td><td>${esc(row.player.name)}</td><td class="value">${esc(row.formatted ?? row.raw)}</td></tr>`).join('')}
                      </tbody>
                    </table>
                  `;
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

                load();
                setInterval(load, 30000);
              </script>
            </body>
            </html>
            """;
    }
}
