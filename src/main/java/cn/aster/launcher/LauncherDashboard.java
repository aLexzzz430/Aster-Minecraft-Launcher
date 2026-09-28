package cn.aster.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static cn.aster.launcher.LauncherTheme.*;

/** The post-login launch workspace. All values are populated from the installed client or live services. */
final class LauncherDashboard extends JPanel {
    final LauncherView.Action launch = new LauncherView.Action("开始游戏", "arrow", true);
    final LauncherView.Action logout = new LauncherView.Action("切换账号", "", false);
    final LauncherView.Action location = new LauncherView.Action("打开位置", "", false);
    final LauncherView.Action changeLocation = new LauncherView.Action("更改位置", "", false);
    final LauncherView.Action uploadSkin = new LauncherView.Action("上传皮肤", "", false);
    private final JLabel heading = LauncherView.label("准备进入游戏", 29, TEXT, true);
    private final JLabel account = LauncherView.label("已登录", 12, GOLD, false);
    private final JLabel server = LauncherView.label("正在读取服务器状态…", 17, TEXT, true);
    private final JLabel players = LauncherView.label("", 12, MUTED, false);
    private final JLabel gameVersion = LauncherView.label("游戏版本 · 读取中", 14, TEXT, false);
    private final JLabel clientVersion = LauncherView.label("客户端版本 · 读取中", 12, MUTED, false);
    private final JLabel skin = LauncherView.label("角色外观", 14, TEXT, true);
    private final JLabel skinNote = LauncherView.label("正在读取皮肤…", 11, MUTED, false);
    private final JLabel path = LauncherView.label("读取安装位置…", 11, MUTED, false);
    private final Avatar avatar = new Avatar();
    private boolean updateReady, working;

    LauncherDashboard() {
        setOpaque(false); setLayout(null);
        for (Component component : new Component[]{heading, account, server, players, gameVersion, clientVersion,
                skin, skinNote, path, avatar, uploadSkin, launch, logout, location, changeLocation}) add(component);
        launch.getAccessibleContext().setAccessibleName("启动 AsterRPG 游戏");
        logout.getAccessibleContext().setAccessibleName("退出当前游戏账号并返回登录页");
    }

    void account(String username) { account.setText("已登录 · " + username); }
    void installation(Path root, String minecraft, String release) {
        gameVersion.setText("Minecraft " + minecraft + "  ·  AsterRPG 内测");
        clientVersion.setText("客户端版本  " + release);
        path.setText(root.toString());
        path.setToolTipText(root.toString());
    }
    void server(MinecraftStatus.Snapshot snapshot) {
        server.setText(snapshot.reachable() ? "南湾主服 · 在线" : "南湾主服 · 暂无法连接");
        server.setForeground(snapshot.reachable() ? TEXT : ERROR_COLOR);
        players.setText(snapshot.reachable()
                ? "在线玩家 " + snapshot.online() + " / " + snapshot.max() + "   ·   " + snapshot.version()
                : "无法读取列表状态；仍可尝试启动游戏");
    }
    void avatar(BufferedImage image) { avatar.image = image; avatar.repaint(); }
    void skinStatus(String message) { skinNote.setText(message); }
    void avatarUnavailable(String reason) {
        avatar.image = null; avatar.repaint();
        skinNote.setText(reason);
    }
    void updateReady(boolean ready) { updateReady = ready; refreshLaunch(""); }
    void working(boolean value, String detail) {
        working = value;
        refreshLaunch(detail);
        logout.setEnabled(!value);
        location.setEnabled(!value);
        changeLocation.setEnabled(!value);
        uploadSkin.setEnabled(!value);
    }
    private void refreshLaunch(String detail) {
        launch.setEnabled(!working && !updateReady);
        launch.setText(working ? detail : updateReady ? "更新客户端后开始游戏" : "开始游戏");
    }

    @Override public void doLayout() {
        int w = getWidth(), h = getHeight();
        heading.setBounds(0, 0, w, 42);
        account.setBounds(0, 49, w - 112, 24);
        logout.setBounds(w - 106, 44, 106, 34);
        server.setBounds(0, 118, w, 30);
        players.setBounds(0, 150, w, 25);
        gameVersion.setBounds(0, 214, w, 28);
        clientVersion.setBounds(0, 245, w, 24);
        avatar.setBounds(0, 310, 78, 78);
        skin.setBounds(98, 315, w - 98, 28);
        skinNote.setBounds(98, 344, w - 98, 36);
        uploadSkin.setBounds(98, 372, 120, 30);
        path.setBounds(0, 431, w, 22);
        location.setBounds(0, 459, 132, 31);
        changeLocation.setBounds(140, 459, 132, 31);
        launch.setBounds(0, h - 56, w, 54);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = graphics(graphics);
        g.setColor(LINE);
        for (int y : new int[]{94, 190, 286, 405}) g.drawLine(0, y, getWidth(), y);
        g.setColor(MUTED); g.setFont(font(11));
        g.drawString("服务器状态", 0, 111);
        g.drawString("游戏版本", 0, 207);
        g.drawString("角色皮肤", 0, 305);
        g.drawString("下载与安装位置", 0, 425);
        g.dispose();
    }

    private static final class Avatar extends JComponent {
        private BufferedImage image;
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            g.setColor(FIELD); g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            if (image != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(image, 8, 8, getWidth() - 16, getHeight() - 16, null);
            } else {
                g.setColor(GOLD); g.fillOval(28, 14, 22, 22);
                g.fillRoundRect(19, 41, 40, 25, 6, 6);
            }
            g.dispose();
        }
    }
}
