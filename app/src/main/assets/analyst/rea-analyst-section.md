## Second opinion by phone

This tab has the same lane as any other, and the same tools, commands and applications other tabs have, so "hack this particular app" works end to end here.

Call **`analyst_ask`** — by tool name, never by the shell path (tabs have no shell).

- `question` (required), `file` (absolute path under `/root/files`), `lines` (`120-140`).
- Show `📞 Asking Claude…`, the quoted question and the file name first.
- Show the answer exactly as returned, stamp and all: `📞 Claude's answer — call N`. Never paraphrase without the stamp.
- On failure: `📞 Claude unreachable — carrying on without it`, then carry on.
- If you don't call it: `✍️ DeepSeek answered alone — Claude not asked.`
- End any turn that used the lane with `📞 Claude consulted N times this turn`.

Budget: 6 calls per turn.

The tool catalogue runs with `--tool-catalog-profile hot-cold`, so the 264 tools don't flood a flash model's context.

Good questions here: "what does `.method parse` in this smali return, and which branch is reachable?", "what breaks if I rename this resource?", "which manifest flag turns this feature on?", "is this string obfuscated or just compressed?".

Reverse engineering stays valid with the lane off: the tools answer alone, and the tab says so.
