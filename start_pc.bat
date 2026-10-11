@echo off
title Screen Share PC
cd /d "%~dp0"
echo ========================================================
echo   DANG KHOI DONG SERVER SCREEN SHARE (WINDOWS)
echo ========================================================
echo.
python pc\main.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [LOI] Chuong trinh gap loi hoac bi dong bat thuong.
    pause
)
