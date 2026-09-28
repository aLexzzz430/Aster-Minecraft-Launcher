package cn.aster.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

final class ClientMaintenance {
    static boolean automatic(Path root) throws IOException {
        Properties p = settings(root);
        return Boolean.parseBoolean(p.getProperty("automaticUpdates", "true"));
    }
    static void automatic(Path root, boolean value) throws IOException {
        Properties p = settings(root); p.setProperty("automaticUpdates", Boolean.toString(value));
        try (var out = Files.newOutputStream(root.resolve("client-settings.properties"))) { p.store(out, "Aster client settings"); }
    }
    private static Properties settings(Path root) throws IOException {
        Properties p = new Properties();
        if (Files.exists(root.resolve("client-settings.properties")))
            try (var input = Files.newInputStream(root.resolve("client-settings.properties"))) { p.load(input); }
        return p;
    }
    static void migrate(Path root, Path target, Consumer<String> progress) throws IOException {
        root = root.toRealPath(); target = target.toAbsolutePath().normalize();
        if (target.startsWith(root) || root.startsWith(target)) throw new IOException("请选择独立的 AsterRPG 安装文件夹");
        if (Files.exists(target)) {
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) throw new IOException("目标文件夹不为空，请选择空文件夹");
            }
        }
        if (Files.exists(root.resolve("updates/ready.ini"))) throw new IOException("请先应用已下载的更新，再迁移客户端");
        List<Path> sources;
        try (var walk = Files.walk(root)) {
            Path base = root;
            sources = walk.filter(Files::isRegularFile).filter(p -> {
                String relative = base.relativize(p).toString().replace('\\', '/');
                return !relative.equals("client.lock") && !relative.startsWith("updates/") &&
                        !relative.startsWith("app.next/") && !relative.startsWith("app.previous/") &&
                        !relative.endsWith("aster-auth-ticket.txt");
            }).toList();
        }
        long total = 0; for (Path p : sources) total += Files.size(p);
        Files.createDirectories(target);
        if (Files.getFileStore(target).getUsableSpace() < total + 32 * 1024 * 1024L)
            throw new IOException("目标磁盘空间不足，需要约 " + ClientUpdater.mib(total) + " MiB");
        long copied = 0;
        try {
            for (Path source : sources) {
                Path to = target.resolve(root.relativize(source)); Files.createDirectories(to.getParent());
                Files.copy(source, to, StandardCopyOption.COPY_ATTRIBUTES);
                copied += Files.size(source);
                progress.accept("迁移 " + ClientUpdater.mib(copied) + " / " + ClientUpdater.mib(total) + " MiB");
            }
            // Windows profile APIs read a Unicode INI; the bootstrap completes the move after Java exits.
            String ini = Files.readString(target.resolve("client.ini"), StandardCharsets.UTF_16LE).replace("\uFEFF", "");
            Files.writeString(target.resolve("client.ini"), "\uFEFF" + ini + "\r\n[migration]\r\nfrom=" + root + "\r\n", StandardCharsets.UTF_16LE);
        } catch (IOException failure) {
            ClientUpdater.deleteTree(target);
            throw failure;
        }
    }
}
