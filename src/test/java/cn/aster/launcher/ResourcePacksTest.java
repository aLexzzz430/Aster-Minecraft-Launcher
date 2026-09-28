package cn.aster.launcher;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class ResourcePacksTest {
    static void expect(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    static void write(Path path, String text) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path, text); }
    static void pack(Path file, String metadataPath) throws Exception {
        Files.createDirectories(file.getParent());
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(metadataPath));
            out.write("{\"pack\":{\"min_format\":[75,0],\"max_format\":[75,0],\"description\":\"玩家材质\"}}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry(); out.putNextEntry(new ZipEntry("assets/minecraft/textures/example.txt")); out.write("player texture".getBytes()); out.closeEntry();
        }
    }
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("Aster resource pack 中文 "), root = base.resolve("client"), source = base.resolve("downloads");
        try {
            String manifest = """
                {"release":"1.0.0.1","managedFiles":["resourcepacks/AsterUI.zip"],"defaultFiles":["options.txt"],"enableResourcePacks":[]}
                """;
            write(root.resolve("app/client-manifest.json"), manifest);
            write(root.resolve("app/game-content/options.txt"), "resourcePacks:[\"vanilla\",\"file/AsterUI.zip\"]\nincompatibleResourcePacks:[]\nsoundCategory_music:0.27\nkey_key.forward:key.keyboard.i\n");
            pack(root.resolve("app/game-content/resourcepacks/AsterUI.zip"), "pack.mcmeta");
            pack(source.resolve("我的风景.zip"), "pack.mcmeta");
            ResourcePacks packs = new ResourcePacks(root);
            var imported = packs.importPacks(List.of(source.resolve("我的风景.zip")), true, s -> {});
            expect(imported.equals(List.of("我的风景.zip")), "Chinese ZIP keeps its name and imports");
            expect(packs.list().getFirst().enabled(), "import before first login enables the personal pack");
            String options = Files.readString(root.resolve("game/options.txt"));
            expect(options.contains("soundCategory_music:0.27") && options.contains("key.keyboard.i"), "import preserves audio and key bindings");
            expect(options.indexOf("file/我的风景.zip") < options.indexOf("file/AsterUI.zip"), "default ordering preserves server UI priority");
            new GameInstallation(root).prepare();
            expect(Files.exists(packs.directory().resolve("我的风景.zip")) && packs.list().size() == 1, "first game preparation preserves custom pack and hides managed UI pack");
            var again = packs.importPacks(List.of(source.resolve("我的风景.zip")), false, s -> {});
            expect(again.equals(List.of("我的风景 (2).zip")), "same-name import keeps the earlier file");
            expect(packs.list().stream().anyMatch(p -> p.name().equals(again.getFirst()) && !p.enabled()), "import-only choice does not enable the pack");
            packs.setEnabled("我的风景.zip", false);
            expect(packs.list().stream().noneMatch(ResourcePacks.Pack::enabled), "pack can be disabled without deleting it");
            packs.setEnabled("我的风景.zip", true);
            write(source.resolve("文件夹材质/pack.mcmeta"), "{\"pack\":{\"pack_format\":75}} ");
            write(source.resolve("文件夹材质/assets/minecraft/test.txt"), "folder content");
            packs.importPacks(List.of(source.resolve("文件夹材质")), true, s -> {});
            expect(Files.readString(packs.directory().resolve("文件夹材质/assets/minecraft/test.txt")).equals("folder content"), "unpacked resource pack imports with subdirectories");
            pack(source.resolve("AsterUI.zip"), "pack.mcmeta");
            expect(packs.importPacks(List.of(source.resolve("AsterUI.zip")), true, s -> {}).equals(List.of("AsterUI (2).zip")), "personal import never replaces a managed pack");
            int before = packs.list().size();
            packs.importPacks(List.of(packs.directory().resolve("我的风景.zip")), true, s -> {});
            expect(before == packs.list().size(), "import from the installed resource directory does not duplicate files");
            pack(source.resolve("套装.zip"), "wrapper/pack.mcmeta");
            try { packs.importPacks(List.of(source.resolve("套装.zip")), true, s -> {}); throw new AssertionError("nested metadata should show actionable error"); }
            catch (java.io.IOException expected) { expect(expected.getMessage().contains("最外层"), "wrapped archive explains how to select the correct pack"); }
            write(root.resolve("app/client-manifest.json"), manifest.replace("1.0.0.1", "1.0.0.2"));
            new GameInstallation(root).prepare();
            expect(packs.list().size() == before && packs.list().stream().anyMatch(p -> p.name().equals("我的风景.zip") && p.enabled()), "managed release upgrade preserves personal files and activation");
            System.out.println("PASS ZIP/folder import, Chinese names, enable/disable, original settings, conflicts, metadata feedback and release-upgrade preservation");
        } finally { ClientUpdater.deleteTree(base); }
    }
}
