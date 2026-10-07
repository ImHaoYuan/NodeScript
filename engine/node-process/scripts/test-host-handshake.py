#!/usr/bin/env python3
"""编译真实 main.cpp + 最小 node::Start 替身；只验启动握手，不编译 Node 主体、不碰设备。"""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[3]
TOKEN = 'a' * 64
STUB = r'''
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
namespace node {
int Start(int, char**) {
  if (getenv("AUTOSCRIPT_BRIDGE_TOKEN")) return 91;
  const char* marker = getenv("TEST_NODE_STARTED");
  if (marker) { FILE* f = fopen(marker, "w"); if (!f) return 92; fputs("ready", f); fclose(f); }
  if (getenv("TEST_EXPECT_TAIL")) {
    const char* text = getenv("AUTOSCRIPT_SOCK_FD");
    char c = 0;
    if (!text || read(atoi(text), &c, 1) != 1 || c != 'Z') return 93;
  }
  puts("NODE_START");
  return 0;
}
}
'''


def main():
    cxx = shutil.which(os.environ.get('CXX', 'g++'))
    if not cxx:
        raise SystemExit('缺 host C++ 编译器；握手 smoke 未执行')
    with tempfile.TemporaryDirectory(prefix='as-host-') as tmp:
        base = Path(tmp)
        stub = base / 'node-stub.cc'
        stub.write_text(STUB)
        lib = base / 'libnode-stub.so'
        host = base / 'noden'
        subprocess.run([cxx, '-std=c++20', '-shared', '-fPIC', str(stub), '-o', str(lib)], check=True)
        subprocess.run([cxx, '-std=c++20', '-Wall', '-Wextra', str(ROOT / 'engine/node-process/src/main/cpp/main.cpp'), '-ldl', '-o', str(host)], check=True)
        for scenario in ('success-fragmented', 'ack-with-tail', 'denied', 'bad-version', 'oversize', 'eof', 'timeout', 'missing-token'):
            marker = base / f'{scenario}.started'
            address = str(base / f'{scenario}.sock')
            failures = []
            stop = threading.Event()
            with socket.socket(socket.AF_UNIX) as server:
                server.bind(address)
                server.listen(1)
                server.settimeout(15)

                def handle():
                    try:
                        with server.accept()[0] as peer:
                            peer.settimeout(15)
                            line = bytearray()
                            while not line.endswith(b'\n'):
                                b = peer.recv(1)
                                if not b:
                                    assert scenario == 'missing-token'
                                    return
                                line.extend(b)
                            assert json.loads(line) == {'t': 'hello', 'v': 1, 'token': TOKEN}
                            assert not marker.exists(), 'ACK 前不应进入 node::Start'
                            if scenario == 'success-fragmented':
                                peer.sendall(b'{"t":"hello')
                                peer.sendall(b'Ack","v":1}\n')
                            elif scenario == 'ack-with-tail':
                                peer.sendall(b'{"t":"helloAck","v":1}\nZ')
                            elif scenario == 'denied':
                                peer.sendall(b'{"t":"helloErr","v":1,"code":"ERR_PERMISSION_DENIED"}\n')
                            elif scenario == 'bad-version':
                                peer.sendall(b'{"t":"helloAck","v":2}\n')
                            elif scenario == 'oversize':
                                peer.sendall(b'x' * 1025)
                            elif scenario == 'timeout':
                                stop.wait(15)
                    except BaseException as exc:
                        failures.append(exc)

                thread = threading.Thread(target=handle, daemon=True)
                thread.start()
                env = os.environ.copy()
                for key in ('AUTOSCRIPT_BRIDGE_ADDON', 'AUTOSCRIPT_BRIDGE_DIST', 'AUTOSCRIPT_SOCK_FD'):
                    env.pop(key, None)
                env.update(AUTOSCRIPT_LIBNODE=str(lib), AUTOSCRIPT_HOST_SOCKET=address,
                           AUTOSCRIPT_BRIDGE_TOKEN=TOKEN, TEST_NODE_STARTED=str(marker))
                if scenario == 'missing-token':
                    env.pop('AUTOSCRIPT_BRIDGE_TOKEN')
                if scenario == 'ack-with-tail':
                    env['TEST_EXPECT_TAIL'] = '1'
                started = time.monotonic()
                try:
                    result = subprocess.run([str(host), 'unused.js'], env=env, text=True, capture_output=True, timeout=14)
                finally:
                    stop.set()
                    thread.join(2)
                assert not thread.is_alive(), f'{scenario}: server 线程未收尾'
                if failures:
                    raise failures[0]
                expected = 0 if scenario in ('success-fragmented', 'ack-with-tail') else 3
                assert result.returncode == expected, (scenario, result.returncode, result.stderr)
                assert marker.exists() == (expected == 0), scenario
                assert TOKEN not in result.stderr + result.stdout, '凭据不许进入诊断'
                if scenario == 'timeout':
                    assert 9 <= time.monotonic() - started < 14, '握手总期限不符'
                print(f'PASS {scenario}', flush=True)


if __name__ == '__main__':
    main()
