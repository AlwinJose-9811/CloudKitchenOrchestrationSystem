# Evidence Log

**Status:** Empty template. No command has been run for this project yet, so there is no evidence.

**Rules**
1. This file is the **only** proof that something works. A requirement, test, phase or concept may be called *done* / *passed* only if a matching entry exists below.
2. Paste the **real, unedited output** of the command (trim only long repetitive middles, and mark every cut with `[... N lines omitted ...]`). Never write, summarise from memory or "reconstruct" output.
3. Record failures too (with the fix that followed). A failed run is evidence of honesty, not a problem.
4. Each entry has: id, phase, command (exact), working directory if relevant, real output, exit code, date (UTC, `YYYY-MM-DD HH:MM`), environment (OS, JDK version), commit hash, requirement/test ids it supports.
5. Never paste secrets, passwords, HMAC secrets, session ids or customer data. Redact and say `[REDACTED]`.
6. Environment facts (what was needed to run on a clean machine, which OS was verified) are recorded in §2 and decide what the final documents may claim (NFR-PORT-01).

---

## 1. Index

| ID | Phase | Command (short) | Result | Date (UTC) | Supports (requirements / tests) | Commit |
|---|---|---|---|---|---|---|
| | | | | | | |

(Add one row per entry below, newest last.)

---

## 2. Environment record (fill in during Phase 1; update when another machine is used)

| Item | Value |
|---|---|
| OS and version | |
| JDK (`java -version`) | |
| Git version | |
| Internet needed for first build (what was downloaded) | |
| System libraries / packages needed (must be "none") | |
| Browser used for manual checks | |
| Other OS verified | (none) |
| Real MySQL 8 verified | (not verified) |
| Standalone Tomcat 10.1 WAR deployment verified | (not verified) |

---

## 3. Entry template (copy for each entry)

```
### EV-000  Phase <n>: <short title>

- Date (UTC): YYYY-MM-DD HH:MM
- Environment: <OS>, <JDK>
- Commit: <short hash>
- Supports: <FR-.../NFR-... ; TestClass1, TestClass2>
- Working directory: <path, if not repo root>
- Command:
  <exact command>
- Exit code: <n>
- Output:
  <real output pasted here>
- Notes / follow-up: <anything unusual, failures fixed, items left unverified>
```

---

## 4. Entries

### Phase 1: Skeleton + zero-install spike
(no entries yet)

### Phase 2: Domain + services (no DB)
(no entries yet)

### Phase 3: Persistence
(no entries yet)

### Phase 4: Concurrent pipeline
(no entries yet)

### Phase 5: Resilience
(no entries yet)

### Phase 6: File handling + serialization
(no entries yet)

### Phase 7: Web layer
(no entries yet)

### Phase 8: Integration audit + Review 2 pack
(no entries yet)
