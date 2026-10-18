"""Keep the charging test phone awake; restore the setting we still own."""
import datetime
import json
from pathlib import Path
import sys
from device import root

state=Path(__file__).resolve().parents[1]/"private"/"test-screen.json"
key="stay_on_while_plugged_in"
if sys.argv[1]=="begin":
    before=root("settings get global "+key).strip()
    applied=str((0 if before=="null" else int(before))|3)
    with state.open("x") as handle:json.dump({"before":before,"applied":applied},handle)
    state.chmod(0o600)
    root("settings put global "+key+" "+applied)
    print("Charging wake setting saved and enabled")
elif sys.argv[1]=="restore":
    saved=json.loads(state.read_text())
    if root("settings get global "+key).strip()==saved["applied"]:
        root("settings delete global "+key if saved["before"]=="null" else "settings put global "+key+" "+saved["before"])
        print("Original charging wake setting restored")
    else: print("Setting changed externally; preserving current value")
    state.rename(state.with_name("test-screen-restored-"+datetime.datetime.now().strftime("%Y%m%d-%H%M%S")+".json"))
else: raise ValueError("Use begin or restore")
