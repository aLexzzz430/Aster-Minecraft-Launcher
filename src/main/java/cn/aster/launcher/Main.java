package cn.aster.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Integrated account entrypoint for the single-file Windows client and local authentication UI. */
public final class Main {
    private static final URI API = URI.create("https://189.24.77.244:25567");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private final Path root;
    private final boolean authOnly;
    private final JFrame frame = new JFrame("AsterRPG");
    private final LauncherView view;
    private HttpClient client;
    private FileChannel lockChannel;
    private FileLock lock;
    private ClientUpdater updater;
    private String sessionName, sessionTicket, serverAddress;
    private volatile String sessionUpdateToken;
    private char[] sessionSecret;
    private long ticketIssuedAt;
    private volatile boolean maintaining;
    private final AtomicBoolean updating = new AtomicBoolean();
    private final ScheduledExecutorService updates = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aster-client-update"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService dashboardTasks = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "aster-launch-dashboard"); t.setDaemon(true); return t;
    });

    private Main(Path root, boolean authOnly) {
        this.root = root; this.authOnly = authOnly; this.view = new LauncherView(authOnly);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--check-bundle".equals(args[0])) {
            int checked = new GameInstallation(Path.of(args[1])).checkBundle();
            System.out.println("BUNDLE_OK required_files=" + checked);
            return;
        }
        boolean authOnly = args.length == 2 && "--auth-only".equals(args[0]);
        Path root = authOnly ? Path.of(args[1]) : args.length > 0 ? Path.of(args[0]) : Path.of(System.getProperty("user.dir"));
        SwingUtilities.invokeLater(() -> {
            if (!authOnly && WindowsIntegration.available()) {
                try { WindowsIntegration.identity(); } catch (Exception error) { error.printStackTrace(); }
            }
            new Main(root.toAbsolutePath(), authOnly).show();
        });
    }

    private void show() {
        if (!authOnly) {
            try {
                Files.createDirectories(root);
                lockChannel = FileChannel.open(root.resolve("client.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = lockChannel.tryLock();
                if (lock == null) {
                    JOptionPane.showMessageDialog(null, "AsterRPG 已经打开，请切换到现有的登录或游戏窗口。", "AsterRPG", JOptionPane.INFORMATION_MESSAGE);
                    lockChannel.close();
                    return;
                }
            } catch (Exception error) {
                JOptionPane.showMessageDialog(null, "无法打开客户端目录：" + error.getMessage(), "AsterRPG", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { if (!maintaining) exit(); }
        });
        frame.setUndecorated(true);
        frame.setResizable(false);
        frame.setIconImage(LauncherTheme.appIcon(64));
        frame.setContentPane(view);
        view.attach(frame);
        view.onSubmit(this::authenticate);
        view.onLogs(this::openLogs);
        view.dashboard.launch.addActionListener(e -> launchGame());
        view.dashboard.logout.addActionListener(e -> switchAccount());
        view.announcements.onOpen(this::openWebsite);
        view.announcements.all.addActionListener(e -> openWebsite(LauncherNews.INDEX));
        if (!authOnly) loadSettings();
        if (!authOnly) view.onSettingsChanged(() -> {
            try {
                new LauncherPreferences(view.username(), view.performancePreset(), view.memoryMb(),
                        view.resetPresetRequested()).save(root);
            } catch (Exception error) { view.showNotice("设置保存失败：" + error.getMessage(), true); }
        });
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(LauncherView.WIDTH, screen.width - 24), Math.min(LauncherView.HEIGHT, screen.height - 16));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        if (!authOnly) configureInstallation();
        if (!authOnly) configureResourcePacks();
        if (!authOnly) {
            dashboardTasks.scheduleWithFixedDelay(this::refreshServerStatus, 60, 60, TimeUnit.SECONDS);
            dashboardTasks.scheduleWithFixedDelay(this::refreshNews, 300, 300, TimeUnit.SECONDS);
        }
        view.startEntrance();
        if (view.username().isEmpty()) view.name.input.requestFocusInWindow();
        else view.password.input.requestFocusInWindow();
    }

    private void authenticate() {
        if (view.busy() || maintaining) return;
        String username = view.username();
        char[] secret = view.password();
        char[] confirmation = view.confirmation();
        boolean registering = view.registering();
        if (!validateInput(view, secret, confirmation)) { clear(secret, confirmation); return; }
        String claimCode = view.claimCode();
        view.setBusy(1, registering ? "正在创建账号" : "正在验证账号");
        new SwingWorker<LoginResult, Void>() {
            @Override protected LoginResult doInBackground() throws Exception {
                LoginResult result = requestTicket(username, secret, registering, claimCode);
                if (authOnly) {
                    Files.createDirectories(root);
                    Files.writeString(root.resolve("aster-auth-ticket.txt"),
                            result.name() + "\n" + result.ticket() + "\n", StandardCharsets.UTF_8);
                    Files.writeString(root.resolve("aster-auth-name.txt"), result.name(), StandardCharsets.UTF_8);
                }
                return result;
            }
            @Override protected void done() {
                clear(confirmation);
                try {
                    LoginResult result = get();
                    if (authOnly) { clear(secret); frame.dispose(); return; }
                    clear(sessionSecret);
                    sessionName = result.name();
                    sessionSecret = secret;
                    sessionTicket = result.ticket();
                    sessionUpdateToken = result.updateToken();
                    if (updater != null) updater.authorize(sessionUpdateToken);
                    ticketIssuedAt = System.currentTimeMillis();
                    try {
                        new LauncherPreferences(sessionName, view.performancePreset(), view.memoryMb(),
                                view.resetPresetRequested()).save(root);
                    } catch (Exception error) { view.showNotice("启动偏好保存失败：" + error.getMessage(), true); }
                    view.enterDashboard(sessionName);
                    view.dashboard.launch.requestFocusInWindow();
                    dashboardTasks.execute(Main.this::refreshServerStatus);
                    dashboardTasks.execute(Main.this::refreshNews);
                    dashboardTasks.execute(Main.this::loadAvatar);
                    try {
                        if (updater != null && ClientMaintenance.automatic(root)) checkUpdates();
                    } catch (Exception error) { updateStatus("更新设置读取失败：" + error.getMessage(), false, false); }
                } catch (Exception error) {
                    clear(secret);
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    view.finishWithError(userMessage(cause));
                }
            }
        }.execute();
    }

    private record LoginResult(String name, String ticket, String updateToken) {}

    private LoginResult requestTicket(String username, char[] secret, boolean registering, String claimCode) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("name", username);
        body.addProperty("password", new String(secret));
        if (registering) body.addProperty("claimCode", claimCode);
        HttpRequest request = HttpRequest.newBuilder(API.resolve(registering ? "/v1/register" : "/v1/login"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() != 200) throw new IllegalStateException(message(result));
        return new LoginResult(result.get("name").getAsString(), result.get("ticket").getAsString(),
                string(result, "updateToken"));
    }

    private void launchGame() {
        if (!view.loggedIn() || view.busy() || maintaining || sessionSecret == null) return;
        if (updating.get()) {
            view.showNotice("正在检查或下载客户端更新，请等待完成后开始游戏。", false);
            return;
        }
        if (updater != null && updater.ready()) {
            view.showNotice("新版已准备好，请先点击“重启并更新”。", false);
            view.homeUpdate.apply.requestFocusInWindow();
            return;
        }
        String name = sessionName;
        char[] secret = sessionSecret;
        String ticket = sessionTicket;
        long issuedAt = ticketIssuedAt;
        int memoryMb = view.memoryMb();
        PerformancePreset preset = view.performancePreset();
        boolean resetPreset = view.resetPresetRequested();
        view.setBusy(0, "正在准备客户端");
        new SwingWorker<Process, Progress>() {
            @Override protected Process doInBackground() throws Exception {
                GameInstallation installation = new GameInstallation(root);
                installation.prepare(message -> publish(new Progress(0, message)));
                installation.applyPerformancePreset(preset, resetPreset);
                publish(new Progress(1, "正在确认入服凭证"));
                String launchTicket = ticket;
                if (launchTicket == null || System.currentTimeMillis() - issuedAt > 12 * 60_000L) {
                    LoginResult renewed = requestTicket(name, secret, false, "");
                    if (!name.equals(renewed.name())) throw new IllegalStateException("登录账号已改变，请切换账号后重试");
                    launchTicket = renewed.ticket();
                    sessionUpdateToken = renewed.updateToken();
                    if (updater != null) updater.authorize(sessionUpdateToken);
                }
                Path ticketFile = installation.gameDirectory().resolve("aster-auth-ticket.txt");
                Files.writeString(ticketFile, name + "\n" + launchTicket + "\n", StandardCharsets.UTF_8);
                try {
                    publish(new Progress(2, "正在启动游戏"));
                    return installation.launch(name, memoryMb, preset);
                } catch (Exception failure) {
                    Files.deleteIfExists(ticketFile);
                    throw failure;
                }
            }
            @Override protected void process(java.util.List<Progress> changes) {
                Progress latest = changes.getLast();
                view.busyDetail(latest.step(), latest.detail());
            }
            @Override protected void done() {
                try {
                    Process process = get();
                    sessionTicket = null;
                    view.presetApplied();
                    frame.setVisible(false);
                    Thread.ofPlatform().name("aster-game-process").start(() -> {
                        try {
                            int code = process.waitFor();
                            Files.deleteIfExists(root.resolve("game/aster-auth-ticket.txt"));
                            SwingUtilities.invokeLater(() -> {
                                if (code == 0) {
                                    if (updater != null && updater.ready()) restart(root); else exit();
                                } else {
                                    view.finishWithError("游戏启动或运行失败（" + code + "），可打开启动日志查看原因。");
                                    frame.setVisible(true);
                                }
                            });
                        } catch (Exception error) { error.printStackTrace(); }
                    });
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    view.finishWithError(userMessage(cause));
                }
            }
        }.execute();
    }

    static boolean validateInput(LauncherView view, char[] secret, char[] confirmation) {
        view.clearErrors(); view.clearNotice();
        if (!NAME.matcher(view.username()).matches()) {
            view.fieldError(view.name, "请输入 3–16 位英文字母、数字或下划线"); return false;
        }
        if (secret.length == 0) { view.fieldError(view.password, "请输入密码"); return false; }
        if (view.registering() && (secret.length < 10 || secret.length > 30)) {
            view.fieldError(view.password, "注册密码需为 10–30 位"); return false;
        }
        if (view.registering() && !Arrays.equals(secret, confirmation)) {
            view.fieldError(view.repeat, "两次输入的密码不一致"); return false;
        }
        return true;
    }

    private static String userMessage(Throwable error) {
        if (error instanceof java.net.http.HttpTimeoutException)
            return "账号服务响应超时，请检查网络后重试。";
        if (error instanceof java.net.ConnectException || error instanceof java.net.UnknownHostException)
            return "暂时无法连接账号服务，请检查网络后重试。";
        if (error instanceof javax.net.ssl.SSLException)
            return "无法验证账号服务连接，请检查系统时间或更新客户端。";
        return error.getMessage() == null ? "登录失败，请稍后重试。" : error.getMessage();
    }

    private record Progress(int step, String detail) {}

    private HttpClient http() throws Exception {
        if (client != null) return client;
        var factory = CertificateFactory.getInstance("X.509");
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream input = Main.class.getResourceAsStream("/aster-auth.cer")) {
            if (input == null) throw new IllegalStateException("客户端缺少认证证书");
            store.setCertificateEntry("aster-auth", factory.generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, managers.getTrustManagers(), null);
        client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10)).build();
        return client;
    }

    private void loadSettings() {
        try {
            var settings = LauncherPreferences.load(root);
            view.name.input.setText(settings.username());
            view.restorePerformanceSettings(settings.preset(), settings.memoryMb(), settings.resetPreset());
        } catch (Exception error) { view.showNotice("启动偏好读取失败，可重新选择后登录。", true); }
    }

    private void configureInstallation() {
        updater = new ClientUpdater(root);
        var panel = view.installation;
        try {
            String release = updater.currentRelease();
            GameInstallation game = new GameInstallation(root);
            serverAddress = game.serverAddress();
            view.dashboard.installation(root, game.minecraftVersion(), release);
            panel.configure(root, release, ClientMaintenance.automatic(root));
            view.homeUpdate.configure(release);
            if (WindowsIntegration.available()) WindowsIntegration.repairExisting(root);
        } catch (Exception e) { view.showNotice("安装设置：" + e.getMessage(), true); }
        panel.location.addActionListener(e -> {
            try { Desktop.getDesktop().open(root.toFile()); } catch (Exception error) { view.showNotice(error.getMessage(), true); }
        });
        view.dashboard.location.addActionListener(e -> panel.location.doClick());
        view.dashboard.changeLocation.addActionListener(e -> view.showInstallation(true));
        panel.desktop.addActionListener(e -> createShortcut(true));
        panel.start.addActionListener(e -> createShortcut(false));
        panel.taskbar.addActionListener(e -> {
            try { WindowsIntegration.pin(root, message -> view.showNotice(message, false)); }
            catch (Exception error) {
                view.showNotice("此 Windows 需要手动固定：右键 AsterRPG → 更多 → 固定到任务栏。", false);
                try { WindowsIntegration.openStartMenu(); } catch (Exception failed) { view.showNotice(failed.getMessage(), true); }
            }
        });
        panel.migrate.addActionListener(e -> migrate());
        panel.automatic.addActionListener(e -> {
            try {
                boolean auto = !panel.automatic(); ClientMaintenance.automatic(root, auto); panel.automatic(auto);
                if (auto) checkUpdates();
            } catch (Exception error) { view.showNotice(error.getMessage(), true); }
        });
        panel.check.addActionListener(e -> checkUpdates());
        panel.apply.addActionListener(e -> restart(root));
        view.homeUpdate.check.addActionListener(e -> checkUpdates());
        view.homeUpdate.apply.addActionListener(e -> restart(root));
        updates.scheduleWithFixedDelay(() -> {
            try { if (sessionUpdateToken != null && ClientMaintenance.automatic(root)) checkUpdates(); }
            catch (Exception error) { updateStatus("更新设置读取失败：" + error.getMessage(), false, false); }
        }, 900, 900, TimeUnit.SECONDS);
        if (updater.ready()) updateStatus("已下载新版，下次启动生效", false, true);
    }

    private void checkUpdates() {
        if (sessionUpdateToken == null || sessionUpdateToken.isBlank()) {
            updateStatus(view.loggedIn() ? "账号服务尚未提供更新授权，请稍后重试" : "请先登录游戏账号，再检查客户端更新",
                    false, updater != null && updater.ready());
            return;
        }
        if (maintaining || !updating.compareAndSet(false, true)) return;
        updateStatus("正在检查更新…", true, false);
        updates.execute(() -> {
            try {
                var result = updater.checkAndStage(message -> updateStatus(message, true, false));
                updateStatus(result.ready() ? "新版 " + result.release() + " 已就绪" : "已是最新版本 · " + result.release(), false, result.ready());
            } catch (Exception error) { updateStatus("更新未完成：" + error.getMessage(), false, updater.ready()); }
            finally { updating.set(false); }
        });
    }

    private void configureResourcePacks() {
        ResourcePacks packs = new ResourcePacks(root);
        var panel = view.resourcePanel;
        view.onResourcePacks(this::refreshResourcePacks);
        panel.refresh.addActionListener(e -> refreshResourcePacks());
        panel.openFolder.addActionListener(e -> {
            try { Desktop.getDesktop().open(packs.directory().toFile()); }
            catch (Exception error) { view.showNotice("无法打开材质包文件夹：" + error.getMessage(), true); }
        });
        panel.addPack.addActionListener(e -> {
            if (view.busy() || maintaining) return;
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("添加材质包：选择 ZIP 或已解压文件夹");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES); chooser.setMultiSelectionEnabled(true);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Java 版材质包（ZIP 或文件夹）", "zip"));
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
                importResourcePacks(Arrays.stream(chooser.getSelectedFiles()).map(java.io.File::toPath).toList());
        });
        panel.toggle.addActionListener(e -> {
            var selected = panel.packs.getSelectedValue(); if (selected == null || maintaining) return;
            try {
                packs.setEnabled(selected.name(), !selected.enabled()); refreshResourcePacks();
                view.showNotice((selected.enabled() ? "已停用：" : "已启用：") + selected.name() + "，下次启动生效。", false);
            } catch (Exception error) { view.showNotice(error.getMessage(), true); }
        });
        panel.onDrop(this::importResourcePacks, message -> view.showNotice(message, true));
    }
    private void refreshResourcePacks() {
        try { view.resourcePanel.setPacks(new ResourcePacks(root).list()); }
        catch (Exception error) { view.showNotice("读取材质包失败：" + error.getMessage(), true); }
    }
    private void importResourcePacks(java.util.List<Path> files) {
        if (view.busy() || maintaining || files.isEmpty()) return;
        boolean enable = view.resourcePanel.autoEnable.isSelected();
        maintaining = true; view.setBusy(0, "正在导入材质包"); maintenanceControls(false); view.resourcePanel.working(true);
        new SwingWorker<java.util.List<String>, String>() {
            protected java.util.List<String> doInBackground() throws Exception {
                return new ResourcePacks(root).importPacks(files, enable, this::publish);
            }
            protected void process(java.util.List<String> changes) { view.showNotice(changes.getLast(), false); }
            protected void done() {
                maintaining = false; view.finishWithError(""); maintenanceControls(true); view.resourcePanel.working(false);
                refreshResourcePacks();
                try {
                    var imported = get();
                    String message = imported.size() == 1 ? "已导入 " + imported.getFirst() : "已导入 " + imported.size() + " 个材质包";
                    view.showNotice(message + (enable ? "，下次启动自动启用。" : "，可在列表中选择启用。"), false);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    view.showNotice("导入未完成：" + cause.getMessage(), true);
                }
            }
        }.execute();
    }
    private void updateStatus(String text, boolean working, boolean ready) {
        SwingUtilities.invokeLater(() -> {
            view.installation.status(text, working, ready);
            view.homeUpdate.status(text, working, ready);
            view.dashboard.updateReady(ready);
        });
    }

    private void refreshServerStatus() {
        if (!view.loggedIn() || serverAddress == null) return;
        MinecraftStatus.Snapshot status;
        try { status = MinecraftStatus.query(serverAddress); }
        catch (Exception error) { status = MinecraftStatus.Snapshot.unavailable(); }
        MinecraftStatus.Snapshot result = status;
        SwingUtilities.invokeLater(() -> { if (view.loggedIn()) view.dashboard.server(result); });
    }

    private void refreshNews() {
        if (!view.loggedIn()) return;
        try {
            var items = LauncherNews.load();
            SwingUtilities.invokeLater(() -> { if (view.loggedIn()) view.announcements.items(items); });
        } catch (Exception error) {
            SwingUtilities.invokeLater(() -> {
                if (view.loggedIn()) view.announcements.message("公告暂时无法加载，请前往官网查看");
            });
        }
    }

    private void loadAvatar() {
        if (!view.loggedIn()) return;
        try {
            var image = MinecraftAvatar.defaultHead(root, new GameInstallation(root).minecraftVersion());
            SwingUtilities.invokeLater(() -> { if (view.loggedIn()) view.dashboard.avatar(image); });
        } catch (Exception error) {
            SwingUtilities.invokeLater(() -> {
                if (view.loggedIn()) view.dashboard.avatarUnavailable("默认皮肤资源无法读取");
            });
        }
    }

    private void openWebsite(URI uri) {
        try { Desktop.getDesktop().browse(uri); }
        catch (Exception error) { view.showNotice("无法打开官网：" + error.getMessage(), true); }
    }

    private void switchAccount() {
        if (view.busy() || maintaining || updating.get()) return;
        clear(sessionSecret);
        sessionSecret = null; sessionTicket = null; sessionName = null; sessionUpdateToken = null; ticketIssuedAt = 0;
        if (updater != null) updater.authorize(null);
        view.returnToLogin();
    }
    private void createShortcut(boolean desktop) {
        try { WindowsIntegration.shortcut(root, desktop); view.showNotice(desktop ? "已创建桌面快捷方式。" : "已添加到开始菜单。", false); }
        catch (Exception error) { view.showNotice("快捷方式创建失败：" + error.getMessage(), true); }
    }
    private void migrate() {
        if (updating.get() || maintaining) return;
        JFileChooser chooser = new JFileChooser(root.getParent().toFile());
        chooser.setDialogTitle("选择新的 AsterRPG 文件夹（需为空）"); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        maintaining = true;
        view.setBusy(0, "正在迁移客户端");
        maintenanceControls(false);
        updateStatus("正在迁移安装文件与玩家数据…", true, false);
        new SwingWorker<Void, String>() {
            protected Void doInBackground() throws Exception { ClientMaintenance.migrate(root, target, this::publish); return null; }
            protected void process(java.util.List<String> changes) { updateStatus(changes.getLast(), true, false); }
            protected void done() {
                maintaining = false; view.finishWithError(""); maintenanceControls(true);
                try { get(); restart(target); }
                catch (Exception error) { view.showNotice("迁移失败：" + (error.getCause() == null ? error.getMessage() : error.getCause().getMessage()), true); updateStatus("原安装仍可使用", false, false); }
            }
        }.execute();
    }
    private void maintenanceControls(boolean enabled) {
        var panel = view.installation;
        view.homeUpdate.setBusy(!enabled || view.busy());
        view.dashboard.launch.setEnabled(enabled && !view.busy() && (updater == null || !updater.ready()));
        for (var component : new Component[]{view.close, view.back, view.settingsDone, view.performanceTab,
                view.installationTab, view.dashboard.logout, view.dashboard.location, view.dashboard.changeLocation,
                panel.migrate, panel.check, panel.location, panel.desktop, panel.start,
                panel.taskbar, panel.automatic}) component.setEnabled(enabled);
    }
    private void restart(Path installation) {
        try {
            new ProcessBuilder(installation.resolve("AsterRPG.exe").toString(), "/WAIT=" + ProcessHandle.current().pid())
                    .directory(installation.toFile()).start();
            exit();
        } catch (Exception error) { view.showNotice("无法重启启动器：" + error.getMessage(), true); frame.setVisible(true); }
    }
    private void exit() {
        clear(sessionSecret); sessionSecret = null;
        sessionUpdateToken = null;
        updates.shutdownNow(); dashboardTasks.shutdownNow(); frame.dispose(); releaseLock(); System.exit(0);
    }

    private void openLogs() {
        try {
            Path directory = root.resolve("logs"); Files.createDirectories(directory);
            Desktop.getDesktop().open(directory.toFile());
        } catch (Exception error) { view.showNotice("日志目录：" + root.resolve("logs"), false); }
    }

    private void releaseLock() {
        try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); }
        catch (Exception error) { error.printStackTrace(); }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static String message(JsonObject response) {
        return switch (string(response, "code").toUpperCase(Locale.ROOT)) {
            case "INVALID_CREDENTIALS" -> "账号或密码错误；注意游戏名大小写";
            case "NAME_REGISTERED" -> "该名字已注册，请切换到登录页";
            case "LEGACY_CLAIM_REQUIRED" -> "这是旧角色名，首次注册需要管理员提供的认领码";
            case "TOO_MANY_ATTEMPTS" -> "尝试太频繁，请一分钟后重试";
            case "INVALID_INPUT" -> "游戏名或密码格式不正确";
            default -> "账号后台暂不可用，请稍后重试";
        };
    }

    private static void clear(char[]... arrays) {
        for (char[] array : arrays) if (array != null) Arrays.fill(array, '\0');
    }
}
