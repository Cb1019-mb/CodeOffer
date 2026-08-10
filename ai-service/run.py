"""CodeOffer AI 服务本地启动包装器。

用途:uvicorn 默认绑定不设 SO_REUSEADDR,Windows 上被强杀的旧进程会留下
「僵尸 socket」表项(WinError 10048: 地址已被使用),导致重启失败。
本包装器先以 SO_REUSEADDR 绑定端口,再把已绑定 socket 交给 uvicorn Server。

用法: python run.py [--host 127.0.0.1] [--port 8001]
"""
from __future__ import annotations

import argparse
import socket

import uvicorn
from uvicorn import Config, Server


def main() -> None:
    parser = argparse.ArgumentParser(description="CodeOffer AI 服务启动器(SO_REUSEADDR)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8001)
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((args.host, args.port))
    # 外部 socket 模式不支持 --reload(热更新);本地开发热更新请直接运行 uvicorn(无僵尸端口时可用)
    config = Config("main:app", workers=1)
    Server(config).run(sockets=[sock])


if __name__ == "__main__":
    main()
