package cn.aster.launcher;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Player-owned resource packs live in game/, outside the release-managed application. */
final class ResourcePacks {
    record Pack(String name, boolean enabled) {}
    private final Path root;
    ResourcePacks(Path root) { this.root = root; }
    Path directory() throws IOException {
        Path path = root.resolve("game/resourcepacks"); Files.createDirectories(path); return path;
    }
    private Set<String> managed() throws IOException {
        Set<String> names = new HashSet<>();
        JsonObject manifest = ClientUpdater.read(root.resolve("app/client-manifest.json"));
        for (JsonElement file : manifest.getAsJsonArray("managedFiles")) {
            String path = file.getAsString();
            if (path.startsWith("resourcepacks/")) names.add(path.substring("resourcepacks/".length()).toLowerCase(Locale.ROOT));
        }
        return names;
    }
    List<Pack> list() throws IOException {
        Set<String> managed = managed();
        Set<String> enabled = selected(readOptions());
        try (var files = Files.list(directory())) {
            return files.filter(p -> Files.isDirectory(p) ? Files.isRegularFile(p.resolve("pack.mcmeta"))
                            : p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .filter(p -> !managed.contains(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(p -> new Pack(p.getFileName().toString(), enabled.contains("file/" + p.getFileName()))).toList();
        }
    }
    List<String> importPacks(List<Path> sources, boolean enable, Consumer<String> progress) throws IOException {
        if (sources.isEmpty()) return List.of();
        for (Path source : sources) validate(source);
        Set<String> reserved = managed();
        Path directory = directory();
        List<String> imported = new ArrayList<>();
        for (Path source : sources) {
            progress.accept("正在导入：" + source.getFileName());
            Path destination = directory.resolve(source.getFileName().toString());
            if (Files.exists(destination) && Files.isSameFile(source, destination) &&
                    !reserved.contains(destination.getFileName().toString().toLowerCase(Locale.ROOT))) {
                imported.add(destination.getFileName().toString()); continue;
            }
            String name = source.getFileName().toString();
            int extension = Files.isDirectory(source) ? name.length() : name.lastIndexOf('.');
            for (int n = 2; Files.exists(destination) || reserved.contains(destination.getFileName().toString().toLowerCase(Locale.ROOT)); n++)
                destination = directory.resolve(name.substring(0, extension) + " (" + n + ")" + name.substring(extension));
            Path temporary = Files.isDirectory(source) ? Files.createTempDirectory(root.resolve("game"), ".aster-pack-")
                    : Files.createTempFile(root.resolve("game"), ".aster-pack-", ".tmp");
            try {
                if (Files.isDirectory(source)) {
                    // Reject selecting a parent of the destination (for example the whole game directory).
                    if (directory.toRealPath().startsWith(source.toRealPath())) throw new IOException("请选择具体的材质包文件夹");
                    try (var walk = Files.walk(source)) {
                        for (Path from : walk.toList()) {
                            Path to = temporary.resolve(source.relativize(from));
                            if (Files.isDirectory(from)) Files.createDirectories(to);
                            else Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                        }
                    }
                } else Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary, destination);
                imported.add(destination.getFileName().toString());
            } finally { ClientUpdater.deleteTree(temporary); }
        }
        if (enable) changeSelection(imported, true);
        return imported;
    }
    private static void validate(Path source) throws IOException {
        try {
            JsonObject metadata;
            if (Files.isDirectory(source)) {
                if (!Files.isRegularFile(source.resolve("pack.mcmeta"))) throw new IOException("文件夹最外层没有 pack.mcmeta，请选择具体的材质包目录");
                metadata = ClientUpdater.read(source.resolve("pack.mcmeta"));
            } else {
                if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) throw new IOException("请选择 Java 版 ZIP 材质包或已解压的文件夹");
                try (ZipFile zip = new ZipFile(source.toFile())) {
                    var entry = zip.getEntry("pack.mcmeta");
                    if (entry == null) throw new IOException("ZIP 最外层没有 pack.mcmeta；若是整合压缩包，请先解压并选择里面的材质包");
                    try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                        metadata = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                }
            }
            if (!metadata.has("pack") || !metadata.get("pack").isJsonObject()) throw new IOException("pack.mcmeta 缺少有效的 pack 信息");
        } catch (IOException | IllegalStateException | JsonParseException error) {
            throw new IOException(source.getFileName() + "：" + error.getMessage(), error);
        }
    }
    void setEnabled(String name, boolean enable) throws IOException {
        if (managed().contains(name.toLowerCase(Locale.ROOT))) throw new IOException("内置材质包请在游戏中管理");
        Path pack = ClientUpdater.safe(directory(), name);
        if (!Files.exists(pack)) throw new IOException("材质包已被移动，请刷新列表");
        if (enable) validate(pack);
        changeSelection(List.of(name), enable);
    }
    private Map<String, String> readOptions() throws IOException {
        Path path = root.resolve("game/options.txt");
        if (!Files.exists(path)) path = root.resolve("app/game-content/options.txt");
        Map<String, String> options = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int colon = line.indexOf(':');
            if (colon > 0) options.put(line.substring(0, colon), line.substring(colon + 1));
        }
        return options;
    }
    private Set<String> selected(Map<String, String> options) {
        Set<String> result = new LinkedHashSet<>();
        for (var value : JsonParser.parseString(options.getOrDefault("resourcePacks", "[]")).getAsJsonArray()) result.add(value.getAsString());
        return result;
    }
    private void changeSelection(List<String> names, boolean enable) throws IOException {
        Map<String, String> options = readOptions();
        List<String> enabled = new ArrayList<>(selected(options));
        Set<String> managed = managed();
        for (String name : names) {
            String id = "file/" + name;
            if (!enable) { enabled.remove(id); continue; }
            if (enabled.contains(id)) continue;
            // Keep new personal textures below the server's UI/music packs; in-game ordering stays editable.
            int insertion = enabled.size();
            for (int i = 0; i < enabled.size(); i++) {
                String existing = enabled.get(i);
                if (existing.startsWith("file/") && managed.contains(existing.substring(5).toLowerCase(Locale.ROOT))) { insertion = i; break; }
            }
            enabled.add(insertion, id);
        }
        JsonArray array = new JsonArray(); enabled.forEach(array::add); options.put("resourcePacks", array.toString());
        Path target = root.resolve("game/options.txt"); Files.createDirectories(target.getParent());
        List<String> lines = new ArrayList<>(); options.forEach((key, value) -> lines.add(key + ":" + value));
        Files.write(target, lines, StandardCharsets.UTF_8);
    }
}
