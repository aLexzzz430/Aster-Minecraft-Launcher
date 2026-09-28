package cn.aster.launcher;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClientUpdaterTest {
    static void expect(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void write(Path p, String s) throws Exception { Files.createDirectories(p.getParent()); Files.writeString(p, s); }
    static JsonObject index(Map<String, String> payload, String version) {
        JsonObject index = new JsonObject(), files = new JsonObject();
        index.addProperty("format", 1); index.addProperty("bootstrapVersion", 1); index.addProperty("release", version);
        payload.forEach((name, body) -> {
            JsonObject e = new JsonObject(); e.addProperty("version", name.equals("client-manifest.json") || name.contains("mod") ? version : "1.0.0.1");
            e.addProperty("size", body.getBytes(StandardCharsets.UTF_8).length); e.addProperty("url", "files/" + name); files.add(name, e);
        });
        index.add("files", files); return index;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("Aster updater 中文 ");
        String manifest = """
          {"release":"1.0.0.1","javaPath":"runtime/javaw.exe","classpath":[],"managedFiles":["mods/core.jar"],
           "defaultFiles":["options.txt"],"assetIndex":"29","versionName":"Aster","server":"localhost:25565",
           "mainClass":"Game","jvmArguments":[],"gameArguments":[]}
          """;
        Map<String, String> old = new LinkedHashMap<>(Map.of("client-manifest.json", manifest,
                "runtime/javaw.exe", "runtime", "game-content/mods/core.jar", "oldmod",
                "game-content/options.txt", "defaults", "minecraft/assets/indexes/29.json", "{}"));
        for (var file : old.entrySet()) write(root.resolve("app/" + file.getKey()), file.getValue());
        write(root.resolve("app/update-index.json"), index(old, "1.0.0.1").toString());
        write(root.resolve("game/options.txt"), "player options");
        write(root.resolve("game/screenshots/example.txt"), "player screenshot");
        Map<String, String> next = new LinkedHashMap<>(old);
        next.put("client-manifest.json", manifest.replace("1.0.0.1", "1.0.0.2"));
        next.put("game-content/mods/core.jar", "new-mod-complete-content");
        JsonObject targetIndex = index(next, "1.0.0.2");
        targetIndex.addProperty("bootstrapVersion", 2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger resumes = new AtomicInteger(), requests = new AtomicInteger(), starts = new AtomicInteger(), completions = new AtomicInteger();
        server.createContext("/manifest.json", exchange -> {
            if (!"Bearer test-update-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            byte[] b = targetIndex.toString().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b); exchange.close();
        });
        server.createContext("/start", exchange -> {
            if (!"Bearer test-update-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes())).getAsJsonObject();
            String requested = body.get("release").getAsString();
            expect((requested.equals("1.0.0.2") && body.getAsJsonArray("files").size() == 2)
                    || (requested.equals("1.0.0.3") && body.getAsJsonArray("files").size() > 0),
                    "update session only includes needed release files");
            starts.incrementAndGet();
            byte[] b = "{\"run\":\"test-run\",\"downloadNumber\":1,\"speedReductionPercent\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length); exchange.getResponseBody().write(b); exchange.close();
        });
        server.createContext("/complete", exchange -> {
            expect("Bearer test-update-token".equals(exchange.getRequestHeaders().getFirst("Authorization")),
                    "completion is authenticated");
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes())).getAsJsonObject();
            expect(body.get("run").getAsString().equals("test-run"), "completion references update run");
            completions.incrementAndGet();
            byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length); exchange.getResponseBody().write(b); exchange.close();
        });
        server.createContext("/files/", exchange -> {
            expect("Bearer test-update-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    && "test-run".equals(exchange.getRequestHeaders().getFirst("X-Aster-Update-Run")),
                    "file downloads require account authorization and update run");
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath().substring(7);
            byte[] body = next.get(path).getBytes(StandardCharsets.UTF_8);
            String range = exchange.getRequestHeaders().getFirst("Range"); int offset = 0;
            if (range != null) { offset = Integer.parseInt(range.substring(6, range.length() - 1)); resumes.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Range", "bytes " + offset + "-" + (body.length - 1) + "/" + body.length); }
            exchange.sendResponseHeaders(offset > 0 ? 206 : 200, body.length - offset);
            exchange.getResponseBody().write(body, offset, body.length - offset); exchange.close();
        });
        server.start();
        try {
            write(root.resolve("updates/downloads/1.0.0.2/game-content/mods/core.jar.part"), "new-mod-");
            var updater = new ClientUpdater(root, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/manifest.json"));
            try { updater.checkAndStage(s -> {}); throw new AssertionError("login required before update check"); }
            catch (java.io.IOException expected) { expect(expected.getMessage().contains("登录"), "pre-login update is denied"); }
            updater.authorize("test-update-token");
            var result = updater.checkAndStage(s -> {});
            expect(result.ready() && resumes.get() == 1 && requests.get() == 2 && starts.get() == 1 && completions.get() == 1,
                    "one authenticated update run downloads only two changed files and reports completion");
            expect(Files.readString(root.resolve("app/game-content/mods/core.jar")).equals("oldmod"), "running app remains untouched");
            expect(Files.readString(root.resolve("app.next/game-content/mods/core.jar")).equals(next.get("game-content/mods/core.jar")), "staged mod is complete");
            expect(Files.readString(root.resolve("game/options.txt")).equals("player options"), "player settings untouched");
            int downloaded = requests.get(); updater.checkAndStage(s -> {});
            expect(downloaded == requests.get(), "prepared update reused without redownload");
            JsonArray pendingFiles = new JsonArray(); pendingFiles.add("files/game-content/mods/core.jar");
            write(root.resolve("updates/download-run.json"), "{\"run\":\"prior-run\",\"release\":\"1.0.0.3\","
                    + "\"files\":[\"files/game-content/mods/core.jar\"],\"createdAt\":" + System.currentTimeMillis() + "}");
            int beforeResume = starts.get();
            var resumed = new ClientUpdater(root, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/manifest.json"));
            resumed.authorize("test-update-token");
            expect(resumed.resumeOrStartRun("1.0.0.3", pendingFiles, s -> {}).equals("prior-run")
                    && starts.get() == beforeResume, "restart reuses the previous authenticated download run");
            expect(resumed.resumeOrStartRun("1.0.0.3", new JsonArray(), s -> {}).equals("prior-run")
                    && starts.get() == beforeResume, "fully cached files retain the run for completion reporting");
            Files.delete(root.resolve("updates/download-run.json"));
            ClientUpdater.deleteTree(root.resolve("app")); Files.move(root.resolve("app.next"), root.resolve("app"));
            Files.delete(root.resolve("updates/ready.ini"));
            expect(!updater.checkAndStage(s -> {}).ready(), "current release does not reinstall itself");
            targetIndex.addProperty("release", "1.0.0.1");
            expect(!updater.checkAndStage(s -> {}).ready(), "old feed cannot downgrade the client");
            targetIndex.addProperty("release", "1.0.0.3");
            targetIndex.getAsJsonObject("files").getAsJsonObject("game-content/mods/core.jar").addProperty("version", "1.0.0.3");
            targetIndex.getAsJsonObject("files").getAsJsonObject("game-content/mods/core.jar").addProperty("size", 500);
            try { updater.checkAndStage(s -> {}); throw new AssertionError("truncated download should not stage"); }
            catch (java.io.IOException expected) { expect(!updater.ready(), "failed download never becomes ready"); }
            write(root.resolve("client.ini"), "");
            Files.writeString(root.resolve("client.ini"), "\uFEFF[client]\r\nrelease=1.0.0.2\r\n", StandardCharsets.UTF_16LE);
            write(root.resolve("AsterRPG.exe"), "entrypoint");
            Path destination = root.resolveSibling(root.getFileName() + " moved 中文");
            ClientMaintenance.migrate(root, destination, s -> {});
            expect(Files.readString(destination.resolve("game/screenshots/example.txt")).equals("player screenshot"), "migration preserves screenshot");
            expect(Files.readString(destination.resolve("client.ini"), StandardCharsets.UTF_16LE).contains(root.toRealPath().toString()), "unicode source reaches native migration");
            expect(Files.exists(root.resolve("app/client-manifest.json")), "migration retains original until native handoff");
            expect(!Files.exists(destination.resolve("updates")), "migration does not copy incomplete download cache");
            ClientUpdater.deleteTree(destination);
            System.out.println("PASS incremental HTTP update, range resume, no live replacement, pending reuse, no downgrade, incomplete transfer and Unicode migration");
        } finally { server.stop(0); ClientUpdater.deleteTree(root); }
    }
}
