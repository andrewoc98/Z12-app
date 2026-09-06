# Aider agent harness for the Z12 Challenge plan

Drives Aider through `plan.md`'s 26 numbered tasks with minimal input, one commit per task,
stopping (and reverting) on the first failed verification instead of piling up broken commits.

## Setup

```bash
pip install pyyaml --break-system-packages
# make sure `aider` is installed and can reach your local model, e.g. Ollama:
ollama pull qwen2.5-coder:7b
```

Copy these three files into the root of your Z12 app repo (next to `plan.md`):
- `.aider.conf.yml`
- `tasks.yaml` (or leave `run_agent.py` to find it next to itself — either works)
- `run_agent.py`

Make sure the repo is a git repo with a clean working tree (`git init` if needed, commit
`plan.md` first) — the driver refuses to start a task on a dirty tree, and reverts with
`git reset --hard` on verification failure.

## Running it

```bash
# Run everything from the top, pausing for manual steps as it hits them:
python3 run_agent.py

# See what it would do without touching git or calling aider:
python3 run_agent.py --dry-run

# Resume after a manual step or a fix, starting at a given task id:
python3 run_agent.py --from 12

# Run one task in isolation (handy while tuning a prompt that failed verification):
python3 run_agent.py --only 19

# Run unattended overnight up to the next step that needs you, then stop cleanly:
python3 run_agent.py --stop-on-manual
```

Progress is tracked in `.aider-agent/progress.json` so re-running skips completed tasks.

## Why this design

- **One task, one commit, gated by verification.** Matches the rule already in plan.md
  Section 8. A 7B model will sometimes produce something that looks plausible but doesn't
  build — `verify` catches that before it becomes a commit, and the working tree gets reset
  rather than left half-edited.
- **Narrow file context per task.** Each task only `/add`s the 1-3 files it should touch, per
  plan.md's own guidance that this model reasons better with a small focused file set.
- **Manual gates are real gates, not skipped.** Tasks 2, 5, 8, 11, 15, 18, 22, 26 need a human
  (DevTools inspection, a physical/emulator device, a Firebase console) — the script pauses
  and asks you to confirm rather than pretending Aider did them.
- **`--stop-on-manual`** lets you kick off a batch of automatable tasks (e.g. all of Phase 4)
  before you go do something else, without it silently blocking on an `input()` prompt in a
  terminal you're not watching.

## Tuning for a weak model

If a specific task keeps failing verification:
1. Run it in isolation: `python3 run_agent.py --only <id>`.
2. Read the Aider chat log in `.aider-agent/chat-history.md` to see what it tried.
3. Tighten the `prompt` in `tasks.yaml` for that task — shorter, more literal instructions
   help small models more than architectural context does.
4. If it still won't pass, do that one task by hand and mark it complete by adding its id to
   `.aider-agent/progress.json`'s `completed` list, then resume from the next one.

Consider swapping to a larger model (even temporarily, via `--model` override on the CLI) for
tasks 16, 19, and 20 specifically — FCM integration and HTML-scraping/diffing logic are the
parts of this plan most likely to need real reasoning rather than pattern-matched boilerplate.
