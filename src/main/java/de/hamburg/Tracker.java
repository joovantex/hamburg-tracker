package de.hamburg;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;

/** Server-Mod: /state liefert Spieler, /blocks?since=N liefert Blockänderungen. */
public class Tracker implements ModInitializer {
    record Ev(long seq, String json) {}

    static final int MAX_EVENTS = 50000;
    static final ArrayDeque<Ev> EVENTS = new ArrayDeque<>();
    static long seq = 0;
    static volatile String players = "[]";
    static HttpServer http;
    static int tick = 0;

    public static void blockChanged(int x, int y, int z, BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (id.equals("minecraft:water") || id.equals("minecraft:lava")) return;
        synchronized (EVENTS) {
            seq++;
            EVENTS.addLast(new Ev(seq, "[" + seq + "," + x + "," + y + "," + z + ",\"" + id + "\"]"));
            if (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        }
    }

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tick % 20 != 0) return;
            StringBuilder sb = new StringBuilder("[");
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (sb.length() > 1) sb.append(',');
                String name = p.getName().getString().replace("\"", "");
                boolean ow = p.level().dimension() == Level.OVERWORLD;
                sb.append("{\"name\":\"").append(name).append("\",\"x\":").append(p.getX())
                  .append(",\"y\":").append(p.getY()).append(",\"z\":").append(p.getZ())
                  .append(",\"yaw\":").append(p.getYRot()).append(",\"overworld\":").append(ow).append('}');
            }
            players = sb.append(']').toString();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> start());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (http != null) http.stop(0); });
    }

    static void start() {
        int port = 25580;
        try {
            Path cfg = FabricLoader.getInstance().getConfigDir().resolve("hamburg-tracker.txt");
            if (Files.exists(cfg)) port = Integer.parseInt(Files.readString(cfg).trim());
            else Files.writeString(cfg, String.valueOf(port));
        } catch (Exception e) { System.out.println("[hamburg-tracker] Config-Fehler: " + e); }
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/state", ex -> send(ex, "{\"players\":" + players + ",\"seq\":" + seq + "}"));
            http.createContext("/blocks", ex -> {
                long since = 0;
                String q = ex.getRequestURI().getQuery();
                if (q != null && q.startsWith("since=")) {
                    try { since = Long.parseLong(q.substring(6)); } catch (Exception ignored) {}
                }
                StringBuilder sb = new StringBuilder("{\"seq\":");
                synchronized (EVENTS) {
                    sb.append(seq).append(",\"events\":[");
                    boolean first = true;
                    for (Ev e : EVENTS) {
                        if (e.seq() <= since) continue;
                        if (!first) sb.append(',');
                        sb.append(e.json());
                        first = false;
                    }
                }
                send(ex, sb.append("]}").toString());
            });
            http.setExecutor(Executors.newFixedThreadPool(2));
            http.start();
            System.out.println("[hamburg-tracker] läuft auf Port " + port);
        } catch (Exception e) {
            System.out.println("[hamburg-tracker] Konnte Port " + port + " nicht öffnen: " + e);
        }
    }

    static void send(HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }
}
