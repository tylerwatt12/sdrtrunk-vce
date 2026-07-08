@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "APP_HOME=%%~fI"

if exist "%APP_HOME%\runtime\bin\java.exe" (
    set "JAVACMD=%APP_HOME%\runtime\bin\java.exe"
) else if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVACMD=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVACMD=java"
    )
) else (
    set "JAVACMD=java"
)

set "CLASSPATH=%APP_HOME%\lib\*"
"%JAVACMD%" -classpath "%CLASSPATH%" io.github.dsheirer.database.migration.XmlPlaylistToSqliteMigrator %*
exit /b %ERRORLEVEL%
