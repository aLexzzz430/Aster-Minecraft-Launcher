Unicode true
!include MUI2.nsh
!include LogicLib.nsh
!include WinVer.nsh
!include x64.nsh
!include WordFunc.nsh
!include nsDialogs.nsh
Name AsterRPG
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\AsterRPG"
InstallDirRegKey HKCU "Software\AsterRPG" InstallDir
RequestExecutionLevel user
ManifestDPIAware true
SetCompressor /SOLID lzma
SetCompressorDictSize 32
ShowInstDetails nevershow
BrandingText "AsterRPG · 南湾港"
Caption "AsterRPG · 安装客户端"
SetFont "Microsoft YaHei UI" 9
VIProductVersion "${RELEASE}"
VIAddVersionKey ProductName AsterRPG
VIAddVersionKey FileDescription "AsterRPG 客户端安装器"
VIAddVersionKey FileVersion "${RELEASE}"
VIAddVersionKey LegalCopyright AsterRPG
!define MUI_ICON "${BRAND}/aster.ico"
!define MUI_UNICON "${BRAND}/aster.ico"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_RIGHT
!define MUI_HEADERIMAGE_BITMAP "${BRAND}/header.bmp"
!define MUI_HEADERIMAGE_BITMAP_STRETCH AspectFitHeight
!define MUI_WELCOMEPAGE_TITLE "安装 AsterRPG"
!define MUI_WELCOMEPAGE_TEXT "选择安装位置和快捷入口。安装完成后登录 Aster 账号即可进入游戏。$\r$\n$\r$\n后续版本将在启动器中自动下载；模组更新在游戏退出后生效。"
!insertmacro MUI_PAGE_WELCOME
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE DirectoryLeave
!insertmacro MUI_PAGE_DIRECTORY
Page custom OptionsPage OptionsLeave
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "打开 AsterRPG"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchClient
!define MUI_FINISHPAGE_TEXT "安装完成。启动器首页会显示客户端版本、下载进度和更新入口。安装路径与快捷入口可在启动设置 → 安装与更新中管理。$\r$\n$\r$\n固定到任务栏需要在启动器中点击，由 Windows 确认。"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE SimpChinese
Var OldDir
Var DesktopCheck
Var StartCheck
Var DesktopValue
Var StartValue
Var Dialog
Var InstallLock
Function .onInit
    SetShellVarContext current
    SetRegView 64
    ${IfNot} ${RunningX64}
        MessageBox MB_ICONSTOP "需要 64 位 Windows 10 或更新系统。"
        Abort
    ${EndIf}
    ${IfNot} ${AtLeastWin10}
        MessageBox MB_ICONSTOP "需要 Windows 10 或更新系统。"
        Abort
    ${EndIf}
    ReadRegStr $0 HKCU "Software\AsterRPG" InstallDir
    ${If} $0 != ""
        StrCpy $INSTDIR $0
    ${EndIf}
    StrCpy $OldDir ""
    IfFileExists "$INSTDIR\app\client-manifest.json" 0 done_init
    StrCpy $OldDir "$INSTDIR"
    ReadINIStr $0 "$INSTDIR\client.ini" client release
    ${VersionCompare} $0 "${RELEASE}" $1
    ${If} $1 != 2
        IfFileExists "$INSTDIR\AsterRPG.exe" 0 done_init
        Call LaunchClient
        Quit
    ${EndIf}
  done_init:
FunctionEnd
Function DirectoryLeave
    ${If} $OldDir != ""
    ${AndIf} $INSTDIR != $OldDir
        MessageBox MB_ICONINFORMATION "升级会保留原安装位置。需要搬迁时，请安装后在启动设置 → 安装与更新中选择更改安装路径。"
        StrCpy $INSTDIR "$OldDir"
        Abort
    ${EndIf}
    ${If} $InstallLock != ""
        System::Call 'kernel32::CloseHandle(p $InstallLock)'
        StrCpy $InstallLock ""
    ${EndIf}
    CreateDirectory "$INSTDIR"
    System::Call 'kernel32::CreateFileW(w "$INSTDIR\client.lock", i 0xC0000000, i 0, p 0, i 4, i 128, p 0) p.r0'
    ${If} $0 == -1
        MessageBox MB_ICONSTOP "请关闭 AsterRPG 游戏和登录界面后安装。"
        Abort
    ${EndIf}
    StrCpy $InstallLock $0
FunctionEnd
Function OptionsPage
    !insertmacro MUI_HEADER_TEXT "选择快捷入口" "这些设置也可以在启动器中修改。"
    nsDialogs::Create 1018
    Pop $Dialog
    ${NSD_CreateCheckbox} 8u 15u 270u 18u "创建桌面快捷方式"
    Pop $DesktopCheck
    ${NSD_Check} $DesktopCheck
    ${NSD_CreateCheckbox} 8u 46u 270u 18u "添加到开始菜单"
    Pop $StartCheck
    ${NSD_Check} $StartCheck
    ${NSD_CreateLabel} 8u 86u 280u 45u "任务栏固定：安装后在启动器的“安装与更新”中点击“固定到任务栏”，由 Windows 确认。"
    Pop $0
    nsDialogs::Show
FunctionEnd
Function OptionsLeave
    ${NSD_GetState} $DesktopCheck $DesktopValue
    ${NSD_GetState} $StartCheck $StartValue
FunctionEnd
Function LaunchClient
    ${If} $InstallLock != ""
        System::Call 'kernel32::CloseHandle(p $InstallLock)'
        StrCpy $InstallLock ""
    ${EndIf}
    Exec '"$INSTDIR\AsterRPG.exe"'
FunctionEnd
Section AsterRPG
    SetOutPath "$INSTDIR"
    RMDir /r "$INSTDIR\app.next"
    SetOutPath "$INSTDIR\app.next"
    File /r "${PAYLOAD}/*"
    SetOutPath "$INSTDIR"
    IfFileExists "$INSTDIR\app\*.*" 0 install_new
    RMDir /r "$INSTDIR\app.previous"
    ClearErrors
    Rename "$INSTDIR\app" "$INSTDIR\app.previous"
    IfErrors install_failed
  install_new:
    ClearErrors
    Rename "$INSTDIR\app.next" "$INSTDIR\app"
    IfErrors restore
    File "${BOOTSTRAP}/AsterRPG.exe"
    File "${BRAND}/aster.ico"
    ; Create a Unicode profile file so Chinese installation paths survive migration.
    FileOpen $0 "$INSTDIR\client.ini" w
    FileWriteUTF16LE /BOM $0 "[client]$\r$\nrelease=${RELEASE}$\r$\n"
    FileClose $0
    WriteINIStr "$INSTDIR\client.ini" shortcuts desktop "$DesktopValue"
    WriteINIStr "$INSTDIR\client.ini" shortcuts start "$StartValue"
    Delete "$INSTDIR\updates\ready.ini"
    WriteRegStr HKCU "Software\AsterRPG" InstallDir "$INSTDIR"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" DisplayName "AsterRPG"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" DisplayVersion "${RELEASE}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" InstallLocation "$INSTDIR"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" UninstallString '$\"$INSTDIR\Uninstall.exe$\"'
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" DisplayIcon "$INSTDIR\aster.ico"
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" NoModify 1
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG" NoRepair 1
    WriteUninstaller "$INSTDIR\Uninstall.exe"
    ${If} $DesktopValue == 1
        CreateShortCut "$DESKTOP\AsterRPG.lnk" "$INSTDIR\AsterRPG.exe" "" "$INSTDIR\aster.ico"
    ${EndIf}
    ${If} $StartValue == 1
        CreateDirectory "$SMPROGRAMS\AsterRPG"
        CreateShortCut "$SMPROGRAMS\AsterRPG\AsterRPG.lnk" "$INSTDIR\AsterRPG.exe" "" "$INSTDIR\aster.ico"
    ${EndIf}
    RMDir /r "$INSTDIR\app.previous"
    Goto finished
  restore:
    Rename "$INSTDIR\app.previous" "$INSTDIR\app"
  install_failed:
    MessageBox MB_ICONSTOP "文件替换失败，请关闭客户端后重新安装。玩家数据仍保留。"
    Abort
  finished:
SectionEnd
Section Uninstall
    SetShellVarContext current
    SetRegView 64
    IfFileExists "$INSTDIR\client.lock" 0 un_ready
    System::Call 'kernel32::CreateFileW(w "$INSTDIR\client.lock", i 0xC0000000, i 0, p 0, i 3, i 128, p 0) p.r0'
    ${If} $0 == -1
        MessageBox MB_ICONSTOP "请先关闭 AsterRPG 游戏和启动器。"
        Abort
    ${EndIf}
    System::Call 'kernel32::CloseHandle(p r0)'
  un_ready:
    Delete "$DESKTOP\AsterRPG.lnk"
    Delete "$SMPROGRAMS\AsterRPG\AsterRPG.lnk"
    RMDir "$SMPROGRAMS\AsterRPG"
    DeleteRegKey HKCU "Software\AsterRPG"
    DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterRPG"
    RMDir /r "$INSTDIR\app"
    RMDir /r "$INSTDIR\app.next"
    RMDir /r "$INSTDIR\app.previous"
    RMDir /r "$INSTDIR\updates"
    Delete "$INSTDIR\AsterRPG.exe"
    Delete "$INSTDIR\aster.ico"
    Delete "$INSTDIR\Uninstall.exe"
    MessageBox MB_ICONINFORMATION "客户端已卸载。截图、存档和个人设置保留在 $INSTDIR\game。"
SectionEnd
