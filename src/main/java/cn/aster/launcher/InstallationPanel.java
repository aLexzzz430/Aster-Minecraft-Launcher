package cn.aster.launcher;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import static cn.aster.launcher.LauncherTheme.*;

final class InstallationPanel extends JPanel {
    final LauncherView.Action location = new LauncherView.Action("打开文件夹", "", false);
    final LauncherView.Action migrate = new LauncherView.Action("更改安装路径", "", false);
    final LauncherView.Action desktop = new LauncherView.Action("桌面快捷方式", "", false);
    final LauncherView.Action start = new LauncherView.Action("开始菜单", "", false);
    final LauncherView.Action taskbar = new LauncherView.Action("固定到任务栏", "", false);
    final LauncherView.Action automatic = new LauncherView.Action("自动检查并下载更新 · 已开启", "", false);
    final LauncherView.Action check = new LauncherView.Action("检查更新", "", false);
    final LauncherView.Action apply = new LauncherView.Action("重启并更新", "arrow", true);
    private final JTextField path = new JTextField();
    private final JLabel version = LauncherView.label("客户端版本", 13, TEXT, true);
    private final JLabel status = LauncherView.label("每次启动检查；游戏运行时后台下载", 11, MUTED, false);
    private boolean enabled = true;
    InstallationPanel() {
        setLayout(null); setOpaque(false);
        var title = LauncherView.label("安装位置", 13, TEXT, true); title.setBounds(0, 0, 352, 24); add(title);
        var shortcuts = LauncherView.label("快捷入口", 13, TEXT, true); shortcuts.setBounds(0, 111, 352, 24); add(shortcuts);
        for (Component c : new Component[]{path, location, migrate, desktop, start, taskbar, version, automatic, check, apply, status}) add(c);
        path.setEditable(false); path.setFont(font(12)); path.setForeground(MUTED); path.setBackground(FIELD);
        path.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
        path.setBounds(0, 28, 352, 31); location.setBounds(0, 68, 164, 31); migrate.setBounds(180, 68, 172, 31);
        desktop.setBounds(0, 143, 116, 35); start.setBounds(118, 143, 104, 35); taskbar.setBounds(224, 143, 128, 35);
        version.setBounds(0, 184, 352, 26); automatic.setBounds(0, 215, 352, 32);
        check.setBounds(0, 257, 150, 42); apply.setBounds(160, 257, 192, 42); status.setBounds(0, 307, 352, 20);
        desktop.setToolTipText("创建或修复桌面上的 AsterRPG 快捷方式");
        start.setToolTipText("创建或修复开始菜单中的 AsterRPG 入口");
        taskbar.setToolTipText("由 Windows 请求确认；旧版系统可通过开始菜单右键固定");
        apply.spinWhenDisabled = false; apply.setEnabled(false);
    }
    void configure(Path root, String release, boolean auto) {
        path.setText(root.toString()); path.setToolTipText(root.toString()); path.setCaretPosition(path.getText().length());
        version.setText("客户端 " + release); automatic(auto);
    }
    boolean automatic() { return enabled; }
    void automatic(boolean value) { enabled = value; automatic.selected = value;
        automatic.setText("自动检查并下载更新 · " + (value ? "已开启" : "已关闭")); }
    void status(String value, boolean working, boolean ready) {
        status.setText(value); status.setToolTipText(value);
        check.setEnabled(!working); migrate.setEnabled(!working && !ready); apply.setEnabled(ready && !working);
    }
}
