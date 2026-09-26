#!/usr/bin/env python3
"""增量构建并覆盖安装开发版，通过 ADB 标准输入配置连接，不把令牌放入命令参数或 APK。"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
CLIENT = ROOT / "Client"
PACKAGE = "cn.hedgeho9.murmur"


def run(args, **kwargs):
    """执行明确参数的子进程，失败即停止，不自动卸载或清空应用数据。"""
    return subprocess.run(args, check=True, **kwargs)


def main():
    """选择已授权设备，构建安装并写入一次性私有配置，最后启动应用。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", help="ADB 设备序列号；多设备时必须指定")
    parser.add_argument("--url", help="手机可达 API 地址；默认 USB reverse 本机端口")
    parser.add_argument("--skip-build", action="store_true", help="仅安装已有开发 APK")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    adb = shutil.which("adb") or str(sdk / "platform-tools/adb")
    rows = run([adb, "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    devices = [r.split()[0] for r in rows if len(r.split()) > 1 and r.split()[1] == "device"]
    serial = args.serial or (devices[0] if len(devices) == 1 else None)
    if serial not in devices:
        sys.exit("请连接并授权手机；存在多个设备时使用 --serial 指定目标。")
    env_file = ROOT / "Server/.env"
    settings = {}
    for line in env_file.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            settings[key] = value.strip().strip('"').strip("'")
    token = settings.get("API_TOKEN", "")
    if not token:
        sys.exit("Server/.env 缺少 API_TOKEN")
    port = int(settings.get("PORT", "8787"))
    url = args.url or f"http://127.0.0.1:{port}/"
    if not url.startswith(("http://", "https://")):
        sys.exit("--url 必须为 HTTP(S) 地址")
    if not args.skip_build:
        env = os.environ.copy()
        java = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')
        if "JAVA_HOME" not in env and java.exists():
            env["JAVA_HOME"] = str(java)
        run(["sh", "gradlew", ":app:assembleDebug", f"-PmurmurApiUrl={url}"], cwd=CLIENT, env=env)
    device = [adb, "-s", serial]
    run(device + ["install", "-r", str(CLIENT / "app/build/outputs/apk/debug/app-debug.apk")])
    if not args.url:
        run(device + ["reverse", f"tcp:{port}", f"tcp:{port}"])
        run(device + ["reverse", "tcp:59000", "tcp:59000"])
    run(device + ["shell", "am", "force-stop", PACKAGE])
    # run-as 仅适用于已授权设备上的可调试应用；配置通过 stdin 写入私有目录。
    run(device + ["shell", "run-as", PACKAGE, "mkdir", "-p", "files"])
    run(device + ["shell", "run-as", PACKAGE, "sh", "-c", "'umask 077; cat > files/dev-connection.json'"], input=json.dumps({"url": url, "token": token}).encode(), stdout=subprocess.DEVNULL)
    run(device + ["shell", "am", "start", "-n", PACKAGE + "/.MainActivity"])
    for attempt in range(20):
        files = run(device + ["exec-out", "run-as", PACKAGE, "ls", "files"], capture_output=True, text=True).stdout.splitlines()
        if "dev-connection.json" not in files:
            break
        time.sleep(0.25)
    else:
        sys.exit("App 尚未消费开发配置，请解锁手机并打开 Murmur 后检查。")
    print(f"已安装并启动：{serial}，API {url}；令牌已通过私有配置注入。")
    print("若 S3_PUBLIC_ENDPOINT 为局域网地址，手机还需能访问该地址；纯 USB 模式请将其设为 http://127.0.0.1:59000 后重启后端。")


if __name__ == "__main__":
    main()
