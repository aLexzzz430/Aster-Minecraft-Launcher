package cn.aster.launcher;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;

import static cn.aster.launcher.LauncherTheme.*;

/** Two current website announcements on the launch page, with direct article links. */
final class AnnouncementPanel extends JPanel {
    final LauncherView.Action all = new LauncherView.Action("全部公告", "arrow", false);
    private final JLabel[] titles = new JLabel[2];
    private final JLabel[] summaries = new JLabel[2];
    private final URI[] links = new URI[2];
    private Consumer<URI> open = ignored -> {};

    AnnouncementPanel() {
        setLayout(null); setOpaque(false);
        add(all);
        for (int i = 0; i < 2; i++) {
            final int row = i;
            titles[i] = LauncherView.label("", 13, TEXT, true);
            summaries[i] = LauncherView.label("", 11, MUTED, false);
            titles[i].setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            titles[i].addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (links[row] != null) open.accept(links[row]);
                }
            });
            add(titles[i]); add(summaries[i]);
        }
        message("正在读取官网公告…");
    }

    void onOpen(Consumer<URI> action) { open = action; }
    void message(String text) {
        titles[0].setText(text); summaries[0].setText(""); links[0] = null;
        titles[1].setText(""); summaries[1].setText(""); links[1] = null;
    }
    void items(List<LauncherNews.Item> news) {
        if (news.isEmpty()) { message("目前没有公告"); return; }
        for (int i = 0; i < 2; i++) {
            if (i < news.size()) {
                var item = news.get(i);
                titles[i].setText(clip(item.title(), 25));
                titles[i].setToolTipText(item.title());
                summaries[i].setText(item.date() + "  ·  " + clip(item.summary(), 38));
                summaries[i].setToolTipText(item.summary());
                links[i] = item.url();
            } else {
                titles[i].setText(""); summaries[i].setText(""); links[i] = null;
            }
        }
    }
    private static String clip(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
    @Override public void doLayout() {
        int w = getWidth();
        all.setBounds(w - 115, 0, 112, 30);
        for (int i = 0; i < 2; i++) {
            int y = 39 + i * 46;
            titles[i].setBounds(18, y, w - 36, 21);
            summaries[i].setBounds(18, y + 21, w - 36, 18);
        }
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = graphics(graphics);
        g.setColor(new Color(6, 15, 20, 214));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
        g.setColor(GOLD); g.fillRect(0, 0, 3, getHeight());
        g.setFont(bold(13)); g.drawString("官方公告", 18, 22);
        g.dispose();
    }
}
