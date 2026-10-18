"""Read-only, timestamped baseline export. Never overwrite a previous backup."""
import hashlib
import json
import os
from pathlib import Path
import datetime
from device import adb, root, archive

project = Path(__file__).resolve().parents[1]
backup = project / "private" / ("baseline-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
backup.mkdir(parents=True, exist_ok=False, mode=0o700)
os.chmod(backup.parent, 0o700)
directory = root("find /data/system -maxdepth 1 -type d -name 'thanos_*' | head -1").strip()
if not directory:
    raise RuntimeError("Thanox data directory not found")
files = ["thanos.xml", "pkg_sets_2.xml", "push_channels.xml", "op_settings.xml", "start_blocking_pkgs.xml",
         "bg_restrict_pkgs.xml", "blocked_wakelocks.xml", "smart_stand_by_pkgs.xml", "resident_pkgs.xml",
         "clean_up_on_task_removed_pkgs.xml", "op_reminding_pkgs.xml", "smart_freeze_pkgs.xml", "enable_on_launch_pkgs.xml"]
hashes = {}
for name in files:
    data = adb("exec-out", "su -c " + __import__("shlex").quote("cat " + directory + "/data/u/0/" + name), binary=True)
    if not data:
        raise RuntimeError("Empty export: " + name)
    (backup / name).write_bytes(data)
    hashes[name] = hashlib.sha256(data).hexdigest()
query = "sqlite3 -readonly -json " + directory + "/data/u/0/rules/db/rule.db 'SELECT id,enabled,rawJson FROM RuleRecord;'"
rules = root(query).strip()
json.loads(rules)
(backup / "rules.json").write_text(rules + "\n")
hashes["rules.json"] = hashlib.sha256((rules + "\n").encode()).hexdigest()
archive_data = archive([directory,"/data/adb/lspd/config","/data/adb/service.d"])
(backup / "original-state.tar.gz").write_bytes(archive_data)
hashes["original-state.tar.gz"] = hashlib.sha256(archive_data).hexdigest()
(backup / "manifest.json").write_text(json.dumps({"schema": 1, "created": datetime.datetime.now().isoformat(), "sha256": hashes}, indent=2))
for file in backup.iterdir():
    file.chmod(0o600)
print(backup)
