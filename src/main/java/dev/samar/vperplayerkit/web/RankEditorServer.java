package dev.samar.vperplayerkit.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.ranks.RankManager;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Small built-in web server for the rank editor. /v:rank editor gives a link with a temporary token, the page edits the rank
 * definitions (tag text, priority, commands). Settings: web.* in config.yml. The link is plain http, keep the port closed
 * to the public or put it behind your own https reverse proxy and set web.public-url.
 */
public final class RankEditorServer {
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final VPerPlayerKit plugin;
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private HttpServer server;
    private byte[] page;

    public RankEditorServer(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("web.enabled", true);
    }

    public boolean running() {
        return server != null;
    }

    public void start() {
        if (!enabled()) return;
        try (InputStream in = plugin.getResource("web/editor.html")) {
            if (in == null) return;
            page = in.readAllBytes();
            String bind = plugin.getConfig().getString("web.bind", "0.0.0.0");
            int port = plugin.getConfig().getInt("web.port", 8123);
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/api/ranks", this::api);
            server.createContext("/", this::serve);
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            plugin.getLogger().info("Rank editor web server listening on " + bind + ":" + port);
        } catch (Throwable ex) {       // also covers a Java runtime without the jdk.httpserver module
            plugin.getLogger().log(Level.WARNING, "Could not start the rank editor web server", ex);
            server = null;
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        server = null;
    }

    /** A link to the editor with a fresh token, or null when the web server is not running. */
    public String createLink() {
        if (server == null) return null;
        long now = System.currentTimeMillis();
        tokens.values().removeIf(expiry -> expiry < now);
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 24; i++) token.append(CHARS.charAt(random.nextInt(CHARS.length())));
        long minutes = Math.max(1, plugin.getConfig().getInt("web.token-minutes", 15));
        tokens.put(token.toString(), now + minutes * 60_000L);

        String base = plugin.getConfig().getString("web.public-url", "");
        if (base == null || base.isBlank()) {
            String ip = Bukkit.getIp();
            base = "http://" + (ip == null || ip.isBlank() ? "localhost" : ip) + ":" + plugin.getConfig().getInt("web.port", 8123);
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/?token=" + token;
    }

    // ------------------------------------------------------------------ http

    private boolean valid(HttpExchange ex) {
        String token = query(ex).get("token");
        if (token == null) return false;
        Long expiry = tokens.get(token);
        return expiry != null && expiry >= System.currentTimeMillis();
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void serve(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!valid(ex)) {
            send(ex, 403, "text/plain; charset=utf-8",
                    "This link expired. Run /v:rank editor in game for a new one.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", page);
    }

    private void api(HttpExchange ex) throws IOException {
        if (!valid(ex)) {
            send(ex, 403, "application/json", "{\"ok\":false,\"error\":\"expired\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
                JsonArray array = new JsonArray();
                for (RankManager.RankDef d : plugin.ranks().definitions()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("key", d.key());
                    o.addProperty("tag", d.tag());
                    o.addProperty("priority", d.priority());
                    o.addProperty("onAdd", d.onAdd());
                    o.addProperty("onRemove", d.onRemove());
                    array.add(o);
                }
                send(ex, 200, "application/json; charset=utf-8", array.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (ex.getRequestMethod().equalsIgnoreCase("POST")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                List<RankManager.RankDef> list = new ArrayList<>();
                for (JsonElement el : JsonParser.parseString(body).getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    String key = RankManager.cleanKey(o.get("key").getAsString());
                    if (key == null) continue;
                    String tag = o.get("tag").getAsString();
                    list.add(new RankManager.RankDef(key, tag.length() > 200 ? tag.substring(0, 200) : tag,
                            o.get("priority").getAsInt(), o.get("onAdd").getAsString(), o.get("onRemove").getAsString()));
                }
                plugin.ranks().saveDefs(list);
                send(ex, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ex2) {
            send(ex, 400, "application/json", "{\"ok\":false,\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
