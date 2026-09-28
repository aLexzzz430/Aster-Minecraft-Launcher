Unicode true
!include LogicLib.nsh
!include FileFunc.nsh
Name AsterRPG
OutFile "${OUTPUT}"
Icon "${BRAND}/aster.ico"
RequestExecutionLevel user
SilentInstall silent
AutoCloseWindow true
VIProductVersion "1.0.0.0"
VIAddVersionKey ProductName AsterRPG
VIAddVersionKey FileDescription "AsterRPG 客户端"
VIAddVersionKey FileVersion "1.0.0.0"
VIAddVersionKey LegalCopyright AsterRPG
Var Root
Var Source
Var Mutex
Var Tries
Var Params
Var WaitPid
Section
    SetShellVarContext current
    SetRegView 64
    StrCpy $Root "$EXEDIR"
    ReadINIStr $0 "$Root\client.ini" "migration" "redirect"
    ${If} $0 != ""
        Exec '"$0\AsterRPG.exe"'
        Quit
    ${EndIf}
    System::Call 'kernel32::CreateMutexW(p 0, i 0, w "Local\AsterRPG-ClientBootstrap") p.r0 ?e'
    StrCpy $Mutex $0
    Pop $1
    ${If} $1 == 183
        MessageBox MB_ICONINFORMATION "AsterRPG 正在启动，请稍候。"
        Quit
    ${EndIf}
    StrCpy $Source "$Root"
    ReadINIStr $0 "$Root\client.ini" "migration" "from"
    ${If} $0 != ""
        StrCpy $Source $0
    ${EndIf}
    ${GetParameters} $Params
    ${GetOptions} $Params "/WAIT=" $WaitPid
    ${If} $WaitPid != ""
        System::Call 'kernel32::OpenProcess(i 0x100000, i 0, i $WaitPid) p.r0'
        ${If} $0 != 0
            StrCpy $1 $0
            System::Call 'kernel32::WaitForSingleObject(p r1, i 60000) i.r0'
            System::Call 'kernel32::CloseHandle(p r1)'
            ${If} $0 != 0
                MessageBox MB_ICONSTOP "启动器仍在退出，请完全关闭后重新打开。"
                Quit
            ${EndIf}
        ${EndIf}
    ${EndIf}
    StrCpy $Tries 0
  wait_lock:
    IfFileExists "$Source\client.lock" 0 lock_free
    System::Call 'kernel32::CreateFileW(w "$Source\client.lock", i 0xC0000000, i 0, p 0, i 3, i 128, p 0) p.r0'
    ${If} $0 != -1
        System::Call 'kernel32::CloseHandle(p r0)'
        Goto lock_free
    ${EndIf}
    ${If} $WaitPid == ""
        MessageBox MB_ICONINFORMATION "AsterRPG 已经打开，请切换到游戏或启动器窗口。"
        Quit
    ${EndIf}
    IntOp $Tries $Tries + 1
    ${If} $Tries > 120
        MessageBox MB_ICONSTOP "客户端还未完全退出，请关闭游戏和启动器后重新打开。"
        Quit
    ${EndIf}
    Sleep 500
    Goto wait_lock
  lock_free:
    ${If} $Source != $Root
        WriteINIStr "$Source\client.ini" "migration" "redirect" "$Root"
        DeleteINISec "$Root\client.ini" "migration"
        ; Keep the tiny old entrypoint so existing taskbar pins can forward to the new location.
        RMDir /r "$Source\app"
        RMDir /r "$Source\game"
        RMDir /r "$Source\logs"
        RMDir /r "$Source\updates"
        RMDir /r "$Source\app.next"
        RMDir /r "$Source\app.previous"
        Delete "$Source\launcher.properties"
        Delete "$Source\client-settings.properties"
        Delete "$Source\game-launch.args"
        Delete "$Source\Uninstall.exe"
    ${EndIf}
    SetOutPath "$Root"
    ; Recover the old application if a previous directory switch was interrupted.
    IfFileExists "$Root\app\client-manifest.json" ready_check
    IfFileExists "$Root\app.previous\client-manifest.json" 0 ready_check
    Rename "$Root\app.previous" "$Root\app"
  ready_check:
    IfFileExists "$Root\updates\ready.ini" 0 launch
    ReadINIStr $2 "$Root\updates\ready.ini" "update" "release"
    IfFileExists "$Root\app.next\client-manifest.json" begin_switch
    ReadINIStr $3 "$Root\app\update-release.ini" client release
    StrCmp $3 $2 finish_switch update_failed
  begin_switch:
    RMDir /r "$Root\app.previous"
    ClearErrors
    Rename "$Root\app" "$Root\app.previous"
    IfErrors update_failed
    ClearErrors
    Rename "$Root\app.next" "$Root\app"
    IfErrors restore
  finish_switch:
    WriteINIStr "$Root\client.ini" "client" "release" "$2"
    Delete "$Root\updates\ready.ini"
    RMDir /r "$Root\app.previous"
    Goto launch
  restore:
    Rename "$Root\app.previous" "$Root\app"
  update_failed:
    MessageBox MB_ICONSTOP "更新文件仍被占用。请关闭 AsterRPG 后重试，已下载的更新会保留。"
    Quit
  launch:
    WriteRegStr HKCU "Software\AsterRPG" InstallDir "$Root"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" InstallLocation "$Root"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" UninstallString '$\"$Root\Uninstall.exe$\"'
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" DisplayIcon "$Root\aster.ico"
    ReadINIStr $2 "$Root\client.ini" "client" "release"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" DisplayVersion "$2"
    ; ReadINIStr may set NSIS's persistent error flag when a key is absent.
    ; Only the Exec result may decide whether the launch failed.
    ClearErrors
    Exec '"$Root\app\runtime\bin\javaw.exe" -Xmx256M -Dfile.encoding=UTF-8 -cp "$Root\app\launcher\*" cn.aster.launcher.Main "$Root"'
    IfErrors 0 done
    MessageBox MB_ICONSTOP "无法启动 AsterRPG，请重新安装客户端。玩家数据位于 game 文件夹。"
  done:
    System::Call 'kernel32::CloseHandle(p $Mutex)'
SectionEnd
