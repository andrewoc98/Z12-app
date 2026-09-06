#!/usr/bin/env python3
"""
Drives Aider through tasks.yaml one task at a time.

Usage:
    python3 run_agent.py                # run all tasks in order, starting from where you left off
    python3 run_agent.py --from 9       # resume from a specific task id
    python3 run_agent.py --only 14      # run a single task
    python3 run_agent.py --dry-run      # print what would happen, don't call aider or git

Requires: pip install pyyaml
Assumes it's run from the repo root, with .aider.conf.yml and tasks.yaml (this dir) also
copied/symlinked into the repo root, and NOTES.md already existing (empty file is fine).

State: writes .aider-agent/progress.json so re-running skips already-completed tasks.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("Missing dependency. Run: pip install pyyaml --break-system-packages")

REPO_ROOT = Path.cwd()
STATE_DIR = REPO_ROOT / ".aider-agent"
PROGRESS_FILE = STATE_DIR / "progress.json"
TASKS_FILE = Path(__file__).parent / "tasks.yaml"


def load_tasks():
    with open(TASKS_FILE) as f:
        data = yaml.safe_load(f)
    return data["tasks"]


def load_progress():
    if PROGRESS_FILE.exists():
        return json.loads(PROGRESS_FILE.read_text())
    return {"completed": []}


def save_progress(progress):
    STATE_DIR.mkdir(exist_ok=True)
    PROGRESS_FILE.write_text(json.dumps(progress, indent=2))


def run(cmd, **kwargs):
    print(f"$ {cmd}")
    return subprocess.run(cmd, shell=True, cwd=REPO_ROOT, **kwargs)


def git_is_clean():
    r = run("git status --porcelain", capture_output=True, text=True)
    return r.stdout.strip() == ""


def git_revert_working_tree():
    run("git reset --hard HEAD")
    run("git clean -fd")


def git_commit(task_id, phase):
    run("git add -A")
    run(f'git commit -m "Task {task_id}: {phase}" --allow-empty-message -q')


def run_aider_task(task, dry_run):
    files = task.get("files", [])
    for f in files:
        p = REPO_ROOT / f
        p.parent.mkdir(parents=True, exist_ok=True)
        if not p.exists():
            p.touch()

    prompt = task["prompt"].strip()
    file_args = " ".join(f'"{f}"' for f in files)
    cmd = f'aider --config .aider.conf.yml --message "{prompt}" {file_args}'.strip()

    if dry_run:
        print(f"[dry-run] would run: {cmd}")
        return True

    result = run(cmd)
    return result.returncode == 0


def do_manual_step(task):
    print("\n" + "=" * 70)
    print(f"MANUAL STEP — Task {task['id']} ({task['phase']})")
    print("=" * 70)
    print(task["prompt"].strip())
    print("=" * 70)
    ans = input("Type 'done' once you've completed this manually (or 'skip' to skip): ").strip().lower()
    return ans == "done"


def verify_task(task, dry_run):
    verify = task.get("verify", "true")
    if dry_run:
        print(f"[dry-run] would verify: {verify}")
        return True
    r = run(verify)
    return r.returncode == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="from_id", type=int, default=None)
    ap.add_argument("--only", dest="only_id", type=int, default=None)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--stop-on-manual", action="store_true",
                     help="Exit instead of prompting when a manual step is hit "
                          "(useful for running unattended overnight up to the next manual gate).")
    args = ap.parse_args()

    tasks = load_tasks()
    progress = load_progress()
    completed = set(progress["completed"])

    for task in tasks:
        tid = task["id"]

        if args.only_id is not None and tid != args.only_id:
            continue
        if args.only_id is None:
            if args.from_id is not None and tid < args.from_id:
                continue
            if tid in completed:
                continue

        print(f"\n### Task {tid} — {task['phase']} ###")

        if not args.dry_run and not git_is_clean():
            print("Working tree is dirty before starting this task — refusing to continue.")
            print("Commit, stash, or `git reset --hard` manually, then re-run.")
            sys.exit(1)

        if task.get("manual"):
            if args.stop_on_manual:
                print("Hit a manual step with --stop-on-manual set. Stopping here.")
                sys.exit(0)
            ok = do_manual_step(task)
            if not ok:
                print(f"Task {tid} skipped by operator.")
                continue
            completed.add(tid)
            save_progress({"completed": sorted(completed)})
            continue

        ok = run_aider_task(task, args.dry_run)
        if not ok:
            print(f"Aider run failed for task {tid}. Leaving working tree as-is for inspection.")
            sys.exit(1)

        if not verify_task(task, args.dry_run):
            print(f"Verification FAILED for task {tid}: `{task.get('verify')}`")
            print("Reverting working tree and stopping so you can look at it / fix the prompt / "
                  "try a different model for this task.")
            if not args.dry_run:
                git_revert_working_tree()
            sys.exit(1)

        print(f"Task {tid} verified OK.")
        if not args.dry_run:
            git_commit(tid, task["phase"])
        completed.add(tid)
        save_progress({"completed": sorted(completed)})

        if args.only_id is not None:
            break

    print("\nAll requested tasks complete.")


if __name__ == "__main__":
    main()
