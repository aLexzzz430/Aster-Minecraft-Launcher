package cn.aster.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import static cn.aster.launcher.LauncherTheme.*;

final class ResourcePackPanel extends JPanel {
    final LauncherView.Action addPack = new LauncherView.Action("导入材质包", "arrow", true);
    final LauncherView.Action openFolder = new LauncherView.Action("打开材质包文件夹", "", false);
    final LauncherView.Action toggle = new LauncherView.Action("选择一个材质包", "", false);
    final LauncherView.Action refresh = new LauncherView.Action("刷新列表", "", false);
    final JCheckBox autoEnable = new JCheckBox("导入后自动启用（下次启动生效）", true);
    final DefaultListModel<ResourcePacks.Pack> model = new DefaultListModel<>();
    final JList<ResourcePacks.Pack> packs = new JList<>(model);
    private final JLabel empty = LauncherView.label("选择 ZIP 或文件夹，也可以直接拖入", 12, MUTED, false);
    ResourcePackPanel() {
        setLayout(null); setOpaque(false);
        addPack.setBounds(0, 0, 180, 43); openFolder.setBounds(187, 0, 165, 43);
        autoEnable.setBounds(0, 52, 352, 26); autoEnable.setOpaque(false); autoEnable.setForeground(MUTED); autoEnable.setFont(font(12));
        autoEnable.setFocusPainted(false);
        var title = LauncherView.label("我的材质包", 13, TEXT, true); title.setBounds(0, 92, 352, 24);
        packs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); packs.setFixedCellHeight(39);
        packs.setBackground(FIELD); packs.setForeground(TEXT); packs.setFont(font(12));
        packs.setCellRenderer((list, pack, index, selected, focus) -> {
            JLabel row = new JLabel((pack.enabled() ? "已启用  ·  " : "未启用  ·  ") + pack.name());
            row.setFont(font(12)); row.setForeground(selected ? GOLD : TEXT); row.setOpaque(true);
            row.setBackground(selected ? new Color(42, 41, 35) : FIELD);
            row.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 8)); row.setToolTipText(pack.name());
            return row;
        });
        packs.addListSelectionListener(e -> selection());
        JScrollPane scroll = new JScrollPane(packs); scroll.setBounds(0, 121, 352, 119);
        scroll.setBorder(BorderFactory.createLineBorder(LINE)); scroll.getVerticalScrollBar().setUnitIncrement(39);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        empty.setBounds(16, 173, 326, 24);
        toggle.setBounds(0, 248, 174, 34); refresh.setBounds(198, 248, 154, 34);
        var hint = LauncherView.label("顺序调整：游戏内「选项 → 资源包」", 11, MUTED, false); hint.setBounds(0, 285, 352, 24);
        for (Component c : new Component[]{addPack, openFolder, autoEnable, title, empty, scroll, toggle, refresh, hint}) add(c);
        setComponentZOrder(empty, 0); selection();
    }
    void setPacks(List<ResourcePacks.Pack> values) {
        String selected = packs.getSelectedValue() == null ? "" : packs.getSelectedValue().name();
        model.clear(); values.forEach(model::addElement); empty.setVisible(values.isEmpty());
        for (int i = 0; i < model.size(); i++) if (model.get(i).name().equals(selected)) packs.setSelectedIndex(i);
        selection();
    }
    private void selection() {
        var selected = packs.getSelectedValue(); toggle.setEnabled(selected != null && packs.isEnabled());
        toggle.setText(selected == null ? "选择一个材质包" : selected.enabled() ? "停用材质包" : "启用材质包");
    }
    void working(boolean value) {
        for (Component c : new Component[]{addPack, openFolder, autoEnable, packs, refresh}) c.setEnabled(!value);
        addPack.setText(value ? "正在导入…" : "导入材质包"); selection();
    }
    void onDrop(Consumer<List<Path>> action, Consumer<String> failure) {
        TransferHandler handler = new TransferHandler() {
            @Override public boolean canImport(TransferSupport data) { return addPack.isEnabled() && data.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override public boolean importData(TransferSupport data) {
                if (!canImport(data)) return false;
                try {
                    @SuppressWarnings("unchecked") List<File> files = (List<File>)data.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    action.accept(files.stream().map(File::toPath).toList()); return true;
                } catch (Exception error) { failure.accept("无法导入拖入的文件：" + error.getMessage()); return false; }
            }
        };
        setTransferHandler(handler); packs.setTransferHandler(handler); empty.setTransferHandler(handler);
    }
}
