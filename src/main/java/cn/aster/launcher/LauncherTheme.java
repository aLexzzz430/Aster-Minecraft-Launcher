package cn.aster.launcher;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.awt.font.TextAttribute;

/** Shared palette, typography and small vector controls; no OS-dependent Swing decoration. */
final class LauncherTheme {
    static final Color BACKGROUND = new Color(13, 20, 25);
    static final Color FIELD = new Color(23, 32, 38);
    static final Color LINE = new Color(47, 59, 65);
    static final Color TEXT = new Color(240, 241, 235);
    static final Color MUTED = new Color(150, 164, 171);
    static final Color GOLD = new Color(221, 190, 136);
    static final Color ERROR_COLOR = new Color(255, 154, 139);
    private static final Font FACE = loadFont();
    static final BufferedImage ART = loadArt();

    private static Font loadFont() {
        try (InputStream in = LauncherTheme.class.getResourceAsStream("/ui/NotoSansSC-Regular.otf")) {
            if (in == null) throw new IllegalStateException("缺少启动器字体");
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    private static BufferedImage loadArt() {
        try (InputStream in = LauncherTheme.class.getResourceAsStream("/ui/south-bay-key-art.png")) {
            if (in == null) throw new IllegalStateException("缺少启动器宣传图");
            BufferedImage art = ImageIO.read(in);
            if (art == null) throw new IllegalStateException("无法读取启动器宣传图");
            return art;
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    static Font font(float size) { return FACE.deriveFont(Font.PLAIN, size); }
    static Font bold(float size) { return FACE.deriveFont(Font.BOLD, size); }
    static Font tracked(float size, float tracking) {
        return bold(size).deriveFont(Map.of(TextAttribute.TRACKING, tracking));
    }
    static BufferedImage appIcon(int size) {
        BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D raw = icon.createGraphics(), g = graphics(raw);
        raw.dispose();
        g.setColor(BACKGROUND); g.fillRoundRect(0, 0, size, size, size / 4, size / 4);
        g.setColor(GOLD); star(g, size / 2.0, size / 2.0, size * .35);
        g.setColor(new Color(68, 76, 76));
        g.setStroke(new BasicStroke(Math.max(1, size / 80f)));
        g.drawOval(size / 6, size / 6, size * 2 / 3, size * 2 / 3);
        g.dispose();
        return icon;
    }
    static Graphics2D graphics(Graphics raw) {
        Graphics2D g = (Graphics2D) raw.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }
    static Color mix(Color a, Color b, float t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
    static void star(Graphics2D g, double x, double y, double size) {
        Path2D p = new Path2D.Double();
        p.moveTo(x, y - size); p.lineTo(x + size * .24, y - size * .24);
        p.lineTo(x + size, y); p.lineTo(x + size * .24, y + size * .24);
        p.lineTo(x, y + size); p.lineTo(x - size * .24, y + size * .24);
        p.lineTo(x - size, y); p.lineTo(x - size * .24, y - size * .24);
        p.closePath(); g.fill(p);
    }
    static void icon(Graphics2D g, String kind, int x, int y, int size) {
        Graphics2D p = (Graphics2D) g.create();
        p.translate(x, y); p.scale(size / 24.0, size / 24.0);
        p.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case "arrow" -> { p.drawLine(4, 12, 20, 12); p.drawLine(14, 6, 20, 12); p.drawLine(14, 18, 20, 12); }
            case "back" -> { p.drawLine(4, 12, 20, 12); p.drawLine(4, 12, 10, 6); p.drawLine(4, 12, 10, 18); }
            case "close" -> { p.drawLine(6, 6, 18, 18); p.drawLine(18, 6, 6, 18); }
            case "minus" -> p.drawLine(5, 12, 19, 12);
            case "eye", "eye-off" -> {
                Path2D eye = new Path2D.Double(); eye.moveTo(2, 12);
                eye.curveTo(7, 4, 17, 4, 22, 12); eye.curveTo(17, 20, 7, 20, 2, 12); p.draw(eye);
                p.drawOval(9, 9, 6, 6);
                if (kind.equals("eye-off")) p.drawLine(3, 3, 21, 21);
            }
            case "settings" -> {
                for (int row = 0; row < 3; row++) {
                    int y1 = 5 + row * 7, at = row == 1 ? 15 : 8;
                    p.drawLine(3, y1, at - 3, y1); p.drawLine(at + 3, y1, 21, y1); p.drawOval(at - 3, y1 - 3, 6, 6);
                }
            }
            case "log" -> { p.drawRoundRect(5, 2, 14, 20, 2, 2); p.drawLine(9, 8, 15, 8); p.drawLine(9, 12, 15, 12); p.drawLine(9, 16, 13, 16); }
            case "chevron" -> { p.drawLine(8, 5, 15, 12); p.drawLine(15, 12, 8, 19); }
            case "warning" -> { p.drawOval(3, 3, 18, 18); p.drawLine(12, 7, 12, 13); p.fillOval(11, 16, 2, 2); }
        }
        p.dispose();
    }
}
