package cn.aster.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One shared definition drives the UI, graphics configuration and JVM launch plan. */
enum PerformancePreset {
    SMOOTH("smooth"), BALANCED("balanced"), QUALITY("quality");

    private static final JsonObject CONFIG = load();
    final String id;
    PerformancePreset(String id) { this.id = id; }

    private static JsonObject load() {
        try (var input = PerformancePreset.class.getResourceAsStream("/performance-presets.json")) {
            if (input == null) throw new IllegalStateException("客户端缺少性能预设");
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    private JsonObject definition() { return CONFIG.getAsJsonObject("presets").getAsJsonObject(id); }
    static String revision() { return CONFIG.get("revision").getAsString(); }
    String title() { return definition().get("name").getAsString(); }
    String audience() { return definition().get("audience").getAsString(); }
    String summary() { return definition().get("summary").getAsString(); }
    int memoryMb() { return definition().get("memoryMb").getAsInt(); }
    JsonObject options() { return definition().getAsJsonObject("options").deepCopy(); }
    JsonObject sodium() { return definition().getAsJsonObject("sodium").deepCopy(); }
    JsonObject horizon() { return definition().getAsJsonObject("horizon").deepCopy(); }
    List<String> jvmArguments() {
        List<String> result = new ArrayList<>();
        for (var value : definition().getAsJsonArray("jvmArguments")) result.add(value.getAsString());
        return List.copyOf(result);
    }
    static PerformancePreset fromId(String id) {
        for (var preset : values()) if (preset.id.equals(id)) return preset;
        throw new IllegalArgumentException("未知性能档位：" + id);
    }
}
