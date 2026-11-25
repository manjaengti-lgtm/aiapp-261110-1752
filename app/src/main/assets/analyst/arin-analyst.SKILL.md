---
name: arin-analyst
description: Ask Claude a single headless question for a second opinion and show the stamped answer unchanged. Use whenever code is in front of you and you are not certain, and always before editing files you did not write.
---

# Talk to Claude

One question per call. Claude has no memory between calls: it is a new process every time.

Call the tool **`analyst_ask`**. Never call the shell script by path — tabs have no shell.

Arguments:

- `question` (required): the one question.
- `file` (optional): absolute path under `/root/files`.
- `lines` (optional): `120-140`.

## Showing it

1. Before the call, print:

   ```
   📞 Asking Claude…
   > the question, quoted
   file: TheNameOfTheFile.kt
   ```

2. Then print the tool's text **exactly as returned**, stamp and all. Never paraphrase an answer without showing the stamp.

3. If it fails: print `📞 Claude unreachable — carrying on without it` and answer yourself. Never go silent.

4. If you don't call it: print `✍️ DeepSeek answered alone — Claude not asked.` on its own line.

5. End any turn that used the lane with `📞 Claude consulted N times this turn`.

## When to call

- the code is in front of you and its behaviour isn't obvious;
- before changing something you didn't write;
- when two fixes look equally good and one is riskier.

## When not to call

- pure formatting or renaming;
- questions the file already answers outright;
- once the turn's cap is reached: say so and carry on manually.

## Budget

6 calls per turn in chat and in the reverse-engineering tab. 3 in the Update Arin tab, where read-only questions ("what does this class do", "what breaks if I change it") come before editing Arin's own source.

## Errors worth remembering

- `--dangerously-skip-permissions` fails as root: the script uses `--allowedTools "Read,Grep,Glob,Bash"`.
- Answers that open with code, a backtick, `grep`, `.method` or `const/` are not hedges, even if they also say "roughly".
- The detector strips a hedged head and escalates; only a totally dead line ends in the unreachable line.

## Log

Every call is in `/root/.arin/analyst.log` as `HH:MM:SS \t #N \t nonce \t question`, then `#N \t nonce \t OK|FAIL \t seconds`.
