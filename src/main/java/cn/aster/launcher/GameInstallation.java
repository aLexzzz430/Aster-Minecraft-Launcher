package cn.aster.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Starts the bundled Fabric client directly, with persistent player data outside the application. */
final class GameInstallation {
    private final Path root;
    private final Path app;
    private final Path game;
    private final JsonObject manifest;

    GameInstallation(Path root) throws IOException {
        this(root, root.toAbsolutePath().resolve("app"));
    }

    GameInstallation(Path root, Path app) throws IOException {
        this.root = root.toAbsolutePath();
        this.app = app.toAbsolutePath();
        this.game = this.root.resolve("game");
        this.manifest = JsonParser.parseString(Files.readString(app.resolve("client-manifest.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    Path gameDirectory() { return game; }
    String minecraftVersion() {
        String value = manifest.get("versionName").getAsString();
        return value.substring(value.lastIndexOf('-') + 1);
    }
    String serverAddress() { return manifest.get("server").getAsString(); }

    void prepare() throws IOException {
        prepare(message -> {});
    }

    void prepare(Consumer<String> progress) throws IOException {
        Files.createDirectories(game);
        Files.createDirectories(root.resolve("logs"));
        Path installed = game.resolve("aster-managed.json");
        JsonObject previous = Files.exists(installed)
                ? JsonParser.parseString(Files.readString(installed, StandardCharsets.UTF_8)).getAsJsonObject()
                : new JsonObject();
        String release = manifest.get("release").getAsString();
        if (previous.has("release") && release.equals(previous.get("release").getAsString())) return;

        Set<String> managed = paths(manifest.getAsJsonArray("managedFiles"));
        if (previous.has("files")) {
            for (String old : paths(previous.getAsJsonArray("files"))) {
                if (!managed.contains(old)) Files.deleteIfExists(gameFile(old));
            }
        }
        for (String path : managed) copySeed(path, true);
        for (String path : paths(manifest.getAsJsonArray("defaultFiles"))) copySeed(path, false);
        Set<String> introducedPacks = previous.has("introducedResourcePacks")
                ? paths(previous.getAsJsonArray("introducedResourcePacks")) : new LinkedHashSet<>();
        if (manifest.has("enableResourcePacks")) {
            Set<String> newPacks = paths(manifest.getAsJsonArray("enableResourcePacks"));
            newPacks.removeAll(introducedPacks);
            if (!newPacks.isEmpty()) {
                enableResourcePacks(newPacks);
                introducedPacks.addAll(newPacks);
            }
        }
        if (manifest.has("tuningRevision") && (!previous.has("tuningRevision")
                || !manifest.get("tuningRevision").equals(previous.get("tuningRevision")))) {
            applyVideoOptions(manifest.getAsJsonObject("optionOverrides"));
        }
        if (manifest.has("seedFiles")) {
            JsonObject previousSeeds = previous.has("seeds") ? previous.getAsJsonObject("seeds") : new JsonObject();
            for (var item : manifest.getAsJsonArray("seedFiles")) {
                JsonObject seed = item.getAsJsonObject();
                String path = seed.get("path").getAsString();
                if (seed.get("revision").equals(previousSeeds.get(path)) && Files.exists(gameFile(path))) continue;
                progress.accept("正在合并南湾港与星塔远景，首次打开需要一些时间");
                DistantHorizonSeed.install(app.resolve("game-content").resolve(path), gameFile(path));
            }
        }
        JsonObject state = new JsonObject();
        state.addProperty("release", release);
        state.add("files", manifest.getAsJsonArray("managedFiles").deepCopy());
        JsonArray resourcePacks = new JsonArray();
        introducedPacks.forEach(resourcePacks::add);
        state.add("introducedResourcePacks", resourcePacks);
        if (manifest.has("tuningRevision")) state.add("tuningRevision", manifest.get("tuningRevision"));
        JsonObject seeds = new JsonObject();
        if (manifest.has("seedFiles")) for (var item : manifest.getAsJsonArray("seedFiles")) {
            JsonObject seed = item.getAsJsonObject();
            seeds.add(seed.get("path").getAsString(), seed.get("revision"));
        }
        state.add("seeds", seeds);
        Files.writeString(installed, state.toString(), StandardCharsets.UTF_8);
    }

    private Map<String, String> readOptions() throws IOException {
        Path options = game.resolve("options.txt");
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
            int split = line.indexOf(':');
            if (split > 0) values.put(line.substring(0, split), line.substring(split + 1));
        }
        return values;
    }

    private void writeOptions(Map<String, String> values) throws IOException {
        List<String> lines = new ArrayList<>();
        values.forEach((key, value) -> lines.add(key + ":" + value));
        Files.write(game.resolve("options.txt"), lines, StandardCharsets.UTF_8);
    }

    private void applyVideoOptions(JsonObject overrides) throws IOException {
        Map<String, String> values = readOptions();
        for (var option : overrides.entrySet()) values.put(option.getKey(), option.getValue().getAsString());
        writeOptions(values);
    }

    private void enableResourcePacks(Set<String> additions) throws IOException {
        Map<String, String> values = readOptions();
        Set<String> selected = paths(JsonParser.parseString(values.getOrDefault("resourcePacks", "[]")).getAsJsonArray());
        selected.addAll(additions);
        JsonArray enabled = new JsonArray();
        selected.forEach(enabled::add);
        values.put("resourcePacks", enabled.toString());
        writeOptions(values);
    }

    void applyPerformancePreset(PerformancePreset preset, boolean restoreDefaults) throws IOException {
        Path stateFile = game.resolve("aster-performance.json");
        if (Files.exists(stateFile) && !restoreDefaults) {
            JsonObject state = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (preset.id.equals(state.get("preset").getAsString())
                    && PerformancePreset.revision().equals(state.get("revision").getAsString())) return;
        }
        applyVideoOptions(preset.options());
        Path sodiumFile = game.resolve("config/sodium-options.json");
        JsonObject sodium = Files.exists(sodiumFile)
                ? JsonParser.parseString(Files.readString(sodiumFile, StandardCharsets.UTF_8)).getAsJsonObject()
                : new JsonObject();
        mergeOptions(sodium, preset.sodium());
        Files.createDirectories(sodiumFile.getParent());
        Files.writeString(sodiumFile, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(sodium), StandardCharsets.UTF_8);
        Path horizonFile = game.resolve("config/aster_horizon.json");
        JsonObject horizon = Files.exists(horizonFile)
                ? JsonParser.parseString(Files.readString(horizonFile, StandardCharsets.UTF_8)).getAsJsonObject()
                : new JsonObject();
        mergeOptions(horizon, preset.horizon());
        Files.writeString(horizonFile, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(horizon), StandardCharsets.UTF_8);
        JsonObject state = new JsonObject();
        state.addProperty("preset", preset.id);
        state.addProperty("revision", PerformancePreset.revision());
        Files.writeString(stateFile, state.toString(), StandardCharsets.UTF_8);
    }

    private static void mergeOptions(JsonObject target, JsonObject changes) {
        for (var entry : changes.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                if (!target.has(entry.getKey())) target.add(entry.getKey(), new JsonObject());
                mergeOptions(target.getAsJsonObject(entry.getKey()), entry.getValue().getAsJsonObject());
            } else target.add(entry.getKey(), entry.getValue());
        }
    }

    private void copySeed(String relative, boolean replace) throws IOException {
        Path target = gameFile(relative);
        if (!replace && Files.exists(target)) return;
        Files.createDirectories(target.getParent());
        Files.copy(app.resolve("game-content").resolve(relative), target, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path gameFile(String relative) throws IOException {
        Path target = game.resolve(relative).normalize();
        if (!target.startsWith(game)) throw new IOException("游戏文件路径不属于客户端目录：" + relative);
        return target;
    }

    private static Set<String> paths(JsonArray array) {
        Set<String> result = new LinkedHashSet<>();
        for (var value : array) result.add(value.getAsString());
        return result;
    }

    List<String> arguments(String username, int memoryMb) {
        return arguments(username, memoryMb, PerformancePreset.BALANCED);
    }

    List<String> arguments(String username, int memoryMb, PerformancePreset preset) {
        // The process starts in game/. Keeping paths relative also avoids Windows argument-file codepages.
        Path argumentRoot = Path.of("..", "app");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("app_directory", argumentRoot.toString());
        values.put("game_directory", ".");
        values.put("auth_player_name", username);
        values.put("auth_uuid", UUID.nameUUIDFromBytes(("OfflinePlayer:" + username)
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""));
        values.put("auth_access_token", "0");
        values.put("clientid", "");
        values.put("auth_xuid", "0");
        values.put("version_name", manifest.get("versionName").getAsString());
        values.put("version_type", "release");
        values.put("assets_root", argumentRoot.resolve("minecraft/assets").toString());
        values.put("assets_index_name", manifest.get("assetIndex").getAsString());
        values.put("natives_directory", argumentRoot.resolve("natives").toString());
        values.put("launcher_name", "AsterRPG");
        values.put("launcher_version", manifest.get("release").getAsString());
        values.put("resolution_width", "1440");
        values.put("resolution_height", "900");
        values.put("quickPlayPath", "quickPlay/log.json");
        values.put("quickPlayMultiplayer", manifest.get("server").getAsString());
        List<String> classpath = new ArrayList<>();
        for (var entry : manifest.getAsJsonArray("classpath"))
            classpath.add(argumentRoot.resolve(entry.getAsString()).toString());
        values.put("classpath", String.join(";", classpath));
        List<String> args = new ArrayList<>(List.of("-Xms512M", "-Xmx" + memoryMb + "M", "-Dfile.encoding=UTF-8"));
        args.addAll(preset.jvmArguments());
        for (var arg : manifest.getAsJsonArray("jvmArguments")) args.add(expand(arg.getAsString(), values));
        args.add(manifest.get("mainClass").getAsString());
        for (var arg : manifest.getAsJsonArray("gameArguments")) args.add(expand(arg.getAsString(), values));
        return List.copyOf(args);
    }

    private static String expand(String argument, Map<String, String> values) {
        for (var value : values.entrySet()) argument = argument.replace("${" + value.getKey() + "}", value.getValue());
        if (argument.contains("${")) throw new IllegalArgumentException("启动参数没有配置完整：" + argument);
        return argument;
    }

    static String argumentFile(List<String> arguments) {
        StringBuilder text = new StringBuilder();
        for (String arg : arguments)
            text.append('"').append(arg.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"').append('\n');
        return text.toString();
    }

    Process launch(String username, int memoryMb, PerformancePreset preset) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows"))
            throw new IllegalStateException("这个单文件客户端适用于 Windows；当前系统无法运行内置的 Windows 游戏运行库。");
        Path args = root.resolve("game-launch.args");
        Files.writeString(args, argumentFile(arguments(username, memoryMb, preset)), StandardCharsets.UTF_8);
        Path log = root.resolve("logs/game-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log");
        return new ProcessBuilder(app.resolve(manifest.get("javaPath").getAsString()).toString(), "@../game-launch.args")
                .directory(game.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    int checkBundle() throws IOException {
        List<String> required = new ArrayList<>();
        required.add(manifest.get("javaPath").getAsString());
        for (var entry : manifest.getAsJsonArray("classpath")) required.add(entry.getAsString());
        for (var entry : manifest.getAsJsonArray("managedFiles")) required.add("game-content/" + entry.getAsString());
        for (var entry : manifest.getAsJsonArray("defaultFiles")) required.add("game-content/" + entry.getAsString());
        if (manifest.has("seedFiles")) for (var entry : manifest.getAsJsonArray("seedFiles"))
            required.add("game-content/" + entry.getAsJsonObject().get("path").getAsString());
        required.add("minecraft/assets/indexes/" + manifest.get("assetIndex").getAsString() + ".json");
        for (String relative : required)
            if (!Files.isRegularFile(app.resolve(relative))) throw new IOException("客户端缺少文件：" + relative);
        arguments("BundleCheck", 4096);
        return required.size();
    }
}
