package cn.aster.launcher;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Exports the same vector brand used by Swing for the EXE icon and preparation header. */
public final class ExportLauncherBrand {
    public static void main(String[] args) throws Exception {
        Path destination = Path.of(args[0]); Files.createDirectories(destination);
        int[] sizes = {16, 32, 48, 64, 256};
        var images = new ArrayList<byte[]>();
        for (int size : sizes) {
            BufferedImage icon = LauncherTheme.appIcon(size);
            int maskStride = ((size + 31) / 32) * 4;
            ByteBuffer dib = ByteBuffer.allocate(40 + size * size * 4 + maskStride * size).order(ByteOrder.LITTLE_ENDIAN);
            dib.putInt(40).putInt(size).putInt(size * 2).putShort((short) 1).putShort((short) 32);
            dib.putInt(0).putInt(size * size * 4).putInt(0).putInt(0).putInt(0).putInt(0);
            for (int y = size - 1; y >= 0; y--)
                for (int x = 0; x < size; x++) dib.putInt(icon.getRGB(x, y));
            for (int y = size - 1; y >= 0; y--) {
                byte[] row = new byte[maskStride];
                for (int x = 0; x < size; x++)
                    if ((icon.getRGB(x, y) >>> 24) < 128) row[x / 8] |= (byte) (0x80 >>> (x % 8));
                dib.put(row);
            }
            images.add(dib.array());
        }
        int offset = 6 + sizes.length * 16;
        ByteBuffer ico = ByteBuffer.allocate(offset + images.stream().mapToInt(x -> x.length).sum()).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            ico.put((byte) sizes[i]).put((byte) sizes[i]).put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        images.forEach(ico::put);
        Files.write(destination.resolve("aster.ico"), ico.array());
        ImageIO.write(LauncherTheme.appIcon(256), "png", destination.resolve("aster-icon.png").toFile());
        BufferedImage header = new BufferedImage(150, 57, BufferedImage.TYPE_INT_RGB);
        Graphics2D raw = header.createGraphics(), g = LauncherTheme.graphics(raw); raw.dispose();
        g.setColor(LauncherTheme.BACKGROUND); g.fillRect(0, 0, 150, 57);
        g.setColor(LauncherTheme.GOLD); LauncherTheme.star(g, 15, 28, 9);
        g.setFont(LauncherTheme.tracked(20, .06f)); g.setColor(LauncherTheme.TEXT);
        g.drawString("ASTER", 36, 36); g.dispose();
        ImageIO.write(header, "bmp", destination.resolve("header.bmp").toFile());
    }
}
