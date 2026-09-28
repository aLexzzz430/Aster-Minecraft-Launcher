package cn.aster.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Installs the matching-world DH 3.2 seed without erasing newer player exploration. */
final class DistantHorizonSeed {
    private DistantHorizonSeed() {}

    static void install(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) {
            // The bundled database is a closed SQLite snapshot with no companion WAL.
            Path preparing = target.resolveSibling(target.getFileName() + ".installing");
            Files.copy(source, preparing, StandardCopyOption.REPLACE_EXISTING);
            Files.move(preparing, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + target);
             var attach = database.prepareStatement("ATTACH DATABASE ? AS aster_seed")) {
            attach.setString(1, source.toString());
            attach.execute();
            List<String> columns = new ArrayList<>();
            try (var statement = database.createStatement();
                 var result = statement.executeQuery("PRAGMA aster_seed.table_info(FullData)")) {
                while (result.next()) columns.add(result.getString("name"));
            }
            List<String> names = columns.stream().map(DistantHorizonSeed::quote).toList();
            List<String> updates = columns.stream()
                    .filter(name -> !List.of("DetailLevel", "PosX", "PosZ").contains(name))
                    .map(name -> quote(name) + "=excluded." + quote(name)).toList();
            database.setAutoCommit(false);
            try (var statement = database.createStatement()) {
                statement.executeUpdate("INSERT INTO main.FullData (" + String.join(",", names)
                        + ") SELECT " + String.join(",", names) + " FROM aster_seed.FullData WHERE true"
                        + " ON CONFLICT(DetailLevel,PosX,PosZ) DO UPDATE SET " + String.join(",", updates)
                        + " WHERE excluded.LastModifiedUnixDateTime > FullData.LastModifiedUnixDateTime");
                database.commit();
            } catch (SQLException failure) {
                database.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("无法更新远景数据，请关闭已打开的游戏后重试：" + failure.getMessage(), failure);
        }
    }

    private static String quote(String identifier) { return '"' + identifier.replace("\"", "\"\"") + '"'; }
}
