package cn.aster.launcher;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.PointerByReference;
import javax.swing.Timer;
import java.nio.file.*;
import java.util.function.Consumer;

/** User-scoped shell integration, including the Windows-consented taskbar API. */
final class WindowsIntegration {
    static final String APP_ID = "AsterRPG.Client";
    static boolean available() { return Platform.isWindows(); }
    interface RuntimeApi extends Library {
        int RoInitialize(int type);
        int WindowsCreateString(WString text, int length, PointerByReference result);
        int WindowsDeleteString(Pointer value);
        int RoGetActivationFactory(Pointer name, Pointer iid, PointerByReference result);
    }
    static void identity() {
        if (!available()) return;
        check(NativeLibrary.getInstance("shell32").getFunction("SetCurrentProcessExplicitAppUserModelID")
                .invokeInt(new Object[]{new WString(APP_ID)}));
        // Swing uses a single UI thread; shell links and pin requests remain on that apartment.
        check(NativeLibrary.getInstance("ole32").getFunction("CoInitializeEx").invokeInt(new Object[]{null, 2}));
    }
    static Path shortcut(Path root, boolean desktop) throws Exception {
        Path directory = Path.of(Shell32Util.getKnownFolderPath(desktop ? KnownFolders.FOLDERID_Desktop : KnownFolders.FOLDERID_Programs));
        if (!desktop) directory = directory.resolve("AsterRPG");
        Files.createDirectories(directory);
        Path path = directory.resolve("AsterRPG.lnk");
        PointerByReference out = new PointerByReference();
        check(NativeLibrary.getInstance("ole32").getFunction("CoCreateInstance").invokeInt(new Object[]{
                guid("00021401-0000-0000-C000-000000000046"), null, 1,
                guid("000214F9-0000-0000-C000-000000000046"), out}));
        Pointer link = out.getValue();
        try {
            call(link, 20, new WString(root.resolve("AsterRPG.exe").toString()));
            call(link, 9, new WString(root.toString()));
            call(link, 7, new WString("AsterRPG · 南湾港"));
            call(link, 17, new WString(root.resolve("aster.ico").toString()), 0);
            Pointer props = query(link, "886d8eeb-8cf2-4446-8d02-cdba1dbdcf99");
            try {
                Memory key = new Memory(20); key.write(0, guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3").getByteArray(0, 16), 0, 16); key.setInt(16, 5);
                Memory value = new Memory(24); value.clear(); value.setShort(0, (short)31);
                Memory text = new Memory((APP_ID.length() + 1L) * Native.WCHAR_SIZE); text.setWideString(0, APP_ID);
                value.setPointer(8, text); call(props, 6, key, value); call(props, 7);
            } finally { release(props); }
            Pointer persist = query(link, "0000010b-0000-0000-C000-000000000046");
            try { call(persist, 6, new WString(path.toString()), 1); } finally { release(persist); }
        } finally { release(link); }
        NativeLibrary.getInstance("kernel32").getFunction("WritePrivateProfileStringW").invokeInt(new Object[]{
                new WString("shortcuts"), new WString(desktop ? "desktop" : "start"), new WString("1"), new WString(root.resolve("client.ini").toString())});
        return path;
    }
    static void repairExisting(Path root) throws Exception {
        var api = NativeLibrary.getInstance("kernel32").getFunction("GetPrivateProfileIntW");
        for (boolean desktop : new boolean[]{true, false}) {
            int enabled = api.invokeInt(new Object[]{new WString("shortcuts"), new WString(desktop ? "desktop" : "start"), 0,
                    new WString(root.resolve("client.ini").toString())});
            if (enabled == 1) shortcut(root, desktop);
        }
    }
    static void pin(Path root, Consumer<String> result) throws Exception {
        shortcut(root, false);
        RuntimeApi runtime = Native.load("combase", RuntimeApi.class);
        check(runtime.RoInitialize(0));
        String name = "Windows.UI.Shell.TaskbarManager";
        PointerByReference h = new PointerByReference(); check(runtime.WindowsCreateString(new WString(name), name.length(), h));
        Pointer manager;
        try {
            PointerByReference factory = new PointerByReference();
            check(runtime.RoGetActivationFactory(h.getValue(), guid("cdfefd63-e879-4134-b9a7-8283f05f9480"), factory));
            release(factory.getValue());
            check(runtime.RoGetActivationFactory(h.getValue(), guid("db32ab74-de52-4fe6-b7b6-95ff9f8395df"), factory));
            try { PointerByReference value = new PointerByReference(); call(factory.getValue(), 6, value); manager = value.getValue(); }
            finally { release(factory.getValue()); }
        } finally { runtime.WindowsDeleteString(h.getValue()); }
        Memory supported = new Memory(1), allowed = new Memory(1);
        try {
            call(manager, 6, supported); call(manager, 7, allowed);
            if (supported.getByte(0) == 0 || allowed.getByte(0) == 0)
                throw new IllegalStateException("当前 Windows 不支持应用请求固定，或已被系统策略禁用");
            PointerByReference op = new PointerByReference(); call(manager, 8, op);
            await(op.getValue(), pinned -> {
                if (pinned) { release(manager); result.accept("AsterRPG 已固定到任务栏"); }
                else {
                    try {
                        PointerByReference request = new PointerByReference(); call(manager, 10, request);
                        await(request.getValue(), yes -> result.accept(yes ? "已固定到任务栏" : "未固定：你取消了请求，或 Windows 未允许此操作"), result);
                    } catch (Exception error) { result.accept("此 Windows 需要手动固定：右键开始菜单中的 AsterRPG → 更多 → 固定到任务栏"); }
                    finally { release(manager); }
                }
            }, message -> { release(manager); result.accept(message); });
        } catch (Exception error) { release(manager); throw error; }
    }
    private static void await(Pointer operation, Consumer<Boolean> done, Consumer<String> failure) {
        Pointer info = query(operation, "00000036-0000-0000-C000-000000000046");
        Timer timer = new Timer(100, null);
        timer.addActionListener(e -> {
            Memory status = new Memory(4);
            try {
                call(info, 7, status); if (status.getInt(0) == 0) return;
                timer.stop();
                if (status.getInt(0) == 1) { Memory answer = new Memory(1); call(operation, 8, answer); done.accept(answer.getByte(0) != 0); }
                else failure.accept("Windows 未完成固定，请右键开始菜单中的 AsterRPG → 更多 → 固定到任务栏");
            } catch (Exception error) { timer.stop(); failure.accept("Windows 未允许固定，请使用开始菜单的右键操作"); }
            finally { if (!timer.isRunning()) { release(info); release(operation); } }
        });
        timer.start();
    }
    static void openStartMenu() throws Exception {
        new ProcessBuilder("explorer.exe", Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Programs), "AsterRPG").toString()).start();
    }
    private static Pointer guid(String id) { Guid.GUID value = new Guid.GUID(id); value.write(); return value.getPointer(); }
    private static Pointer query(Pointer p, String id) { PointerByReference out = new PointerByReference(); call(p, 0, guid(id), out); return out.getValue(); }
    private static void call(Pointer p, int slot, Object... values) {
        Object[] args = new Object[values.length + 1]; args[0] = p; System.arraycopy(values, 0, args, 1, values.length);
        check(Function.getFunction(p.getPointer(0).getPointer((long)slot * Native.POINTER_SIZE), Function.ALT_CONVENTION).invokeInt(args));
    }
    private static void release(Pointer p) { Function.getFunction(p.getPointer(0).getPointer(2L * Native.POINTER_SIZE), Function.ALT_CONVENTION).invokeInt(new Object[]{p}); }
    private static void check(int hr) { if (hr < 0) throw new IllegalStateException("Windows 接口暂不可用 (0x" + Integer.toHexString(hr) + ")"); }
}
