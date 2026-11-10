# Resume prompt — paste into any new Claude Code session opened in D:\Movy

```text
Resume the Movy TV Round 5 revamp in D:\Movy. You are the ORCHESTRATOR; you do not write feature code yourself.

1. ORIENT (read before acting)
   - docs/tv-adapter/ROUND5-PROGRESS.md: status, "Next step", user decisions (never re-ask them), stage log,
     §4b "L0 findings that change later levels".
   - docs/tv-adapter/ROUND5-REVAMP.md: the plan (§0–§2 plus the current level).
   - docs/tv-adapter/R5-AGENT-BRIEFS.md: the common agent preamble and the brief for the current level.
   - docs/tv-adapter/R5-L1-REVIEW.md: open review findings.
   - Run `git status`, `git branch` and `git log --oneline -10`. If the log says a level is parked on a wip/ branch,
     switch to that branch before relaunching the agent.

2. RUN THE LEVELS IN ORDER, one at a time:
   L2 native drawer → fix L1 review findings → L3 search (keyboard proxy, in-page mic, global mic search,
   continue watching) → L4 player settings panel + subtitle size → L5 exit dialog, cache, warm-up, CH± brightness →
   L6 full QA (emulator, then the Bravia) and docs.
   For each level:
   a. Launch ONE implementation agent: Agent tool, subagent_type "general-purpose", model "sonnet" (Sonnet 5.5
      medium), run_in_background true. Prompt = the common preamble from R5-AGENT-BRIEFS.md + that level's
      section of ROUND5-REVAMP.md (+ its brief if one exists) + relevant §4b findings. The agent must not
      commit or push, writes "## Round 5 — Level N" in docs/tv-adapter/REPORT.md, and replies with a
      ~20-line summary.
   b. In parallel, at most ONE more agent, and only a read-only one (e.g. subagent_type "code-reviewer",
      model "sonnet", reviewing the previous level's commit). Never two agents on the emulator. Max two agents
      at once.
   c. When the agent reports, verify its work yourself:
      - `git diff --stat` and a scan of the added lines for source URLs or hosts (forbidden).
      - `npm test`.
      - Gradle in apps/mobile/android: `gradlew.bat testTvDebugUnitTest -q`, with
        JAVA_HOME=C:\Program Files\Java\jdk-17 and ANDROID_HOME=C:\Users\ak818\AppData\Local\Android\Sdk.
   d. Commit on master (conventional message ending with the Co-Authored-By line).
   e. Update ROUND5-PROGRESS.md (status, next step, stage-log row with numbers and open issues, checklist),
      and add any new agent brief to R5-AGENT-BRIEFS.md. Commit the docs.
   f. Update memory (movy-round5-progress) when state changes. Then tell the user in plain words what
      changed, what's not met, and what's next, and start the next level.

3. RULES
   - TV flavour only; phone and Electron unchanged.
   - Never write source URLs or hosts anywhere.
   - No movie media on disk; no second WebView during playback.
   - Site specifics go in tvProfiles config.
   - Files under 800 lines.
   - Don't push. Don't switch models or effort silently.
   - Ask with the AskUserQuestion prompt when a real decision is needed.
   - If the user says "pause": stop the agents (TaskStop), park partial work on a wip/r5-L<N>-* branch so master
     stays working, record the resume steps in ROUND5-PROGRESS.md, commit, and update memory.

Start now: orient, tell me in 3–4 lines where things stand, then continue from "Next step".
```
