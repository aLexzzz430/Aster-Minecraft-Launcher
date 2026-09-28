package cn.aster.launcher;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Renders the bundled game's default Steve head as an explicitly labeled skin preview. */
final class MinecraftAvatar {
    static BufferedImage defaultHead(Path root, String minecraftVersion) throws IOException {
        Path jar = root.resolve("app/minecraft/versions").resolve(minecraftVersion)
                .resolve(minecraftVersion + ".jar");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("assets/minecraft/textures/entity/player/wide/steve.png");
            if (entry == null) throw new IOException("客户端缺少默认皮肤资源");
            BufferedImage sheet;
            try (var input = zip.getInputStream(entry)) { sheet = ImageIO.read(input); }
            if (sheet == null || sheet.getWidth() < 48 || sheet.getHeight() < 16)
                throw new IOException("默认皮肤资源无法读取");
            return head(sheet);
        }
    }

    static BufferedImage head(BufferedImage sheet) throws IOException {
        if (sheet == null || sheet.getWidth() != 64 || (sheet.getHeight() != 64 && sheet.getHeight() != 32))
            throw new IOException("皮肤图片尺寸须为 64×64 或 64×32");
        BufferedImage head = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = head.createGraphics();
        g.drawImage(sheet, 0, 0, 8, 8, 8, 8, 16, 16, null);
        if (sheet.getHeight() == 64) g.drawImage(sheet, 0, 0, 8, 8, 40, 8, 48, 16, null);
        g.dispose();
        return head;
    }
}
