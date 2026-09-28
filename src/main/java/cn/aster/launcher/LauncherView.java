package cn.aster.launcher;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

import static cn.aster.launcher.LauncherTheme.*;

/** The actual desktop launcher surface, also rendered by the visual QA harness. */
final class LauncherView extends JPanel {
    static final int WIDTH = 1180, HEIGHT = 740;
    final Entry name = new Entry("游戏名", false);
    final Entry password = new Entry("密码", true);
    final Entry repeat = new Entry("确认密码", true);
    final Entry claim = new Entry("旧角色认领码", false);
    final Action submit = new Action("登录", "arrow", true);
    final Action loginTab = new Action("登录", "", false);
    final Action registerTab = new Action("创建账号", "", false);
    final Action claimToggle = new Action("已有旧角色？输入认领码", "chevron", false);
    final Action settings = new Action("启动设置", "settings", false);
    final Action resourcePacks = new Action("材质包", "", false);
    final Action logs = new Action("启动日志", "log", false);
    final Action back = new Action("返回账号", "back", false);
    final Action minimize = new Action("", "minus", false);
    final Action close = new Action("", "close", false);
    final Action help = new Action("登录遇到问题", "", false);
    final Notice notice = new Notice();
    final List<Action> memoryChoices = new ArrayList<>();
    final List<PresetChoice> presetChoices = new ArrayList<>();
    final Action restorePreset = new Action("恢复该档默认", "", false);
    final Action settingsDone = new Action("完成设置", "arrow", true);
    final Action performanceTab = new Action("性能", "", false);
    final Action installationTab = new Action("安装与更新", "", false);
    final InstallationPanel installation = new InstallationPanel();
    final HomeUpdate homeUpdate = new HomeUpdate();
    final ResourcePackPanel resourcePanel = new ResourcePackPanel();
    final LauncherDashboard dashboard = new LauncherDashboard();
    final AnnouncementPanel announcements = new AnnouncementPanel();
    private boolean installingSettings, resourceSettings;
    private final boolean authOnly;
    private boolean registering, claiming, showingSettings, busy, helping;
    private volatile boolean loggedIn;
    private int memoryMb = 4096, stage = -1;
    private PerformancePreset preset = PerformancePreset.BALANCED;
    private boolean resetPreset;
    private float tabPosition, tabTarget, entrance = 1;
    private long entranceStart;
    private final Timer animation;
    private BufferedImage scene;
    private JFrame attachedFrame;
    private Runnable submitAction = () -> {}, logsAction = () -> {}, resourceAction = () -> {};
    private Runnable settingsChanged = () -> {};
    private final JLabel heading = label("欢迎回来", 29, TEXT, true);
    private final JLabel subtitle = label("登录你的 Aster 账号，继续旅程。", 13, MUTED, false);
    private final JLabel privacy = label("仅记住游戏名，不保存密码。", 12, MUTED, false);
    private String progressDetail = "";
    private boolean error;

    LauncherView(boolean authOnly) {
        this.authOnly = authOnly;
        setLayout(null); setBackground(BACKGROUND); setOpaque(true);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        for (Component c : new Component[]{name, password, repeat, claim, submit, loginTab, registerTab,
                claimToggle, settings, logs, back, minimize, close, help, notice, heading, subtitle, privacy,
                restorePreset, settingsDone, performanceTab, installationTab, installation, homeUpdate,
                resourcePacks, resourcePanel, dashboard, announcements}) add(c);
        performanceTab.addActionListener(e -> showInstallation(false));
        installationTab.addActionListener(e -> showInstallation(true));
        loginTab.addActionListener(e -> setRegistering(false));
        registerTab.addActionListener(e -> setRegistering(true));
        claimToggle.addActionListener(e -> {
            claiming = !claiming;
            claimToggle.setText(claiming ? "收起旧角色认领" : "已有旧角色？输入认领码");
            updateVisibility(); revalidate(); repaint();
            if (claiming) claim.input.requestFocusInWindow();
        });
        settings.addActionListener(e -> showSettings(true));
        resourcePacks.addActionListener(e -> { if (!busy) { showResourcePacks(); resourceAction.run(); } });
        back.addActionListener(e -> showSettings(false));
        settingsDone.addActionListener(e -> showSettings(false));
        restorePreset.addActionListener(e -> {
            resetPreset = true;
            setMemoryMb(preset.memoryMb());
            settingsChanged.run();
        });
        help.addActionListener(e -> {
            helping = !helping;
            if (helping) showNotice("忘记密码请联系管理员；旧角色首次使用时，在创建账号中填写认领码。", false);
            else clearNotice();
        });
        submit.addActionListener(e -> { if (!busy && !showingSettings) submitAction.run(); });
        logs.addActionListener(e -> logsAction.run());
        for (int mb : LauncherPreferences.MEMORY_CHOICES) {
            Action option = new Action((mb / 1024) + " GB", "", false);
            option.addActionListener(e -> { setMemoryMb(mb); settingsChanged.run(); });
            memoryChoices.add(option); add(option);
        }
        for (var choice : PerformancePreset.values()) {
            PresetChoice button = new PresetChoice(choice);
            button.addActionListener(e -> {
                if (preset == choice) return;
                preset = choice;
                resetPreset = false;
                setMemoryMb(preset.memoryMb());
                updatePresetSelection();
                settingsChanged.run();
            });
            presetChoices.add(button); add(button);
        }
        name.input.setToolTipText("3–16 位英文字母、数字或下划线");
        password.input.setToolTipText("注册密码需为 10–30 位");
        claim.input.setToolTipText("旧角色首次使用，由管理员提供认领码；新玩家无需填写");
        repeat.input.setToolTipText("再输入一次密码");
        for (Entry field : List.of(name, password, repeat, claim)) {
            field.input.addActionListener(e -> {
                if (!busy && !showingSettings) submit.doClick();
            });
            field.input.getDocument().addDocumentListener(new DocumentListener() {
                private void edited() { field.setError(""); if (error && !busy) clearNotice(); }
                public void insertUpdate(DocumentEvent e) { edited(); }
                public void removeUpdate(DocumentEvent e) { edited(); }
                public void changedUpdate(DocumentEvent e) { edited(); }
            });
        }
        animation = new Timer(16, e -> {
            entrance = Math.min(1, (System.nanoTime() - entranceStart) / 650_000_000f);
            tabPosition += (tabTarget - tabPosition) * .24f;
            if (Math.abs(tabPosition - tabTarget) < .002) tabPosition = tabTarget;
            repaint();
            if (entrance == 1 && tabPosition == tabTarget && !busy) ((Timer) e.getSource()).stop();
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (!isShowing()) animation.stop();
                else if (busy || entrance < 1 || tabPosition != tabTarget) animate();
            }
        });
        setMemoryMb(4096);
        updatePresetSelection();
        updateVisibility();
    }

    static JLabel label(String text, int size, Color color, boolean bold) {
        JLabel result = new JLabel(text);
        result.setFont(bold ? bold(size) : font(size)); result.setForeground(color);
        return result;
    }

    void attach(JFrame frame) {
        attachedFrame = frame;
        close.setToolTipText("关闭");
        minimize.setToolTipText("最小化");
        close.getAccessibleContext().setAccessibleName("关闭启动器");
        minimize.getAccessibleContext().setAccessibleName("最小化启动器");
        close.addActionListener(e -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        minimize.addActionListener(e -> frame.setState(Frame.ICONIFIED));
        MouseAdapter drag = new MouseAdapter() {
            private Point offset;
            public void mousePressed(MouseEvent e) { offset = e.getY() < 58 ? e.getPoint() : null; }
            public void mouseDragged(MouseEvent e) {
                if (offset != null) frame.setLocation(e.getXOnScreen() - offset.x, e.getYOnScreen() - offset.y);
            }
        };
        addMouseListener(drag); addMouseMotionListener(drag);
        frame.getRootPane().setDefaultButton(submit);
        frame.getRootPane().registerKeyboardAction(e -> {
            if (showingSettings) showSettings(false);
            else if (helping) { helping = false; clearNotice(); }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    void onSubmit(Runnable action) { submitAction = action; }
    void onLogs(Runnable action) { logsAction = action; }
    void onResourcePacks(Runnable action) { resourceAction = action; }
    void onSettingsChanged(Runnable action) { settingsChanged = action; }
    boolean registering() { return registering; }
    boolean claiming() { return claiming; }
    boolean showingSettings() { return showingSettings; }
    boolean loggedIn() { return loggedIn; }
    boolean busy() { return busy; }
    int memoryMb() { return memoryMb; }
    PerformancePreset performancePreset() { return preset; }
    boolean resetPresetRequested() { return resetPreset; }
    void presetApplied() { resetPreset = false; }
    void restorePerformanceSettings(PerformancePreset choice, int memory, boolean pendingReset) {
        preset = choice; resetPreset = pendingReset;
        setMemoryMb(memory); updatePresetSelection();
    }
    private void updatePresetSelection() {
        for (var button : presetChoices) {
            button.setSelected(button.preset == preset);
            button.repaint();
        }
        repaint();
    }
    String username() { return name.input.getText().trim(); }
    String claimCode() { return claiming ? claim.input.getText().trim() : ""; }
    char[] password() { return ((JPasswordField) password.input).getPassword(); }
    char[] confirmation() { return ((JPasswordField) repeat.input).getPassword(); }

    void setMemoryMb(int mb) {
        memoryMb = mb;
        for (int i = 0; i < memoryChoices.size(); i++) {
            Action option = memoryChoices.get(i);
            option.selected = LauncherPreferences.MEMORY_CHOICES.get(i) == mb; option.repaint();
        }
        repaint();
    }

    void setRegistering(boolean value) {
        if (busy || loggedIn || registering == value) return;
        registering = value; tabTarget = value ? 1 : 0;
        clearPasswords(); clearErrors(); clearNotice();
        password.placeholder = value ? "设置 10–30 位密码" : "输入密码";
        heading.setText(value ? "开启新的旅程" : "欢迎回来");
        subtitle.setText(value ? "创建 Aster 账号，即可进入游戏。" : "登录你的 Aster 账号，继续旅程。");
        submit.setText(value ? "创建账号" : "登录");
        animate(); updateVisibility(); revalidate(); repaint();
    }

    void showSettings(boolean value) {
        if (busy || !loggedIn) return;
        showingSettings = value;
        heading.setText(value ? (resourceSettings ? "我的材质包" : "启动设置") : "欢迎回来");
        subtitle.setText(value ? (resourceSettings ? "添加喜欢的材质，为冒险换一种风格。" : installingSettings ? "管理客户端入口、文件位置与版本。" : "目标 200 FPS，按电脑性能分配画面预算。") : "登录你的 Aster 账号，继续旅程。");
        clearNotice(); updateVisibility(); revalidate(); repaint();
    }

    void enterDashboard(String username) {
        loggedIn = true; showingSettings = false; busy = false; stage = -1;
        clearPasswords(); clearErrors(); clearNotice();
        dashboard.account(username);
        dashboard.working(false, "");
        homeUpdate.setBusy(false);
        if (attachedFrame != null) attachedFrame.getRootPane().setDefaultButton(dashboard.launch);
        updateVisibility(); revalidate(); repaint();
    }

    void returnToLogin() {
        loggedIn = false; showingSettings = false; busy = false; stage = -1;
        registering = false; claiming = false; tabTarget = 0; tabPosition = 0;
        heading.setText("欢迎回来");
        subtitle.setText("登录你的 Aster 账号，继续旅程。");
        submit.setText("登录");
        password.placeholder = "输入密码";
        claimToggle.setText("已有旧角色？输入认领码");
        clearPasswords(); clearErrors(); clearNotice();
        if (attachedFrame != null) attachedFrame.getRootPane().setDefaultButton(submit);
        updateVisibility(); revalidate(); repaint();
        password.input.requestFocusInWindow();
    }

    void showInstallation(boolean value) {
        if (busy) return;
        resourceSettings = false;
        installingSettings = value;
        showSettings(true);
    }

    void showResourcePacks() {
        if (busy) return;
        resourceSettings = true; installingSettings = false; showSettings(true);
    }

    void showNotice(String text, boolean failure) {
        error = failure; notice.message = text; notice.failure = failure;
        notice.getAccessibleContext().setAccessibleName(text);
        notice.repaint();
    }
    void clearNotice() { helping = false; showNotice("", false); }
    void fieldError(Entry field, String message) {
        busy = false; stage = -1; field.setError(message);
        showNotice("", true); field.input.requestFocusInWindow();
    }
    void clearErrors() { for (Entry field : List.of(name, password, repeat, claim)) field.setError(""); }
    void clearPasswords() {
        password.input.setText(""); repeat.input.setText("");
        password.hidePassword(); repeat.hidePassword();
    }
    void setBusy(int step, String detail) {
        busy = true; stage = step; progressDetail = detail;
        clearErrors(); clearNotice();
        for (Component c : new Component[]{submit, loginTab, registerTab, settings, resourcePacks, help, claimToggle}) c.setEnabled(false);
        for (Entry field : List.of(name, password, repeat, claim)) field.setInputEnabled(false);
        dashboard.working(true, loggedIn ? "正在准备游戏…" : "");
        homeUpdate.setBusy(true);
        submit.setText(step == 1 ? "正在验证账号…" : "正在处理…");
        password.hidePassword(); repeat.hidePassword();
        updateVisibility(); animate(); revalidate(); repaint();
    }
    void busyDetail(int step, String detail) {
        stage = step; progressDetail = detail;
        if (loggedIn) dashboard.launch.setText(detail);
        else submit.setText(detail);
        repaint();
    }
    void finishWithError(String message) {
        busy = false; stage = -1;
        for (Component c : new Component[]{submit, loginTab, registerTab, settings, resourcePacks, help, claimToggle}) c.setEnabled(true);
        for (Entry field : List.of(name, password, repeat, claim)) field.setInputEnabled(true);
        dashboard.working(false, "");
        homeUpdate.setBusy(false);
        submit.setText(registering ? "创建账号" : "登录");
        if (message.isEmpty()) clearNotice(); else showNotice(message, true);
        updateVisibility(); revalidate(); repaint();
    }
    void startEntrance() { entranceStart = System.nanoTime(); entrance = 0; animate(); }
    private void animate() {
        if (!isDisplayable()) { tabPosition = tabTarget; return; }
        if (!animation.isRunning()) animation.start();
    }
    @Override public void removeNotify() { animation.stop(); super.removeNotify(); }

    private void updateVisibility() {
        boolean accountPage = !loggedIn && !showingSettings;
        for (Component c : new Component[]{name, password, submit, loginTab, registerTab, help}) c.setVisible(accountPage);
        repeat.setVisible(accountPage && registering);
        claimToggle.setVisible(accountPage && registering && !busy);
        claim.setVisible(accountPage && registering && claiming && !busy);
        privacy.setVisible(accountPage && !registering);
        heading.setVisible(!loggedIn || showingSettings);
        subtitle.setVisible(!loggedIn || showingSettings);
        dashboard.setVisible(loggedIn && !showingSettings);
        announcements.setVisible(loggedIn && !showingSettings);
        settings.setVisible(loggedIn && !authOnly && !showingSettings);
        logs.setVisible(loggedIn && !authOnly);
        resourcePacks.setVisible(loggedIn && !authOnly && !showingSettings);
        back.setVisible(showingSettings);
        for (Action option : memoryChoices) option.setVisible(showingSettings && !installingSettings && !resourceSettings);
        for (var option : presetChoices) option.setVisible(showingSettings && !installingSettings && !resourceSettings);
        restorePreset.setVisible(showingSettings && !installingSettings && !resourceSettings);
        performanceTab.setVisible(showingSettings); installationTab.setVisible(showingSettings);
        performanceTab.selected = !installingSettings && !resourceSettings; installationTab.selected = installingSettings;
        installation.setVisible(showingSettings && installingSettings);
        homeUpdate.setVisible(loggedIn && !authOnly && !showingSettings);
        resourcePanel.setVisible(showingSettings && resourceSettings);
        settingsDone.setVisible(showingSettings);
        loginTab.selected = !registering; registerTab.selected = registering;
    }

    @Override public void doLayout() {
        int rail = 440, x = getWidth() - rail + 44, w = rail - 88, h = getHeight();
        minimize.setBounds(getWidth() - 90, 9, 36, 32);
        close.setBounds(getWidth() - 48, 9, 36, 32);
        loginTab.setBounds(x, 90, 76, 36); registerTab.setBounds(x + 106, 90, 105, 36);
        back.setBounds(x - 10, 90, 128, 36);
        performanceTab.setBounds(x + 135, 90, 75, 36);
        installationTab.setBounds(x + 217, 90, 135, 36);
        installation.setBounds(x, 230 - Math.max(0, HEIGHT - h) / 2, w, 359);
        dashboard.setBounds(x, 78, w, h - 145);
        announcements.setBounds(52, h - 362, Math.min(610, getWidth() - rail - 96), 132);
        homeUpdate.setBounds(52, h - 160, Math.min(610, getWidth() - rail - 96), 86);
        resourcePanel.setBounds(x, 230 - Math.max(0, HEIGHT - h) / 2, w, 338);
        int headingLift = registering && !showingSettings ? 10 + Math.max(0, HEIGHT - h) / 3 : 0;
        heading.setBounds(x, 157 - headingLift, w, 42); subtitle.setBounds(x, 206 - headingLift, w, 26);
        if (showingSettings) { heading.setBounds(x, 137, w, 42); subtitle.setBounds(x, 184, w, 26); }
        int compactBy = Math.max(0, HEIGHT - h);
        int fieldY = registering ? 240 - compactBy / 2 : 274 - compactBy / 2;
        name.setBounds(x, fieldY, w, 88);
        password.setBounds(x, fieldY + (registering ? 84 : 103), w, 88);
        repeat.setBounds(x, fieldY + 168, w, 88);
        claimToggle.setBounds(x - 10, 494 - compactBy / 2, w + 10, 28);
        claim.setBounds(x, 526 - compactBy / 2, w, 64);
        claim.compact = true;
        privacy.setBounds(x, 479, w, 22);
        help.setBounds(x - 10, 518, 140, 28);
        help.setVisible(!loggedIn && !registering && !showingSettings && !busy);
        int actionY = h - 120;
        notice.setBounds(x, actionY - 55, w, 49);
        if (registering && claiming) notice.setBounds(x, 577 - compactBy / 2, w, actionY - (577 - compactBy / 2) - 4);
        if (loggedIn && !showingSettings)
            notice.setBounds(52, h - 220, Math.min(610, getWidth() - rail - 96), 49);
        submit.setBounds(x, actionY, w, 54);
        settings.setBounds(x - 8, h - 48, 118, 30);
        resourcePacks.setBounds(x + 128, h - 48, 86, 30);
        logs.setBounds(x + w - 106, h - 48, 116, 30);
        for (int i = 0; i < presetChoices.size(); i++)
            presetChoices.get(i).setBounds(x, 230 + i * 70 - compactBy / 2, w, 62);
        for (int i = 0; i < memoryChoices.size(); i++)
            memoryChoices.get(i).setBounds(x + i * 72, 475 - compactBy / 2, 62, 38);
        restorePreset.setBounds(x + w - 132, 522 - compactBy / 2, 140, 32);
        settingsDone.setBounds(x, actionY, w, 54);
        if (showingSettings) notice.setBounds(x, actionY - 58, w, 50);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = graphics(graphics);
        int w = getWidth(), h = getHeight(), left = w - 440;
        if (scene == null || scene.getWidth() != w || scene.getHeight() != h) {
            scene = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D raw = scene.createGraphics();
            Graphics2D s = graphics(raw); raw.dispose();
            double scale = Math.max((double) w / ART.getWidth(), (double) h / ART.getHeight());
            s.drawImage(ART, 0, (h - (int) (ART.getHeight() * scale)) / 2,
                    (int) (ART.getWidth() * scale), (int) (ART.getHeight() * scale), null);
            // Contrast overlays belong to the artwork; form controls sit on an opaque calm surface.
            s.setPaint(new GradientPaint(0, 0, new Color(5, 12, 18, 140), 0, 265, new Color(5, 12, 18, 0)));
            s.fillRect(0, 0, left, 265);
            s.setPaint(new GradientPaint(0, h - 300, new Color(5, 12, 18, 0), 0, h, new Color(5, 12, 18, 224)));
            s.fillRect(0, h - 300, left, 300);
            s.setPaint(new GradientPaint(left - 120, 0, new Color(13, 20, 25, 0), left, 0, BACKGROUND));
            s.fillRect(left - 120, 0, 120, h); s.dispose();
        }
        g.setComposite(AlphaComposite.SrcOver.derive(.55f + .45f * entrance));
        g.drawImage(scene, 0, 0, null);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(BACKGROUND); g.fillRect(left, 0, 440, h);
        g.setColor(GOLD); star(g, 29, 25, 7);
        g.setFont(tracked(10, .16f)); g.setColor(new Color(200, 208, 209));
        g.drawString("ASTER CLIENT", 47, 29);
        g.setFont(font(11)); g.setColor(MUTED); g.drawString(loggedIn ? "账号已验证" : "0.1  内测", left + 44, 29);
        Graphics2D hero = (Graphics2D) g.create();
        hero.setComposite(AlphaComposite.SrcOver.derive(entrance));
        hero.translate(0, (1 - entrance) * 9);
        hero.setColor(TEXT); hero.setFont(tracked(72, .065f)); hero.drawString("ASTER", 48, 158);
        hero.setColor(GOLD); hero.setFont(tracked(13, .40f)); hero.drawString("R P G", 52, 192);
        hero.setColor(new Color(225, 229, 226)); hero.setFont(font(17));
        hero.drawString("于星光之下，启程。", 52, 228);
        if (loggedIn) {
            hero.setColor(GOLD); hero.fillRect(52, h - 382, 30, 2);
            hero.setFont(font(12)); hero.drawString("南湾港  /  官方动态", 95, h - 377);
        } else {
            hero.setColor(GOLD); hero.fillRect(52, h - 182, 30, 2);
            hero.setFont(font(12)); hero.drawString("南湾港  /  初章", 95, h - 177);
            hero.setFont(bold(27)); hero.setColor(TEXT); hero.drawString("你的故事，从这里开始。", 52, h - 128);
            hero.setFont(font(13)); hero.setColor(new Color(183, 198, 201));
            hero.drawString("自由锻造  ·  并肩探索  ·  挑战星塔", 54, h - 94);
            hero.setFont(font(10)); hero.setColor(new Color(143, 161, 171));
            hero.drawString("ASTER RPG  /  SOUTH BAY", 54, h - 28);
            hero.drawString("场景概念宣传图", left - 144, h - 28);
        }
        hero.dispose();
        int x = left + 44, width = 352;
        g.setColor(LINE);
        g.drawLine(x, h - 62, x + width, h - 62);
        if (!showingSettings && !loggedIn) {
            g.drawLine(x, 137, x + width, 137);
            g.setColor(GOLD); g.fillRoundRect(x + Math.round(tabPosition * 106), 135, registering ? 97 : 65, 3, 2, 2);
            if (busy) paintProgress(g, x, h - 171, width);
        } else if (showingSettings && !installingSettings && !resourceSettings) {
            int lift = Math.max(0, HEIGHT - h) / 2;
            g.setColor(TEXT); g.setFont(bold(14)); g.drawString("游戏内存", x, 460 - lift);
            g.setFont(font(12)); g.setColor(MUTED);
            String memoryNote = "该档推荐 " + preset.memoryMb() / 1024 + " GB";
            g.drawString(memoryNote, x + width - g.getFontMetrics().stringWidth(memoryNote), 460 - lift);
            g.drawString(resetPreset ? "下次启动恢复默认" : "下次启动生效", x, 544 - lift);
            g.setFont(font(12));
            if (notice.message.isEmpty()) g.drawString(preset.summary(), x, h - 141);
        }
        g.dispose();
    }

    static final class PresetChoice extends JButton {
        final PerformancePreset preset;
        PresetChoice(PerformancePreset preset) {
            this.preset = preset;
            setText(preset.title());
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(preset.audience() + "；" + preset.summary()
                    + "；帧率上限 " + preset.options().get("maxFps").getAsString() + "，为 200 FPS 目标保留余量");
            getAccessibleContext().setAccessibleName(preset.title() + "，" + preset.audience());
            addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { repaint(); }
                public void focusLost(FocusEvent e) { repaint(); }
            });
            setRolloverEnabled(true);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            if (isSelected() || getModel().isRollover() || isFocusOwner()) {
                g.setColor(isSelected() ? new Color(42, 41, 35) : FIELD);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 7, 7);
            }
            if (isSelected()) { g.setColor(GOLD); g.fillRoundRect(0, 10, 3, getHeight() - 20, 2, 2); }
            g.setFont(bold(15)); g.setColor(isSelected() ? GOLD : TEXT);
            g.drawString(preset.title(), 16, 25);
            g.setFont(font(12)); g.setColor(MUTED);
            g.drawString(preset.audience(), 16, 47);
            String memory = preset.memoryMb() / 1024 + " GB";
            g.setColor(isSelected() ? GOLD : MUTED);
            g.drawString(memory, getWidth() - g.getFontMetrics().stringWidth(memory) - 16, 25);
            if (isFocusOwner()) { g.setColor(GOLD); g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 7, 7); }
            g.dispose();
        }
    }

    private void paintProgress(Graphics2D g, int x, int y, int width) {
        // These are real process stages, not a fabricated percentage.
        String[] labels = {"准备客户端", "验证账号", "启动游戏"};
        int stepWidth = width / 3;
        for (int i = 0; i < 3; i++) {
            g.setColor(i <= stage ? GOLD : LINE);
            g.fillRoundRect(x + i * stepWidth, y, stepWidth - 6, 2, 2, 2);
            g.setFont(font(11)); g.setColor(i <= stage ? GOLD : MUTED);
            g.drawString(labels[i], x + i * stepWidth, y + 20);
        }
        g.setColor(MUTED); g.setFont(font(10)); g.drawString(progressDetail, x, y + 40);
    }

    static final class HomeUpdate extends JPanel {
        final Action check = new Action("检查更新", "arrow", false);
        final Action apply = new Action("重启并更新", "arrow", true);
        private final JLabel version = label("当前版本 · 读取中", 11, MUTED, false);
        private final JTextArea status = new JTextArea("等待检查更新");
        private boolean working, ready, busy;
        private double downloadProgress = -1;

        HomeUpdate() {
            setLayout(null); setOpaque(false);
            add(version); add(status); add(check); add(apply);
            status.setEditable(false); status.setFocusable(false); status.setOpaque(false);
            status.setFont(font(12)); status.setForeground(TEXT);
            status.setLineWrap(true); status.setWrapStyleWord(true);
            check.selected = true;
            apply.spinWhenDisabled = false;
            status("等待检查更新", false, false);
        }

        void configure(String release) { version.setText("当前版本 · " + release); }

        void status(String text, boolean working, boolean ready) {
            this.working = working; this.ready = ready;
            status.setText(text); status.setToolTipText(text);
            status.setForeground(text.startsWith("更新未完成") || text.startsWith("更新设置读取失败")
                    ? ERROR_COLOR : ready ? GOLD : TEXT);
            check.setText(text.startsWith("更新未完成") || text.startsWith("更新设置读取失败")
                    ? "重试更新" : working ? "正在更新…" : "检查更新");
            var numbers = java.util.regex.Pattern.compile("下载更新 (\\d+) / (\\d+) MiB").matcher(text);
            downloadProgress = numbers.find() && Long.parseLong(numbers.group(2)) > 0
                    ? Math.min(1, (double) Long.parseLong(numbers.group(1)) / Long.parseLong(numbers.group(2))) : -1;
            refreshActions(); repaint();
        }

        void setBusy(boolean value) { busy = value; refreshActions(); }

        private void refreshActions() {
            check.setVisible(!ready);
            check.setEnabled(!working && !busy);
            apply.setVisible(ready);
            apply.setEnabled(!working && !busy);
        }

        @Override public void doLayout() {
            int actionX = getWidth() - 178;
            version.setBounds(126, 11, actionX - 136, 22);
            status.setBounds(18, 40, actionX - 28, 34);
            check.setBounds(actionX, 21, 160, 43);
            apply.setBounds(actionX, 21, 160, 43);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            g.setColor(new Color(8, 17, 23, 226));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 9, 9);
            g.setColor(new Color(115, 128, 131, 110));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 9, 9);
            g.setColor(GOLD); g.setFont(bold(13)); g.drawString("客户端更新", 18, 27);
            g.setColor(LINE); g.fillRoundRect(18, 76, getWidth() - 214, 2, 2, 2);
            if (downloadProgress >= 0 || ready) {
                g.setColor(GOLD);
                g.fillRoundRect(18, 76, (int) Math.round((getWidth() - 214) * (ready ? 1 : downloadProgress)), 2, 2, 2);
            }
            g.dispose();
        }
    }

    static final class Action extends JButton {
        boolean spinWhenDisabled = true;
        private final boolean primary;
        private final String icon;
        boolean selected;
        private float hover;
        private boolean inside;
        private final Timer tween;

        Action(String text, String icon, boolean primary) {
            super(text); this.primary = primary; this.icon = icon;
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
            setFont(primary ? bold(16) : font(13)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(0, 0, 0, 0));
            tween = new Timer(16, e -> {
                float goal = inside && isEnabled() ? 1 : 0;
                hover += (goal - hover) * .3f;
                if (Math.abs(goal - hover) < .02f) { hover = goal; ((Timer) e.getSource()).stop(); }
                repaint();
            });
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { inside = true; tween.start(); }
                public void mouseExited(MouseEvent e) { inside = false; tween.start(); }
            });
            addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { repaint(); }
                public void focusLost(FocusEvent e) { repaint(); }
            });
        }
        @Override public void removeNotify() { tween.stop(); super.removeNotify(); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            int w = getWidth(), h = getHeight();
            if (primary) {
                g.setColor(!isEnabled() ? new Color(84, 79, 65) :
                        getModel().isPressed() ? new Color(192, 160, 109) : mix(GOLD, new Color(243, 220, 180), hover));
                g.fillRoundRect(0, 0, w, h, 9, 9);
            } else if (selected && !icon.isEmpty() || hover > 0 || selected && getText().endsWith("GB")) {
                g.setColor(selected ? new Color(52, 49, 40) : mix(BACKGROUND, FIELD, hover));
                g.fillRoundRect(0, 0, w, h, 7, 7);
            }
            Color fg = primary ? new Color(25, 27, 25) : !isEnabled() ? new Color(91, 104, 110) :
                    selected ? GOLD : hover > .2 || isFocusOwner() ? TEXT : MUTED;
            g.setColor(fg); g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            if (primary) {
                g.drawString(getText(), 21, (h - fm.getHeight()) / 2 + fm.getAscent());
                if (isEnabled()) icon(g, icon, w - 43, (h - 22) / 2, 22);
                else if (spinWhenDisabled) {
                    int angle = (int) ((System.nanoTime() / 4_000_000) % 360);
                    g.setStroke(new BasicStroke(2)); g.drawArc(w - 41, (h - 18) / 2, 18, 18, angle, 255);
                }
            } else {
                int total = fm.stringWidth(getText()) + (icon.isEmpty() ? 0 : getText().isEmpty() ? 20 : 30);
                int x = Math.max(6, (w - total) / 2);
                if (!icon.isEmpty()) { icon(g, icon, x, (h - 18) / 2, 18); x += 28; }
                g.drawString(getText(), x, (h - fm.getHeight()) / 2 + fm.getAscent());
            }
            if (isFocusOwner() || selected && getText().endsWith("GB")) {
                g.setColor(GOLD); g.setStroke(new BasicStroke(1)); g.drawRoundRect(1, 1, w - 3, h - 3, 8, 8);
            }
            g.dispose();
        }
    }

    static final class Entry extends JPanel {
        final JTextField input;
        final Action reveal;
        private final String caption;
        private String error = "";
        boolean compact;
        private boolean visiblePassword;
        String placeholder;
        Entry(String caption, boolean secret) {
            this.caption = caption; setLayout(null); setOpaque(false);
            placeholder = switch (caption) {
                case "游戏名" -> "英文字母、数字或下划线";
                case "确认密码" -> "再次输入密码";
                case "旧角色认领码" -> "由管理员提供，新玩家无需填写";
                default -> "输入密码";
            };
            input = secret ? new JPasswordField() : new JTextField();
            input.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            input.setOpaque(false); input.setForeground(TEXT); input.setFont(font(15));
            input.setCaretColor(GOLD); input.setSelectionColor(new Color(102, 88, 62));
            input.setSelectedTextColor(TEXT); input.setDisabledTextColor(MUTED);
            input.getAccessibleContext().setAccessibleName(caption);
            if (secret) ((JPasswordField) input).setEchoChar('•');
            input.addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { repaint(); }
                public void focusLost(FocusEvent e) { repaint(); }
            });
            add(input);
            reveal = secret ? new Action("", "eye", false) : null;
            if (reveal != null) {
                reveal.setToolTipText("显示或隐藏密码");
                reveal.getAccessibleContext().setAccessibleName("显示密码");
                reveal.addActionListener(e -> {
                    visiblePassword = !visiblePassword;
                    ((JPasswordField) input).setEchoChar(visiblePassword ? (char) 0 : '•');
                    reveal.getAccessibleContext().setAccessibleName(visiblePassword ? "隐藏密码" : "显示密码");
                    reveal.selected = visiblePassword; reveal.repaint();
                }); add(reveal);
            }
        }
        void hidePassword() {
            if (reveal != null) {
                visiblePassword = false; ((JPasswordField) input).setEchoChar('•');
                reveal.selected = false; reveal.getAccessibleContext().setAccessibleName("显示密码"); reveal.repaint();
            }
        }
        void setError(String text) { error = text; input.getAccessibleContext().setAccessibleDescription(text); repaint(); }
        String error() { return error; }
        void setInputEnabled(boolean enabled) { input.setEnabled(enabled); if (reveal != null) reveal.setEnabled(enabled); }
        @Override public void doLayout() {
            int top = compact ? 0 : 24;
            input.setBounds(14, top + 2, getWidth() - (reveal == null ? 28 : 60), 42);
            if (reveal != null) reveal.setBounds(getWidth() - 43, top + 6, 34, 34);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            int top = compact ? 0 : 24, width = getWidth();
            if (!compact) { g.setFont(font(12)); g.setColor(MUTED); g.drawString(caption, 0, 15); }
            g.setColor(FIELD); g.fillRoundRect(0, top, width, 47, 8, 8);
            g.setColor(!error.isEmpty() ? ERROR_COLOR : input.isFocusOwner() ? GOLD : LINE);
            g.drawRoundRect(0, top, width - 1, 47, 8, 8);
            if (input.getDocument().getLength() == 0) {
                g.setColor(new Color(108, 123, 131)); g.setFont(font(13));
                g.drawString(placeholder, 17, top + 29);
            }
            if (!error.isEmpty()) { g.setFont(font(10)); g.setColor(ERROR_COLOR); g.drawString(error, 0, top + 62); }
            g.dispose();
        }
    }

    static final class Notice extends JComponent {
        String message = "";
        boolean failure;
        @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {};
            return accessibleContext;
        }
        @Override protected void paintComponent(Graphics graphics) {
            if (message.isEmpty()) return;
            Graphics2D g = graphics(graphics);
            g.setColor(failure ? ERROR_COLOR : MUTED);
            if (failure) icon(g, "warning", 0, 2, 16);
            int x = failure ? 23 : 0;
            AttributedString text = new AttributedString(message);
            text.addAttribute(TextAttribute.FONT, font(11));
            LineBreakMeasurer lines = new LineBreakMeasurer(text.getIterator(), g.getFontRenderContext());
            float y = 0;
            while (lines.getPosition() < message.length()) {
                TextLayout line = lines.nextLayout(getWidth() - x);
                y += line.getAscent();
                line.draw(g, x, y);
                y += line.getDescent() + line.getLeading() + 4;
            }
            g.dispose();
        }
    }
}
