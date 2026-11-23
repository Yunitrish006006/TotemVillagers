#!/usr/bin/env python3
"""Run the same village on a loopback dedicated server and two real rendering clients."""
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid

repo = Path(__file__).resolve().parents[1]
runtime = repo / "build/head-status-tracking"
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"


def wait_for_message(log, process, pattern, start=0, peer=None):
    deadline = time.monotonic() + 240
    with log.open(errors="replace") as stream:
        stream.seek(start)
        while time.monotonic() < deadline:
            for line in stream:
                match = re.search(pattern, line)
                if match:
                    return match
            if peer is not None and peer.poll() is not None:
                raise RuntimeError(f"Rendering client exited before {pattern}")
            if process.poll() is not None:
                raise RuntimeError(f"{log.name} exited before {pattern}. See {log}")
            time.sleep(0.2)
    raise TimeoutError(f"Missing {pattern}. See {log}")


def run(world, reports):
    jobs = []
    with socket.socket() as port_selector:
        port_selector.bind(("127.0.0.1", 0))
        port = port_selector.getsockname()[1]

    def launch(name, environment, arguments):
        folder = world / name
        folder.mkdir(exist_ok=True)
        is_client = environment == "client"
        kind = "runClientGameTest" if is_client else "runGameTest"
        main = "Client" if is_client else "Server"
        cmd = [java, "-Xmx1600m", "-XX:ActiveProcessorCount=4", "--enable-native-access=ALL-UNNAMED",
               "-Dtotem.villagers.trackingTest=true" if not is_client else "-Dtotem.villagers.trackingViewer=" + name,
               "-Dfabric.dli.config=" + str(repo / ".gradle/loom-cache/launch.cfg"),
               "-Dfabric.dli.env=" + environment, "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.Knot" + main,
               "-cp", (runtime / (kind + ".classpath")).read_text(), "net.fabricmc.devlaunchinjector.Main", *arguments]
        if is_client and os.environ.get("TOTEM_TRACKING_GRAPHICS_BACKEND"):
            cmd.extend(["--graphicsBackend", os.environ["TOTEM_TRACKING_GRAPHICS_BACKEND"]])
        if is_client and not os.environ.get("DISPLAY"):
            cmd = ["xvfb-run", "-a", "-s", "-screen 0 1024x768x24", *cmd]
        log = reports / (name + ".log")
        with log.open("w") as output:
            process = subprocess.Popen(cmd, cwd=folder, stdout=output, stderr=subprocess.STDOUT, stdin=subprocess.PIPE,
                                       text=True, start_new_session=True)
        jobs.append(process)
        return log, process

    def command(line):
        server.stdin.write(line + "\n")
        server.stdin.flush()

    try:
        folder = world / "server"
        folder.mkdir()
        (folder / "eula.txt").write_text("eula=true\n")
        (folder / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n"
                "enforce-secure-profile=false\nlevel-type=minecraft:flat\nspawn-protection=0\n"
                "view-distance=3\nsimulation-distance=3\npause-when-empty-seconds=0\ndifficulty=peaceful\n")
        players = [{"name": name, "uuid": str(uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3))}
                   for name in ("Near", "Far")]
        (folder / "whitelist.json").write_text(json.dumps(players))
        server_log, server = launch("server", "server", ["nogui"])
        wait_for_message(server_log, server, "Tracking fixture ready")
        viewers = []
        for name, z in [("Near", ".5"), ("Far", "-6.5")]:
            folder = world / name
            folder.mkdir()
            (folder / "options.txt").write_text("lang:zh_tw\nrenderDistance:3\nsimulationDistance:5\n")
            log, peer = launch(name, "client", ["--version", "Fabric", "--accessToken", "FabricMC",
                    "--username", name, "--quickPlayMultiplayer", f"127.0.0.1:{port}", "--width", "854", "--height", "480"])
            wait_for_message(server_log, server, rf"{name}.*logged in with entity id", peer=peer)
            command(f"tp {name} 0.5 100 {z} 0 0")
            resident = wait_for_message(log, peer, rf"Tracking {name} observed ([a-f0-9-]+)")[1]
            viewers.append((name, log, peer, resident))
        if viewers[0][3] != viewers[1][3]:
            raise AssertionError("Viewers observed different villagers")
        offsets = {log: log.stat().st_size for _, log, _, _ in viewers}
        command("supply_tracking_fixture")
        for name, log, peer, resident in viewers:
            wait_for_message(log, peer, rf"Tracking {name} cleared {resident}", offsets[log])
        print("Two clients saw the same shortage, near text and distant icon, and both cleared the label.")
    finally:
        for process in reversed(jobs):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
        for process in jobs:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()


if __name__ == "__main__":
    subprocess.run([str(repo / "gradlew"), *sys.argv[1:], "prepareHeadStatusTracking"], cwd=repo, check=True)
    reports = repo / "build/reports/head-status-tracking"
    reports.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="villager-tracking-") as temp:
        run(Path(temp), reports)
