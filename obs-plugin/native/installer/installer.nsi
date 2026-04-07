; obs-android-usbcam installer
; Installs the plugin DLL into an existing OBS Studio installation.
;
; Build with:
;   makensis -DPLUGIN_DLL=path\to\obs-android-usbcam.dll installer.nsi

!define PLUGIN_NAME      "obs-android-usbcam"
!define PLUGIN_DISPLAY   "Android USB Cam (OBS Plugin)"
!define PLUGIN_VERSION   "1.0.0"
!define PLUGIN_PUBLISHER "haeimalan"
!define PLUGIN_URL       "https://github.com/haeimalan/android-obs-streamer"

!ifndef PLUGIN_DLL
  !define PLUGIN_DLL "obs-android-usbcam.dll"
!endif

!ifndef OUTFILE
  !define OUTFILE "obs-android-usbcam-installer.exe"
!endif

Name "${PLUGIN_DISPLAY}"
OutFile "${OUTFILE}"
Unicode true
RequestExecutionLevel admin
ShowInstDetails show
ShowUninstDetails show

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!include "FileFunc.nsh"

!define MUI_ABORTWARNING
!define MUI_ICON   "${NSISDIR}\Contrib\Graphics\Icons\modern-install.ico"
!define MUI_UNICON "${NSISDIR}\Contrib\Graphics\Icons\modern-uninstall.ico"

Var OBS_DIR

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

;-------------------------------------------------------------------
; Detect OBS install directory from the registry
;-------------------------------------------------------------------
Function .onInit
  SetRegView 64

  ; Try a few well-known registry locations
  ReadRegStr $OBS_DIR HKLM "SOFTWARE\OBS Studio" ""
  ${If} $OBS_DIR == ""
    ReadRegStr $OBS_DIR HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\OBS Studio" "InstallLocation"
  ${EndIf}
  ${If} $OBS_DIR == ""
    ReadRegStr $OBS_DIR HKCU "SOFTWARE\OBS Studio" ""
  ${EndIf}
  ${If} $OBS_DIR == ""
    ; Common defaults
    ${If} ${FileExists} "$PROGRAMFILES64\obs-studio\bin\64bit\obs64.exe"
      StrCpy $OBS_DIR "$PROGRAMFILES64\obs-studio"
    ${ElseIf} ${FileExists} "$PROGRAMFILES\obs-studio\bin\64bit\obs64.exe"
      StrCpy $OBS_DIR "$PROGRAMFILES\obs-studio"
    ${EndIf}
  ${EndIf}

  ${If} $OBS_DIR == ""
    StrCpy $OBS_DIR "$PROGRAMFILES64\obs-studio"
  ${EndIf}

  StrCpy $INSTDIR $OBS_DIR
FunctionEnd

;-------------------------------------------------------------------
; Main install
;-------------------------------------------------------------------
Section "Install plugin" SecMain
  SectionIn RO

  ; Sanity check: $INSTDIR should look like an OBS install
  ${IfNot} ${FileExists} "$INSTDIR\bin\64bit\obs64.exe"
    MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION \
      "obs64.exe was not found under$\r$\n$INSTDIR\bin\64bit$\r$\n$\r$\nInstall anyway?" \
      IDOK +2
    Abort "Aborted: not an OBS Studio install dir."
  ${EndIf}

  SetOutPath "$INSTDIR\obs-plugins\64bit"
  File /oname=obs-android-usbcam.dll "${PLUGIN_DLL}"

  ; data dir (for locale, even if empty)
  CreateDirectory "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\locale"
  SetOutPath "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}"
  FileOpen $0 "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\README.txt" w
  FileWrite $0 "Android USB Cam OBS plugin$\r$\n"
  FileWrite $0 "${PLUGIN_URL}$\r$\n"
  FileClose $0

  ; Bundled adb (for USB mode auto-forward)
!ifdef ADB_DIR
  CreateDirectory "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\adb"
  SetOutPath "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\adb"
  File "${ADB_DIR}\adb.exe"
  File "${ADB_DIR}\AdbWinApi.dll"
  File "${ADB_DIR}\AdbWinUsbApi.dll"
!endif

  ; Uninstaller
  WriteUninstaller "$INSTDIR\${PLUGIN_NAME}-uninstall.exe"

  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PLUGIN_NAME}" \
    "DisplayName"     "${PLUGIN_DISPLAY}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PLUGIN_NAME}" \
    "DisplayVersion"  "${PLUGIN_VERSION}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PLUGIN_NAME}" \
    "Publisher"       "${PLUGIN_PUBLISHER}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PLUGIN_NAME}" \
    "URLInfoAbout"    "${PLUGIN_URL}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PLUGIN_NAME}" \
    "UninstallString" "$\"$INSTDIR\${PLUGIN_NAME}-uninstall.exe$\""
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PLUGIN_NAME}" \
    "InstallLocation" "$INSTDIR"

  DetailPrint "Installed obs-android-usbcam.dll to $INSTDIR\obs-plugins\64bit"
  DetailPrint "Restart OBS Studio, then add a source: + → 'Android USB Cam'"
SectionEnd

;-------------------------------------------------------------------
; Uninstall
;-------------------------------------------------------------------
Section "Uninstall"
  Delete "$INSTDIR\obs-plugins\64bit\obs-android-usbcam.dll"
  Delete "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\README.txt"
  Delete "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\adb\adb.exe"
  Delete "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\adb\AdbWinApi.dll"
  Delete "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\adb\AdbWinUsbApi.dll"
  RMDir  "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\adb"
  RMDir  "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}\locale"
  RMDir  "$INSTDIR\data\obs-plugins\${PLUGIN_NAME}"
  Delete "$INSTDIR\${PLUGIN_NAME}-uninstall.exe"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PLUGIN_NAME}"
SectionEnd
