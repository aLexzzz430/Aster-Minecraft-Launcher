package cn.aster.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.sql.DriverManager;

/** Standalone integration checks for managed updates and Java argument-file parsing. */
public final class GameInstallationTest {
    private static void expect(boolean result, String description) {
        if (!result) throw new AssertionError(description);
    }
    private static JsonArray array(String... items) {
        JsonArray result = new JsonArray(); for (String item : items) result.add(item); return result;
    }
    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent()); Files.writeString(path, value, StandardCharsets.UTF_8);
    }
    private static JsonObject manifest(String release, String mod) {
        JsonObject result = new JsonObject();
        result.addProperty("release", release); result.addProperty("versionName", "AsterRPG-1.21.11");
        result.addProperty("assetIndex", "29"); result.addProperty("server", "189.24.77.244:25565");
        result.addProperty("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient");
        result.addProperty("javaPath", "runtime/bin/javaw.exe");
        result.add("classpath", array("minecraft/client.jar", "minecraft/fabric.jar"));
        result.add("jvmArguments", array("-cp", "${classpath}", "-Djava.library.path=${natives_directory}"));
        result.add("gameArguments", array("--username", "${auth_player_name}", "--gameDir", "${game_directory}",
                "--quickPlayMultiplayer", "${quickPlayMultiplayer}"));
        result.add("managedFiles", array(mod)); result.add("defaultFiles", array("options.txt"));
        return result;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("Aster RPG 中文路径 ");
        Path app = root.resolve("app");
        write(app.resolve("client-manifest.json"), manifest("1.0.0.1", "mods/aster-old.jar").toString());
        write(app.resolve("game-content/mods/aster-old.jar"), "release one");
        write(app.resolve("game-content/options.txt"), "default options");
        GameInstallation first = new GameInstallation(root); first.prepare();
        expect(Files.readString(root.resolve("game/options.txt")).equals("default options"), "first launch applies defaults");
        write(root.resolve("game/options.txt"), "player custom options");
        write(root.resolve("game/mods/user-extra.jar"), "player file");
        write(app.resolve("client-manifest.json"), manifest("1.0.0.2", "mods/aster-new.jar").toString());
        write(app.resolve("game-content/mods/aster-new.jar"), "release two");
        GameInstallation update = new GameInstallation(root); update.prepare();
        expect(!Files.exists(root.resolve("game/mods/aster-old.jar")), "update removes old managed mod");
        expect(Files.readString(root.resolve("game/mods/aster-new.jar")).equals("release two"), "update installs new managed mod");
        expect(Files.readString(root.resolve("game/options.txt")).equals("player custom options"), "update preserves player settings");
        expect(Files.exists(root.resolve("game/mods/user-extra.jar")), "update preserves other player files");
        update.prepare();
        expect(Files.readString(root.resolve("game/options.txt")).equals("player custom options"), "repeat launch preserves settings");
        List<String> plan = update.arguments("PartyPlayer", 6144);
        expect(plan.contains("-Xmx6144M") && plan.contains("PartyPlayer"), "chosen identity and memory reach game process");
        expect(plan.contains("-XX:+UseZGC") && plan.contains("-XX:+ZGenerational"), "client uses Java 21 generational ZGC");
        expect(plan.contains("189.24.77.244:25565") && plan.stream().noneMatch(s -> s.contains("${")), "formal-server launch plan is fully expanded");
        expect(plan.stream().allMatch(s -> s.chars().allMatch(c -> c < 128)), "Windows argument file is independent of user profile encoding");
        Path echoArgs = root.resolve("arguments with space.txt");
        List<String> values = List.of("C:\\Users\\张 三\\AppData\\Local\\AsterRPG", "two words", "quote\"inside", "");
        var echo = new java.util.ArrayList<>(List.of("-cp", System.getProperty("java.class.path"), ArgumentEcho.class.getName()));
        echo.addAll(values);
        write(echoArgs, GameInstallation.argumentFile(echo));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "@" + echoArgs)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        expect(process.waitFor() == 0, "Java argument-file invocation succeeds: " + output);
        expect(output.equals(String.join("\n", values) + "\n"), "spaces, Unicode, quotes and backslashes survive argument-file parsing");
        checkTuning(root, app);
        checkMusicPack();
        checkPerformancePresets();
        System.out.println("PASS managed updates, launch arguments, graphics migration, DH/music preservation and performance presets/preferences");
    }

    private static void checkPerformancePresets() throws Exception {
        Path root = Files.createTempDirectory("Aster performance ");
        Path app = root.resolve("app");
        write(app.resolve("client-manifest.json"), manifest("3.0.0.1", "mods/game.jar").toString());
        write(app.resolve("game-content/mods/game.jar"), "game");
        write(app.resolve("game-content/options.txt"), "renderDistance:16\nkey_key.forward:key.keyboard.i\nsoundCategory_music:0.3\nresourcePacks:[\"vanilla\",\"file/AsterMusic.zip\"]\n");
        GameInstallation installation = new GameInstallation(root);
        installation.prepare();
        write(root.resolve("game/config/sodium-options.json"), "{\"quality\":{\"pixel_filtering_mode\":\"NEAREST\"},\"notifications\":{\"has_seen_donation_prompt\":true}}");
        write(root.resolve("game/config/aster_horizon.json"), "{\"enabled\":false,\"dataFile\":\"custom/world.ahz\"}");
        installation.applyPerformancePreset(PerformancePreset.SMOOTH, false);
        String smooth = Files.readString(root.resolve("game/options.txt"));
        expect(smooth.contains("renderDistance:4") && smooth.contains("simulationDistance:5")
                && smooth.contains("maxFps:240") && smooth.contains("enableVsync:false"),
                "smooth preset applies valid distances and limiter headroom for its 200 FPS target");
        expect(smooth.contains("key_key.forward:key.keyboard.i") && smooth.contains("soundCategory_music:0.3") && smooth.contains("file/AsterMusic.zip"),
                "performance changes preserve keys, audio and music pack");
        var sodium = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("game/config/sodium-options.json"))).getAsJsonObject();
        expect(sodium.getAsJsonObject("performance").get("chunk_builder_threads").getAsInt() == 1
                && sodium.getAsJsonObject("notifications").get("has_seen_donation_prompt").getAsBoolean()
                && sodium.getAsJsonObject("quality").get("pixel_filtering_mode").getAsString().equals("NEAREST"),
                "Sodium tuning merges with unrelated existing preferences");
        var horizonFile = root.resolve("game/config/aster_horizon.json");
        var horizon = com.google.gson.JsonParser.parseString(Files.readString(horizonFile)).getAsJsonObject();
        expect(horizon.get("quality").getAsString().equals("low") && horizon.get("cacheBudgetMb").getAsInt() == 64
                && !horizon.get("enabled").getAsBoolean() && horizon.get("dataFile").getAsString().equals("custom/world.ahz"),
                "Horizon receives its hardware budget while preserving enablement and data selection");
        horizon.addProperty("quality", "ultra");
        write(horizonFile, horizon.toString());
        write(root.resolve("game/options.txt"), smooth.replace("renderDistance:4", "renderDistance:7"));
        installation.applyPerformancePreset(PerformancePreset.SMOOTH, false);
        expect(Files.readString(root.resolve("game/options.txt")).contains("renderDistance:7"), "same profile preserves later in-game adjustments");
        expect(Files.readString(horizonFile).contains("ultra"), "same profile preserves Horizon in-game adjustments");
        installation.applyPerformancePreset(PerformancePreset.SMOOTH, true);
        expect(Files.readString(root.resolve("game/options.txt")).contains("renderDistance:4"), "explicit restore reapplies the chosen profile");
        expect(com.google.gson.JsonParser.parseString(Files.readString(horizonFile)).getAsJsonObject().get("quality").getAsString().equals("low"),
                "explicit restore reapplies Horizon defaults");
        installation.applyPerformancePreset(PerformancePreset.QUALITY, false);
        String quality = Files.readString(root.resolve("game/options.txt"));
        expect(quality.contains("renderDistance:10") && quality.contains("entityShadows:true") && quality.contains("maxFps:240"),
                "switching presets restores higher-detail options as a coherent set");
        for (var preset : PerformancePreset.values()) {
            var args = installation.arguments("Traveler", preset.memoryMb(), preset);
            expect(args.contains("-Xmx" + preset.memoryMb() + "M"), "profile memory reaches game JVM");
            expect(args.contains("-XX:+UseG1GC") == (preset == PerformancePreset.SMOOTH)
                    && args.contains("-XX:+UseZGC") == (preset != PerformancePreset.SMOOTH), "collector follows selected hardware tier");
        }
        write(root.resolve("launcher.properties"), "username=LegacyPlayer\nmemoryMb=6144\n");
        var legacy = LauncherPreferences.load(root);
        expect(legacy.preset() == PerformancePreset.BALANCED && legacy.memoryMb() == 6144 && legacy.username().equals("LegacyPlayer"),
                "old launcher preferences retain identity and custom memory");
        var chosen = new LauncherPreferences("Traveler", PerformancePreset.SMOOTH, 3072, true);
        chosen.save(root);
        expect(LauncherPreferences.load(root).equals(chosen), "profile and pending default reset persist without login");
    }

    private static void checkMusicPack() throws Exception {
        Path root = Files.createTempDirectory("Aster music update ");
        Path app = root.resolve("app");
        String pack = "resourcepacks/AsterMusic.zip";
        JsonObject release = manifest("2.0.0.1", pack);
        release.add("enableResourcePacks", array("file/AsterMusic.zip"));
        write(app.resolve("client-manifest.json"), release.toString());
        write(app.resolve("game-content/" + pack), "music one");
        write(app.resolve("game-content/options.txt"), "resourcePacks:[\"vanilla\",\"file/AsterUI.zip\"]\nsoundCategory_music:0.65\n");
        new GameInstallation(root).prepare();
        expect(Files.readString(root.resolve("game/options.txt")).contains("\"file/AsterUI.zip\",\"file/AsterMusic.zip\""),
                "fresh install enables soundtrack after existing packs");

        // Simulate an older installed client with custom packs and a muted music preference.
        Files.delete(root.resolve("game/aster-managed.json"));
        write(root.resolve("game/options.txt"), "resourcePacks:[\"vanilla\",\"file/custom.zip\"]\nsoundCategory_music:0.0\nkey_key.forward:key.keyboard.i\n");
        new GameInstallation(root).prepare();
        String upgraded = Files.readString(root.resolve("game/options.txt"));
        expect(upgraded.contains("[\"vanilla\",\"file/custom.zip\",\"file/AsterMusic.zip\"]")
                && upgraded.contains("soundCategory_music:0.0") && upgraded.contains("key_key.forward:key.keyboard.i"),
                "upgrade enables new soundtrack while retaining user packs, keys and audio volume");

        // Disabling music is a player choice that future client releases must retain.
        write(root.resolve("game/options.txt"), "resourcePacks:[\"vanilla\",\"file/custom.zip\"]\nsoundCategory_music:0.2\n");
        release.addProperty("release", "2.0.0.2");
        write(app.resolve("client-manifest.json"), release.toString());
        write(app.resolve("game-content/" + pack), "music two");
        new GameInstallation(root).prepare();
        String later = Files.readString(root.resolve("game/options.txt"));
        expect(!later.contains("file/AsterMusic.zip") && later.contains("soundCategory_music:0.2"),
                "later update does not reenable a deliberately disabled soundtrack");
        expect(Files.readString(root.resolve("game/" + pack)).equals("music two"), "disabled managed pack still receives its update");
    }

    private static void checkTuning(Path root, Path app) throws Exception {
        String dbPath = "Distant_Horizons_server_data/aster-live/world/DistantHorizons.sqlite";
        Path seed = app.resolve("game-content").resolve(dbPath);
        Files.createDirectories(seed.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + seed);
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE FullData (DetailLevel INT, PosX INT, PosZ INT, Data TEXT, LastModifiedUnixDateTime BIGINT, PRIMARY KEY(DetailLevel,PosX,PosZ))");
            sql.execute("INSERT INTO FullData VALUES (0,1,1,'bundled',20),(0,2,2,'bundled',20)");
        }
        JsonObject manifest = manifest("1.0.0.3", "mods/aster-new.jar");
        manifest.addProperty("tuningRevision", "balanced-1");
        JsonObject options = new JsonObject(); options.addProperty("renderDistance", "8");
        manifest.add("optionOverrides", options);
        JsonObject database = new JsonObject(); database.addProperty("path", dbPath); database.addProperty("revision", "seed-1");
        JsonArray seeds = new JsonArray(); seeds.add(database); manifest.add("seedFiles", seeds);
        write(app.resolve("client-manifest.json"), manifest.toString());
        write(root.resolve("game/options.txt"), "renderDistance:32\nkey_key.forward:key.keyboard.i\nsoundCategory_master:0.6\n");
        new GameInstallation(root).prepare();
        String tuned = Files.readString(root.resolve("game/options.txt"));
        expect(tuned.contains("renderDistance:8") && tuned.contains("key_key.forward:key.keyboard.i")
                && tuned.contains("soundCategory_master:0.6"), "graphics tuning keeps keys and audio");
        Path playerDb = root.resolve("game").resolve(dbPath);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + playerDb);
             var sql = connection.createStatement()) {
            sql.execute("UPDATE FullData SET Data='newer player view', LastModifiedUnixDateTime=50 WHERE PosX=1");
            sql.execute("UPDATE FullData SET Data='older player view', LastModifiedUnixDateTime=5 WHERE PosX=2");
            sql.execute("INSERT INTO FullData VALUES (0,3,3,'extra exploration',40)");
        }
        database.addProperty("revision", "seed-2"); manifest.addProperty("release", "1.0.0.4");
        write(app.resolve("client-manifest.json"), manifest.toString());
        write(root.resolve("game/options.txt"), "renderDistance:10\nkey_key.forward:key.keyboard.i\n");
        new GameInstallation(root).prepare();
        expect(Files.readString(root.resolve("game/options.txt")).contains("renderDistance:10"), "same tuning revision does not reset player graphics");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + playerDb);
             var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT Data FROM FullData ORDER BY PosX")) {
            expect(rows.next() && rows.getString(1).equals("newer player view"), "newer player terrain survives seed update");
            expect(rows.next() && rows.getString(1).equals("bundled"), "newer shipped terrain replaces older terrain");
            expect(rows.next() && rows.getString(1).equals("extra exploration") && !rows.next(), "unrelated player exploration survives");
        }
    }
    public static final class ArgumentEcho {
        public static void main(String[] args) { for (String arg : args) System.out.println(arg); }
    }
}
