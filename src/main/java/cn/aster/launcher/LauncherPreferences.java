package cn.aster.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Player choices persist without needing an authentication request. Passwords never enter this file. */
record LauncherPreferences(String username, PerformancePreset preset, int memoryMb, boolean resetPreset) {
    static final List<Integer> MEMORY_CHOICES = List.of(2048, 3072, 4096, 6144, 8192);

    static LauncherPreferences load(Path root) throws IOException {
        Properties settings = new Properties();
        Path path = root.resolve("launcher.properties");
        if (Files.exists(path)) try (var input = Files.newInputStream(path)) { settings.load(input); }
        var preset = PerformancePreset.fromId(settings.getProperty("performancePreset", "balanced"));
        int memory = Integer.parseInt(settings.getProperty("memoryMb", String.valueOf(preset.memoryMb())));
        if (!MEMORY_CHOICES.contains(memory)) throw new IOException("游戏内存设置无效，请重新选择");
        return new LauncherPreferences(settings.getProperty("username", ""), preset, memory,
                Boolean.parseBoolean(settings.getProperty("resetPreset", "false")));
    }

    void save(Path root) throws IOException {
        Files.createDirectories(root);
        Properties settings = new Properties();
        settings.setProperty("username", username);
        settings.setProperty("performancePreset", preset.id);
        settings.setProperty("memoryMb", String.valueOf(memoryMb));
        settings.setProperty("resetPreset", String.valueOf(resetPreset));
        try (var output = Files.newOutputStream(root.resolve("launcher.properties"))) {
            settings.store(output, "AsterRPG launcher preferences");
        }
    }
}
