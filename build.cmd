@echo off
chcp 65001 > nul
setlocal enabledelayedexpansion
title AShell PC Build Script
color 0B

:: ============================================================
:: AShell Windows x64 portable build script
:: Adapted for the PC-Build.md server layout:
::   C:\DevEnv + C:\DevTool
:: Script location is always treated as source root.
:: ============================================================

set "ROOT_FOLDER=%~dp0"
if "%ROOT_FOLDER:~-1%"=="\" set "ROOT_FOLDER=%ROOT_FOLDER:~0,-1%"
for %%A in ("%ROOT_FOLDER%") do set "FOLDER_NAME=%%~nxA"
set "RELEASE_DIR=%ROOT_FOLDER%\flutter\build\windows\x64\runner\Release"
set "OUTPUT_DIR=%ROOT_FOLDER%\PC-Bulid"
set "FINAL_EXE=%OUTPUT_DIR%\ashell.exe"
set "PORTABLE_DIR=%ROOT_FOLDER%\libs\portable"
set "PACKER_EXE=%ROOT_FOLDER%\target\release\ashell-portable-packer.exe"
set "MANIFEST_FILE=%ROOT_FOLDER%\res\manifest.xml"
set "MANIFEST_BACKUP=%ROOT_FOLDER%\res\manifest.pc.bak.xml"
set "manifest_changed=0"
:: build.py and the packager consume the workspace target/release paths.
set "CARGO_TARGET_DIR=%ROOT_FOLDER%\target"

if not defined DEVENV set "DEVENV=C:\DevEnv"
if not defined DEVTOOL set "DEVTOOL=C:\DevTool"
if not defined CARGO_HOME set "CARGO_HOME=%DEVENV%\cargo"
if not defined RUSTUP_HOME set "RUSTUP_HOME=%DEVENV%\rustup"
if not defined PUB_CACHE set "PUB_CACHE=%DEVENV%\pub-cache"
if not defined PIP_CACHE_DIR set "PIP_CACHE_DIR=%DEVENV%\pip-cache"
if not defined VCPKG_ROOT set "VCPKG_ROOT=%DEVENV%\vcpkg"
if not defined VCPKG_DEFAULT_TRIPLET set "VCPKG_DEFAULT_TRIPLET=x64-windows-static"
if not defined VCPKG_DEFAULT_HOST_TRIPLET set "VCPKG_DEFAULT_HOST_TRIPLET=x64-windows-static"
if not defined X_VCPKG_BINARY_CACHE set "X_VCPKG_BINARY_CACHE=%DEVENV%\vcpkg-binary-cache"
if not defined VCPKG_BINARY_SOURCES set "VCPKG_BINARY_SOURCES=clear;files,%DEVENV%\vcpkg-binary-cache,readwrite"
if not defined VCPKG_INSTALLED_ROOT set "VCPKG_INSTALLED_ROOT=%DEVENV%\vcpkg\installed"
if not defined LIBCLANG_PATH set "LIBCLANG_PATH=%DEVTOOL%\LLVM\bin"

set "PATH=%CARGO_HOME%\bin;%DEVENV%\flutter\bin;%VCPKG_ROOT%;%DEVTOOL%\Python;%DEVTOOL%\Python\Scripts;%DEVTOOL%\Git\cmd;%DEVTOOL%\Git\bin;%DEVTOOL%\LLVM\bin;%PATH%"

set "VCVARS=%DEVTOOL%\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

echo ============================================================
echo AShell PC build script
echo ============================================================
echo [INFO] Source root : "%ROOT_FOLDER%"
echo [INFO] App name    : "A Shell You"
echo [INFO] DEVENV   : "%DEVENV%"
echo [INFO] DEVTOOL  : "%DEVTOOL%"
echo [INFO] VC vars     : "%VCVARS%"
echo.

if not exist "%VCVARS%" (
    echo [ERROR] vcvars64.bat is missing.
    echo [ERROR] Expected: "%VCVARS%"
    goto fail
)

echo [INFO] Calling Visual Studio Build Tools environment...
call "%VCVARS%"
if errorlevel 1 (
    echo [ERROR] Visual Studio Build Tools environment initialization failed.
    goto fail
)
echo [OK] Visual Studio Build Tools environment is ready.
echo.

:: vcvars64.bat can override VCPKG_ROOT to Visual Studio's bundled vcpkg.
:: AShell must stay pinned to the PC-Build.md dependency cache under C:\DevEnv.
set "VCPKG_ROOT=%DEVENV%\vcpkg"
set "VCPKG_DEFAULT_TRIPLET=x64-windows-static"
set "VCPKG_DEFAULT_HOST_TRIPLET=x64-windows-static"
set "X_VCPKG_BINARY_CACHE=%DEVENV%\vcpkg-binary-cache"
set "VCPKG_BINARY_SOURCES=clear;files,%DEVENV%\vcpkg-binary-cache,readwrite"
set "VCPKG_INSTALLED_ROOT=%DEVENV%\vcpkg\installed"
set "LIBCLANG_PATH=%DEVTOOL%\LLVM\bin"
set "PATH=%CARGO_HOME%\bin;%DEVENV%\flutter\bin;%VCPKG_ROOT%;%DEVTOOL%\Python;%DEVTOOL%\Python\Scripts;%DEVTOOL%\Git\cmd;%DEVTOOL%\Git\bin;%DEVTOOL%\LLVM\bin;%PATH%"
if errorlevel 1 goto fail
echo [OK] AShell dependency roots were restored after vcvars64.
echo [INFO] VCPKG_ROOT          : "%VCPKG_ROOT%"
echo [INFO] VCPKG_INSTALLED_ROOT: "%VCPKG_INSTALLED_ROOT%"
echo.

if not exist "%ROOT_FOLDER%\.info" (
    echo [ERROR] .info file is missing: "%ROOT_FOLDER%\.info"
    goto fail
)

echo [INFO] .info file: "%ROOT_FOLDER%\.info"
for /f "usebackq eol=# tokens=1* delims==" %%i in ("%ROOT_FOLDER%\.info") do (
    if not "%%i"=="" (
        if not defined %%i set "%%i=%%j"
        echo [INFO] Loaded setting: %%i
    )
)

if not defined VERSION set "VERSION=5.2.1"
if not defined ARCH set "ARCH=x64"

echo.
echo [INFO] Folder Name: "%FOLDER_NAME%"
echo [INFO] Version    : v%VERSION% (%ARCH%)
echo [INFO] Output dir : "%ROOT_FOLDER%\PC-Bulid"
echo.

:confirm
choice /c YN /n /t 10 /d Y /m "Please input Y to continue, or N to cancel. Auto-confirm in 10 seconds: "
if %errorlevel% equ 1 (
    goto continue
) else (
    echo [INFO] canceled
    exit /b 1
)

:continue
echo [INFO] confirmed
echo.

if not exist "%DEVENV%\flutter" (
    echo [ERROR] Missing Flutter root:
    echo         "%DEVENV%\flutter"
    goto fail
)
if not exist "%VCPKG_ROOT%" (
    echo [ERROR] Missing vcpkg root:
    echo         "%VCPKG_ROOT%"
    goto fail
)
if not exist "%VCPKG_ROOT%\vcpkg.exe" (
    echo [ERROR] Missing vcpkg.exe:
    echo         "%VCPKG_ROOT%\vcpkg.exe"
    goto fail
)
if not exist "%VCPKG_INSTALLED_ROOT%\x64-windows-static" (
    echo [ERROR] Missing vcpkg x64-windows-static install root:
    echo         "%VCPKG_INSTALLED_ROOT%\x64-windows-static"
    goto fail
)
if not exist "%LIBCLANG_PATH%\libclang.dll" (
    echo [ERROR] Missing libclang.dll:
    echo         "%LIBCLANG_PATH%\libclang.dll"
    goto fail
)
if not exist "%VCPKG_INSTALLED_ROOT%\x64-windows-static\include\libavcodec\avcodec.h" (
    echo [ERROR] Missing FFmpeg avcodec headers:
    echo         "%VCPKG_INSTALLED_ROOT%\x64-windows-static\include\libavcodec\avcodec.h"
    goto fail
)
if not exist "%VCPKG_INSTALLED_ROOT%\x64-windows-static\include\libavutil\attributes.h" (
    echo [ERROR] Missing FFmpeg avutil headers:
    echo         "%VCPKG_INSTALLED_ROOT%\x64-windows-static\include\libavutil\attributes.h"
    goto fail
)
if not exist "%VCPKG_INSTALLED_ROOT%\x64-windows-static\include\opus\opus_multistream.h" (
    echo [ERROR] Missing Opus multistream headers:
    echo         "%VCPKG_INSTALLED_ROOT%\x64-windows-static\include\opus\opus_multistream.h"
    goto fail
)
if not exist "%ROOT_FOLDER%\build.py" (
    echo [ERROR] Missing build.py:
    echo         "%ROOT_FOLDER%\build.py"
    goto fail
)
if not exist "%ROOT_FOLDER%\flutter\pubspec.yaml" (
    echo [ERROR] Missing flutter pubspec.yaml:
    echo         "%ROOT_FOLDER%\flutter\pubspec.yaml"
    goto fail
)
if not exist "%ROOT_FOLDER%\libs\portable\generate.py" (
    echo [ERROR] Missing portable generate.py:
    echo         "%ROOT_FOLDER%\libs\portable\generate.py"
    goto fail
)

where rustup >nul 2>nul
if errorlevel 1 (
    echo [ERROR] rustup was not found in PATH.
    goto fail
)
where cargo >nul 2>nul
if errorlevel 1 (
    echo [ERROR] cargo was not found in PATH.
    goto fail
)
where flutter >nul 2>nul
if errorlevel 1 (
    echo [ERROR] flutter was not found in PATH.
    goto fail
)
where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] python was not found in PATH.
    goto fail
)

:: Packaging has no automatic Python dependency installation or driver downloads.
echo [INFO] Checking Python Brotli for the portable packer...
python -c "import brotli"
if errorlevel 1 (
    echo [ERROR] Python Brotli is missing. Prepare it in the build environment first.
    goto fail
)
if not exist "%MANIFEST_FILE%" (
    echo [ERROR] Missing manifest: "%MANIFEST_FILE%"
    goto fail
)
if exist "%MANIFEST_BACKUP%" (
    echo [ERROR] A previous manifest backup exists: "%MANIFEST_BACKUP%"
    echo [ERROR] Review and restore the previous interrupted build before continuing.
    goto fail
)

echo.
echo ========== Step: 1. Pin Rust toolchain ==========
echo [COMMAND] rustup default 1.75.0-x86_64-pc-windows-msvc
rustup default 1.75.0-x86_64-pc-windows-msvc
if errorlevel 1 goto fail
echo [SUCCESS] 1. Pin Rust toolchain

echo.
echo ========== Step: 1.1 Ensure Windows MSVC target ==========
echo [COMMAND] rustup target add x86_64-pc-windows-msvc
rustup target add x86_64-pc-windows-msvc
if errorlevel 1 goto fail
echo [SUCCESS] 1.1 Ensure Windows MSVC target

set MAX_RETRY=1800
set retry_count=0

:check_loop
set "all_exist=1"
for %%f in (
    "%ROOT_FOLDER%\src\bridge_generated.rs"
    "%ROOT_FOLDER%\src\bridge_generated.io.rs"
    "%ROOT_FOLDER%\flutter\lib\generated_bridge.dart"
    "%ROOT_FOLDER%\flutter\lib\generated_bridge.freezed.dart"
) do (
    if not exist "%%~f" (
        set "all_exist=0"
        echo [WARN] Generated file missing: %%~nxf
    )
)

if !all_exist! equ 1 (
    echo [OK] Generated bridge files are ready.
    goto build_start
)

set /a "retry_count+=1"
if !retry_count! gtr !MAX_RETRY! (
    echo [ERROR] Generated bridge file wait timed out.
    goto fail
)

echo [INFO] Waiting for generated bridge files (!retry_count!/!MAX_RETRY!)...
timeout /t 1 /nobreak > nul
goto check_loop

:build_start
echo.
echo ========== Step: 2. Get Flutter dependencies ==========
echo [WORKDIR] "%ROOT_FOLDER%\flutter"
echo [COMMAND] flutter pub get
pushd "%ROOT_FOLDER%\flutter"
if errorlevel 1 goto fail
call flutter pub get
set "pub_get_code=!errorlevel!"
popd
if not "!pub_get_code!"=="0" goto fail
echo [SUCCESS] 2. Get Flutter dependencies

echo.
echo ========== Step: 3. Prepare a clean Windows Release directory ==========
echo [INFO] Clearing only the previous compiler bundle: "%RELEASE_DIR%"
:: Verify the fixed workspace path and reject reparse points before recursive removal.
powershell -NoProfile -Command "$ErrorActionPreference = 'Stop'; $root = (Resolve-Path -LiteralPath $env:ROOT_FOLDER).Path; $target = [IO.Path]::GetFullPath($env:RELEASE_DIR); $expected = Join-Path $root 'flutter\build\windows\x64\runner\Release'; if ($target -ne $expected) { throw 'Unexpected Release path' }; $cursor = $target; while ($cursor.Length -gt $root.Length) { if (Test-Path -LiteralPath $cursor) { $item = Get-Item -Force -LiteralPath $cursor; if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Release path contains a reparse point' } }; $cursor = Split-Path -Parent $cursor }; if (Test-Path -LiteralPath $target) { $links = @(Get-ChildItem -LiteralPath $target -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }); if ($links.Count -gt 0) { throw 'Release directory contains reparse points' }; Remove-Item -LiteralPath $target -Recurse -Force }"
if errorlevel 1 goto fail
echo [SUCCESS] 3. Prepare a clean Windows Release directory

echo.
echo ========== Step: 4. Build AShell Windows release ==========
echo [WORKDIR] "%ROOT_FOLDER%"
echo [COMMAND] python .\build.py --portable --hwcodec --flutter --vram --skip-portable-pack
pushd "%ROOT_FOLDER%"
if errorlevel 1 goto fail
python .\build.py --portable --hwcodec --flutter --vram --skip-portable-pack
set "build_code=!errorlevel!"
popd
if not "!build_code!"=="0" goto fail
echo [SUCCESS] 4. Build AShell Windows release

:: Compress only this freshly produced Release bundle. No external DLL/driver staging.
for %%F in (
    "%RELEASE_DIR%\ashell.exe"
    "%RELEASE_DIR%\ashell.dll"
    "%RELEASE_DIR%\flutter_windows.dll"
    "%RELEASE_DIR%\dylib_virtual_display.dll"
    "%RELEASE_DIR%\data\icudtl.dat"
    "%RELEASE_DIR%\data\app.so"
) do (
    if not exist "%%~F" (
        echo [ERROR] Missing compiled bundle file: "%%~F"
        goto fail
    )
)

copy /Y "%MANIFEST_FILE%" "%MANIFEST_BACKUP%" >nul
if errorlevel 1 goto fail
set "manifest_changed=1"

echo.
echo ========== Step: 5. Prepare portable manifest ==========
powershell -NoProfile -Command "$ErrorActionPreference = 'Stop'; $p = $env:MANIFEST_FILE; $lines = [IO.File]::ReadAllLines($p); $filtered = @($lines | Where-Object { $_ -notmatch 'dpiAware' }); [IO.File]::WriteAllLines($p, [string[]]$filtered, (New-Object System.Text.UTF8Encoding($false)))"
if errorlevel 1 goto fail
echo [SUCCESS] 5. Prepare portable manifest

echo.
echo ========== Step: 6. Generate portable self-extract EXE ==========
echo [WORKDIR] "%PORTABLE_DIR%"
echo [INPUT] "%RELEASE_DIR%"
echo [COMMAND] python .\generate.py -f "%RELEASE_DIR%" -o . -e "%RELEASE_DIR%\ashell.exe"
pushd "%PORTABLE_DIR%"
if errorlevel 1 goto fail
python .\generate.py -f "%RELEASE_DIR%" -o . -e "%RELEASE_DIR%\ashell.exe"
set "portable_pack_code=!errorlevel!"
popd
if not "!portable_pack_code!"=="0" (
    echo [ERROR] Portable generation failed with code !portable_pack_code!.
    goto fail
)

call :restore_manifest
if errorlevel 1 goto fail
if not exist "%PACKER_EXE%" (
    echo [ERROR] Missing portable executable: "%PACKER_EXE%"
    goto fail
)
for %%F in ("%PACKER_EXE%") do if "%%~zF"=="0" (
    echo [ERROR] Portable executable is empty.
    goto fail
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
if errorlevel 1 goto fail
copy /Y "%PACKER_EXE%" "%FINAL_EXE%.tmp" >nul
if errorlevel 1 goto fail
move /Y "%FINAL_EXE%.tmp" "%FINAL_EXE%" >nul
if errorlevel 1 goto fail

echo [SUCCESS] 6. Generate portable self-extract EXE
echo.
echo [INFO] Compiled Release bundle retained:
echo        "%RELEASE_DIR%"
echo [INFO] PC self-extract EXE:
echo        "%FINAL_EXE%"
echo [INFO] Payload contains only the compiled Release bundle, with no added drivers.
echo.
echo [INFO] Press Enter to close now, or wait 60 seconds for automatic close.
powershell -NoProfile -Command "$deadline = (Get-Date).AddSeconds(60); try { while ((Get-Date) -lt $deadline) { if ([Console]::KeyAvailable) { $key = [Console]::ReadKey($true); if ($key.Key -eq [ConsoleKey]::Enter) { exit 0 } }; Start-Sleep -Milliseconds 100 } } catch { Start-Sleep -Seconds 60 }"
exit /b 0

:restore_manifest
if "!manifest_changed!"=="0" exit /b 0
if not exist "%MANIFEST_BACKUP%" (
    echo [ERROR] Cannot restore manifest; backup is missing.
    exit /b 1
)
copy /Y "%MANIFEST_BACKUP%" "%MANIFEST_FILE%" >nul
if errorlevel 1 (
    echo [ERROR] Cannot restore manifest. Backup retained: "%MANIFEST_BACKUP%"
    exit /b 1
)
del /q "%MANIFEST_BACKUP%"
if errorlevel 1 exit /b 1
set "manifest_changed=0"
exit /b 0

:fail
call :restore_manifest
if errorlevel 1 echo [ERROR] Manual manifest recovery is required.
echo.
echo [FAILED] build.cmd stopped. No new portable result was published.
echo [INFO] Existing output files may belong to a previous build.
pause
exit /b 1
