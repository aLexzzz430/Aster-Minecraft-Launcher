package cn.aster.launcher;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Downloads immutable release files while the running game keeps using the old app directory. */
final class ClientUpdater {
    static final URI FEED = URI.create("https://189.24.77.244/aster-client/v2/manifest.json");
    static final int BOOTSTRAP_VERSION = 2;
    private final Path root;
    private final URI feed;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private volatile String updateToken;
    private volatile String updateAccount;
    record Result(boolean ready, String release, long downloadedBytes) {}

    ClientUpdater(Path root) { this(root, FEED); }
    ClientUpdater(Path root, URI feed) { this.root = root; this.feed = feed; }
    void authorize(String token, String account) {
        updateToken = token == null || token.isBlank() ? null : token;
        updateAccount = updateToken == null ? null : account.toLowerCase(Locale.ROOT);
    }

    String currentRelease() throws IOException {
        return read(root.resolve("app/client-manifest.json")).get("release").getAsString();
    }
    boolean ready() { return Files.isRegularFile(root.resolve("updates/ready.ini")); }

    Result checkAndStage(Consumer<String> progress) throws Exception {
        if (updateToken == null) throw new IOException("请先登录游戏账号，再检查客户端更新");
        try { sendPendingCompletion(); }
        catch (Exception error) { progress.accept("正在保留上次下载统计，稍后重试补报"); }
        if (ready()) return new Result(true, read(root.resolve("app.next/client-manifest.json")).get("release").getAsString(), 0);
        progress.accept("正在检查客户端更新…");
        var response = http.send(request(feed).timeout(Duration.ofSeconds(25)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw responseError(response.statusCode());
        JsonObject index = JsonParser.parseString(response.body()).getAsJsonObject();
        if (index.get("format").getAsInt() != 1 || index.get("bootstrapVersion").getAsInt() > BOOTSTRAP_VERSION)
            throw new IOException("此版本需要安装新版 AsterRPG.exe");
        String release = index.get("release").getAsString();
        if (compareVersions(release, currentRelease()) <= 0) {
            Files.deleteIfExists(root.resolve("updates/download-run.json"));
            return new Result(false, currentRelease(), 0);
        }
        JsonObject previous = read(root.resolve("app/update-index.json")).getAsJsonObject("files");
        JsonObject files = index.getAsJsonObject("files");
        Path next = root.resolve("app.next"), cache = root.resolve("updates/downloads/" + release);
        deleteTree(next); Files.createDirectories(next); Files.createDirectories(cache);
        long downloadBytes = 0, appBytes = 0;
        JsonArray needed = new JsonArray();
        for (var entry : files.entrySet()) {
            Path current = safe(root.resolve("app"), entry.getKey());
            long size = entry.getValue().getAsJsonObject().get("size").getAsLong();
            if (size < 0) throw new IOException("更新文件大小无效");
            appBytes += size;
            if (!reusable(previous, entry, current)) {
                downloadBytes += size;
                Path partial = safe(cache, entry.getKey() + ".part");
                if (!Files.isRegularFile(partial) || Files.size(partial) != size)
                    needed.add(entry.getValue().getAsJsonObject().get("url").getAsString());
            }
        }
        if (Files.getFileStore(root).getUsableSpace() < appBytes + downloadBytes + 32 * 1024 * 1024L)
            throw new IOException("磁盘空间不足，更新需要约 " + mib(appBytes + downloadBytes) + " MiB 可用空间");
        String runId = downloadBytes == 0 ? null : resumeOrStartRun(release, needed, progress);
        long downloaded = 0;
        final long totalDownload = downloadBytes;
        for (var entry : files.entrySet()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String relative = entry.getKey();
            Path source = safe(root.resolve("app"), relative), target = safe(next, relative);
            Files.createDirectories(target.getParent());
            JsonObject item = entry.getValue().getAsJsonObject();
            if (reusable(previous, entry, source)) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            else {
                long size = item.get("size").getAsLong(), before = downloaded;
                URI url = feed.resolve(item.get("url").getAsString());
                if (!Objects.equals(url.getScheme(), feed.getScheme()) || !Objects.equals(url.getAuthority(), feed.getAuthority()))
                    throw new IOException("更新下载地址与发布服务不一致");
                Path partial = safe(cache, relative + ".part");
                download(url, partial, size, runId,
                        count -> progress.accept("下载更新 " + mib(before + count) + " / " + mib(totalDownload) + " MiB"));
                Files.copy(partial, target, StandardCopyOption.REPLACE_EXISTING);
                downloaded += size;
            }
        }
        Files.writeString(next.resolve("update-index.json"), index.toString());
        JsonObject manifest = read(next.resolve("client-manifest.json"));
        if (!release.equals(manifest.get("release").getAsString())) throw new IOException("发布版本与客户端版本不一致");
        new GameInstallation(root, next).checkBundle();
        Files.writeString(next.resolve("update-release.ini"), "[client]\r\nrelease=" + release + "\r\n");
        Path marker = root.resolve("updates/ready.tmp");
        Files.writeString(marker, "[update]\r\nrelease=" + release + "\r\n");
        Files.move(marker, root.resolve("updates/ready.ini"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        deleteTree(root.resolve("updates/downloads"));
        Files.deleteIfExists(root.resolve("updates/download-run.json"));
        if (runId != null) {
            Path pending = root.resolve("updates/complete-pending.json");
            Files.writeString(pending, "{\"run\":\"" + runId + "\"}");
            try { sendPendingCompletion(); }
            catch (Exception error) { progress.accept("更新已准备好，下载统计将在下次登录后补报"); }
        }
        return new Result(true, release, downloaded);
    }

    private HttpRequest.Builder request(URI uri) throws IOException {
        String token = updateToken;
        if (token == null) throw new IOException("请先登录游戏账号，再检查客户端更新");
        return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token);
    }

    private static IOException responseError(int status) {
        return new IOException(status == 401 ? "更新登录凭证已过期，请切换账号重新登录" : "更新服务返回 HTTP " + status);
    }

    private String startRun(String release, JsonArray needed, Consumer<String> progress) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("release", release);
        body.add("files", needed);
        var response = http.send(request(feed.resolve("start")).timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw responseError(response.statusCode());
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        int number = result.get("downloadNumber").getAsInt();
        int reduction = result.get("speedReductionPercent").getAsInt();
        if (reduction > 0) progress.accept("此版本第 " + number + " 次下载，速度降低 " + reduction + "%");
        return result.get("run").getAsString();
    }

    String resumeOrStartRun(String release, JsonArray needed, Consumer<String> progress) throws Exception {
        Path marker = root.resolve("updates/download-run.json");
        if (Files.isRegularFile(marker)) {
            JsonObject saved = read(marker);
            if (saved.has("account") && updateAccount.equals(saved.get("account").getAsString())
                    && release.equals(saved.get("release").getAsString())
                    && System.currentTimeMillis() - saved.get("createdAt").getAsLong() < 23 * 60 * 60_000L) {
                Set<String> allowed = new HashSet<>();
                saved.getAsJsonArray("files").forEach(item -> allowed.add(item.getAsString()));
                boolean sameRun = true;
                for (var item : needed) if (!allowed.contains(item.getAsString())) sameRun = false;
                if (sameRun) {
                    progress.accept("继续上次未完成的下载");
                    return saved.get("run").getAsString();
                }
            }
        }
        if (needed.isEmpty()) return null;
        String runId = startRun(release, needed, progress);
        JsonObject saved = new JsonObject();
        saved.addProperty("run", runId);
        saved.addProperty("release", release);
        saved.addProperty("account", updateAccount);
        saved.add("files", needed);
        saved.addProperty("createdAt", System.currentTimeMillis());
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, saved.toString());
        return runId;
    }

    private void sendPendingCompletion() throws Exception {
        Path pending = root.resolve("updates/complete-pending.json");
        if (!Files.isRegularFile(pending)) return;
        String body = Files.readString(pending);
        var response = http.send(request(feed.resolve("complete")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) throw responseError(response.statusCode());
        Files.deleteIfExists(pending);
    }

    private boolean reusable(JsonObject previous, Map.Entry<String, JsonElement> entry, Path source) throws IOException {
        return previous.has(entry.getKey()) && previous.getAsJsonObject(entry.getKey()).get("version")
                .equals(entry.getValue().getAsJsonObject().get("version")) && Files.isRegularFile(source)
                && Files.size(source) == entry.getValue().getAsJsonObject().get("size").getAsLong();
    }
    private void download(URI uri, Path partial, long size, String runId, Consumer<Long> progress) throws Exception {
        Files.createDirectories(partial.getParent());
        long offset = Files.exists(partial) ? Files.size(partial) : 0;
        if (offset > size) { Files.delete(partial); offset = 0; }
        if (offset == size) { progress.accept(size); return; }
        if (runId == null) throw new IOException("更新下载会话未建立");
        var builder = request(uri).header("X-Aster-Update-Run", runId).timeout(Duration.ofHours(3)).GET();
        if (offset > 0) builder.header("Range", "bytes=" + offset + "-");
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 429) {
                Files.deleteIfExists(root.resolve("updates/download-run.json"));
                throw new IOException(response.statusCode() == 429
                        ? "下载会话已用尽，请重新检查更新" : "更新登录凭证已过期，请切换账号重新登录");
            }
            if (response.statusCode() == 200) offset = 0;
            else if (response.statusCode() != 206 || !response.headers().firstValue("Content-Range")
                    .orElse("").startsWith("bytes " + offset + "-")) throw new IOException("更新下载失败：HTTP " + response.statusCode());
            try (OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    offset == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
                byte[] block = new byte[128 * 1024]; int n; long last = 0;
                while ((n = input.read(block)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    output.write(block, 0, n); offset += n;
                    if (System.nanoTime() - last > 300_000_000L) { progress.accept(offset); last = System.nanoTime(); }
                }
            }
        }
        if (offset != size) throw new IOException("下载尚未完成，下次将继续下载");
    }
    static Path safe(Path parent, String relative) throws IOException {
        Path path = parent.resolve(relative).normalize();
        if (relative.contains("\\") || Path.of(relative).isAbsolute() || !path.startsWith(parent) || path.equals(parent))
            throw new IOException("无效的更新文件路径");
        return path;
    }
    static int compareVersions(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        if (left.length != 4 || right.length != 4) throw new IllegalArgumentException("无效的客户端版本");
        for (int i = 0; i < 4; i++) { int c = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i])); if (c != 0) return c; }
        return 0;
    }
    static JsonObject read(Path path) throws IOException { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    static long mib(long value) { return (value + 1048575) / 1048576; }
    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) { for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
}
