@echo off
echo Building portable version...

:: Compile Java files
javac -encoding UTF-8 Raycaster3D.java

:: Create JAR
echo Manifest-Version: 1.0 > MANIFEST.MF
echo Main-Class: Raycaster3D >> MANIFEST.MF
echo Class-Path: . >> MANIFEST.MF
echo. >> MANIFEST.MF

jar cvfm Raycaster3D.jar MANIFEST.MF *.class

:: Create portable folder
mkdir Raycaster3D_Portable
mkdir Raycaster3D_Portable\textures
mkdir Raycaster3D_Portable\textures\items
mkdir Raycaster3D_Portable\maps

:: Copy JAR
copy Raycaster3D.jar Raycaster3D_Portable\

:: Create launcher
(
echo @echo off
echo cd /d "%%~dp0"
echo start javaw -jar Raycaster3D.jar
) > Raycaster3D_Portable\start.bat

:: Create double-click launcher
(
echo Set WshShell = CreateObject^("WScript.Shell"^)
echo WshShell.Run "start.bat", 0, False
) > Raycaster3D_Portable\start.vbs

echo Done! Folder created: Raycaster3D_Portable
echo Send this folder to your friend!
pause