package cn.aster.launcher;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Renders both desktop pages and checks the account-to-launch transition. */
public final class LauncherViewTest {
    private static void expect(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
    private static void layout(Container component) {
        component.doLayout();
        for (Component child : component.getComponents()) if (child instanceof Container inner) layout(inner);
    }
    private static void capture(LauncherView view, Path output, String name) throws Exception {
        layout(view);
        BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        view.printAll(graphics); graphics.dispose();
        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
    }
    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Path.of("outputs/launcher-ui-20260928") : Path.of(args[0]);
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                LauncherView view = new LauncherView(false);
                view.setSize(LauncherView.WIDTH, LauncherView.HEIGHT);
                AtomicInteger submits = new AtomicInteger();
                view.onSubmit(() -> {
                    if (Main.validateInput(view, view.password(), view.confirmation())) submits.incrementAndGet();
                });
                capture(view, output, "01-login");
                expect(view.submit.isVisible() && !view.dashboard.isVisible() && !view.homeUpdate.isVisible()
                        && !view.settings.isVisible(), "first page contains only account entry");
                view.submit.doClick();
                expect(submits.get() == 0 && !view.name.error().isEmpty(), "invalid account stays on login page");
                view.name.input.setText("AsterTraveler");
                view.password.input.setText("sample-pass-123");
                view.submit.doClick();
                expect(submits.get() == 1, "valid login reaches account handler");
                view.registerTab.doClick();
                view.password.input.setText("sample-pass-123");
                view.repeat.input.setText("sample-pass-124");
                view.submit.doClick();
                expect(submits.get() == 1 && !view.repeat.error().isEmpty(), "registration mismatch is shown inline");
                view.repeat.input.setText("sample-pass-123");
                view.claimToggle.doClick();
                view.claim.input.setText("claim-example");
                expect(view.claimCode().equals("claim-example"), "old-character claim remains available");
                capture(view, output, "02-register");
                view.setBusy(1, "正在验证账号");
                expect(!view.name.input.isEnabled() && !view.submit.isEnabled(), "pending authentication locks form");
                view.finishWithError("账号服务响应超时，请检查网络后重试。");
                expect(view.name.input.isEnabled(), "failure unlocks account form");
                view.loginTab.doClick();

                view.enterDashboard("AsterTraveler");
                view.dashboard.installation(Path.of("D:\\Program Files\\AsterRPG"), "1.21.11", "2026.9.28.1");
                view.dashboard.server(new MinecraftStatus.Snapshot(true, 7, 60, "1.21.11"));
                view.announcements.items(List.of(
                        new LauncherNews.Item("南湾城内测公告", "新增锻造流程与南方平原路线。", "09月28日", URI.create("https://asterrpg.org/forum/topic/1/")),
                        new LauncherNews.Item("星塔组队大厅开放", "组队房间与备战服务已开放。", "09月27日", URI.create("https://asterrpg.org/forum/topic/2/"))));
                view.homeUpdate.configure("2026.9.28.1");
                view.homeUpdate.status("已是最新版本 · 2026.9.28.1", false, false);
                AtomicInteger launches = new AtomicInteger(), checks = new AtomicInteger();
                view.dashboard.launch.addActionListener(e -> launches.incrementAndGet());
                view.homeUpdate.check.addActionListener(e -> checks.incrementAndGet());
                capture(view, output, "03-launch");
                expect(view.dashboard.isVisible() && view.announcements.isVisible() && view.homeUpdate.isVisible()
                        && !view.submit.isVisible(), "post-login page has launch, news and top-level updates");
                view.dashboard.launch.doClick(); view.homeUpdate.check.doClick();
                expect(launches.get() == 1 && checks.get() == 1, "launch and update actions are live");
                view.homeUpdate.status("新版 2026.9.28.2 已就绪", false, true);
                view.dashboard.updateReady(true);
                expect(!view.dashboard.launch.isEnabled() && view.homeUpdate.apply.isVisible(),
                        "ready update takes priority over game launch");
                capture(view, output, "04-update-ready");
                view.homeUpdate.status("已是最新版本 · 2026.9.28.1", false, false);
                view.dashboard.updateReady(false);
                view.setBusy(0, "正在准备客户端");
                expect(!view.dashboard.launch.isEnabled() && !view.dashboard.logout.isEnabled(),
                        "game preparation prevents duplicate launch and account switch");
                view.finishWithError("游戏启动失败，可查看日志。");
                expect(view.dashboard.launch.isEnabled(), "game failure returns to actionable launch page");

                view.settings.doClick();
                expect(view.showingSettings() && !view.dashboard.isVisible(), "settings open from launch page");
                view.presetChoices.get(0).doClick();
                expect(view.performancePreset() == PerformancePreset.SMOOTH, "performance choice remains actionable");
                capture(view, output, "05-settings");
                view.installationTab.doClick();
                view.installation.configure(Path.of("D:\\Program Files\\AsterRPG"), "2026.9.28.1", true);
                expect(view.installation.isVisible(), "install location and shortcuts remain available");
                capture(view, output, "06-install-location");
                view.showResourcePacks();
                expect(view.resourcePanel.isVisible(), "resource packs remain a separate launch-page setting");
                view.setSize(1040, 704);
                capture(view, output, "07-compact-resource-packs");
                view.settingsDone.doClick();
                expect(view.dashboard.isVisible(), "settings return to launch page");
                capture(view, output, "08-compact-launch");
                view.returnToLogin();
                expect(view.submit.isVisible() && !view.dashboard.isVisible(), "switch account returns to login page");

                LauncherView local = new LauncherView(true);
                expect(!local.dashboard.isVisible() && !local.settings.isVisible() && !local.homeUpdate.isVisible(),
                        "auth-only helper does not show Windows launch controls");
                System.out.println("PASS separate account and launch pages, updates, settings, error recovery and auth-only mode");
                System.out.println("UI renders: " + output.toAbsolutePath());
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
}
