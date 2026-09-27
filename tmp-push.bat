@echo off
cd /d D:\workspace\tianshu-opensource
for /f "tokens=2 delims==" %%T in ('findstr "password=" D:\workspace\tianshu-agent\tmp\cred.out') do set TOKEN=%%T
git init -b main
git config user.name "Coder"
git config user.email "coder@tianshu.local"
git add -A
git commit -q -m "Open-source Tianshu: reactive AI Agent framework for the JVM"
echo COMMIT_RC=%errorlevel%
git remote add origin "https://gantangzx:%TOKEN%@github.com/gantangzx/tianshu.git"
git push -u origin main
echo PUSH_RC=%errorlevel%
git remote set-url origin "https://github.com/gantangzx/tianshu.git"
echo REMOTE_RESET=%errorlevel%
