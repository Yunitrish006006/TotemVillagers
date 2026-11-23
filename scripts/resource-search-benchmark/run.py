"""Run from TotemVillagers after gametestClasses. Optional argument: baseline snapshot containing java/ and classes/."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

repo = Path.cwd()
scripts = Path(__file__).resolve().parent
baseline = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else None
root = Path(tempfile.mkdtemp(prefix="resource-worker-load-"))
print(root, flush=True)
shutil.copytree((baseline or (repo / "build")) / "classes", root / "classes")
shutil.copytree(repo / "build/resources/gametest", root / "resources")
manifest = root / "resources/fabric.mod.json"
data = json.loads(manifest.read_text())
data["entrypoints"]["fabric-gametest"] = ["dev.totem.villagers.runtime.ResourceSearchBenchmark"]
manifest.write_text(json.dumps(data))
for name, path in [("args", "build/loom-cache/argFiles/runGameTest"), ("launch", ".gradle/loom-cache/launch.cfg")]:
    contents = (repo / path).read_text().replace(str(repo / "build/classes"), str(root / "classes"))
    contents = contents.replace(str(repo / "build/resources/gametest"), str(root / "resources"))
    (root / name).write_text(contents)

# Inject simple event counts into temporary bytecode only. Both versions use identical counters.
# Keep counters next to the instrumented classes to avoid introducing a main->test classloader dependency.
source = baseline / "java" if baseline else repo / "src/main/java"
package = root / "java/dev/totem/villagers/world"
package.mkdir(parents=True)
counter = "ResourceSearchCounters"
(package / (counter+".java")).write_text("package dev.totem.villagers.world; public class "+counter+
                                         " { public static long cells, paths, furnaces, workNanos; }")
def instrument(name, variants):
    contents = (source / "dev/totem/villagers/world" / (name+".java")).read_text()
    for old, new in variants:
        if old in contents:
            contents = contents.replace(old, new, 1)
            break
    else:
        raise RuntimeError("Review instrumentation for " + name)
    (package / (name+".java")).write_text(contents)
instrument("BoundedZoneTargetFinder", [(s, s+" "+counter+".cells++;") for s in
           ["BlockPos candidate = cursor.positions.next();", "BlockPos candidate = new BlockPos(x, y, z);"]])
instrument("WorldWorkNavigation", [("stance -> worker.getNavigation().createPath(stance.below(), 0)",
                                   "stance -> { "+counter+".paths++; return worker.getNavigation().createPath(stance.below(), 0); }")])
instrument("MinerFurnaceWorkstation", [(s, s+" "+counter+".furnaces++;") for s in
           ["for (var pos : chunk.getBlockEntities().keySet()) {", "BlockPos station = new BlockPos(x, y, z);"]])
runtime = root / "java/dev/totem/villagers/runtime/VillagerWorldWorkRuntime.java"
runtime.parent.mkdir(parents=True, exist_ok=True)
runtime_source = (source / runtime.relative_to(root/"java")).read_text()
entry = "        run(server, false);"
if runtime_source.count(entry) != 1:
    raise RuntimeError("Review worker tick timing instrumentation")
runtime.write_text(runtime_source.replace(entry, "        long start = System.nanoTime();\n" + entry +
                   "\n        dev.totem.villagers.world.ResourceSearchCounters.workNanos = System.nanoTime()-start;"))
bench = scripts / "ResourceSearchBenchmark.java"
jdk = Path(os.environ["JAVA_HOME"]) / "bin"
classpath = (root / "args").read_text().splitlines()[1]
for output, files in [("main", [*package.glob("*.java"), runtime]), ("gametest", [bench])]:
    subprocess.run([jdk/"javac", "-proc:none", "-cp", classpath, "-d", root/("classes/java/"+output), *files], check=True)
(root / "eula.txt").write_text("eula=true\n")
(root / "server.properties").write_text("server-port=0\nserver-ip=127.0.0.1\nlevel-seed=633713\n")
os.chdir(root)
java = str(jdk/"java")
os.execv(java, [java, "-Xms1g", "-Xmx2g", "-XX:ActiveProcessorCount=4", "-Dfabric-api.gametest",
                "-Dtotem.bench.reverse="+str(os.environ.get("RESOURCE_BENCH_REVERSE")=="1").lower(),
                "-Dfabric-api.gametest.report-file="+str(root/"tests.xml"), "-Dfabric.dli.config="+str(root/"launch"),
                "-Dfabric.dli.env=server", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
                "@"+str(root/"args"), "--enable-native-access=ALL-UNNAMED", "net.fabricmc.devlaunchinjector.Main", "nogui"])
