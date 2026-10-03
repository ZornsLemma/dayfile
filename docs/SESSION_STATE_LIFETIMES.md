# Session state lifetimes

> Snapshot of an extended discussion (September 2026) about **which piece of app state lives in
> which container, and why**. Written for a future session that has to pick this up cold.
>
> **Status: the code is settled. The open question below is a *possible future refactor*, not a
> planned change.** Do not act on it without re-reading the "Should we do it?" section.

---

## 1. The two lifetimes that matter

Android gives us two different "does it survive?" questions, and confusing them is the root of
most of the pain in this area.

### Process-bound

Dies with the process. Survives configuration change (rotation) and survives an OS low-memory
kill **while the task is still held**.

* DataStore (Room, preferences) — survives everything, including swipe-away.
* Most ViewModel state.

### Task-bound

Dies **when the user removes the task from the overview screen** (swipe-away). Survives an OS
kill of a backgrounded process (the task is still held).

* The saved-state bundle / `SavedStateHandle`. This is the *only* container with this lifetime.

**The whole difficulty of this app lives in the fact that one piece of state — the background
timestamp — has a *task-bound* lifetime while everything else is process-bound.**

---

## 2. The four pieces of state, and where they live now

| State | Container (current) | Lifetime it needs | Correct? |
|---|---|---|---|
| Selected date | DataStore (`AppStateRepository`) | process-bound | works, but see §4 |
| Protection override | DataStore (`AppStateRepository`) | process-bound | works |
| History filter | DataStore (`AppStateRepository`) | cleared by 10-min reset | works |
| **Background timestamp** | **`SavedStateHandle` (`ResetViewModel`)** | **task-bound** | **yes — this is the fix** |

### What changed in September 2026

The background timestamp **used to live in DataStore**. That was the bug. DataStore survives
swipe-away, but the timestamp's correct lifetime is task-bound — it must *not* survive a
swipe-away, because a swipe-away means "come back fresh".

The result was a fork that was invisible to the user but real in behaviour:

* swipe away **while foregrounded** → `ON_PAUSE` never fires → no timestamp recorded → fresh.
* background, **then** swipe away → `ON_PAUSE` fires → timestamp recorded → DataStore keeps it →
  **preserved** (a non-today date).

Same user action, different outcome. The fix was a **container swap, not a logic change**:
`ON_PAUSE` writes `T = now`; `ON_RESUME` measures `now - T`, resets if `> 10 min`, then clears
`T`. Identical logic. Only the storage moved, to a `SavedStateHandle` held by a new
activity-scoped `ResetViewModel`.

Now both swipe cases collapse onto "fresh", because the bundle is discarded with the task. No
detection code needed — the platform's own state-preservation semantics encode the answer.

### Why the timestamp lives in a ViewModel at all

`SavedStateHandle` can't exist outside a ViewModel. `ResetViewModel` is scoped to the
**activity**, not the home back-stack entry, so it survives rotation *and* survives a successful
reset navigation (`popUpTo(startDestination, inclusive=true)` destroys the home entry and its
ViewModel, but not the activity's store). It dies only with the process. That is the lifetime the
timestamp needs.

## 3. The policy, stated as one question

Forget "background vs foreground vs swipe". Ask exactly one thing when the app comes back:

> **Is the task still held?**

* task gone → fresh session (today, default protection, home screen).
* task kept → 10-minute rule on time since the last foreground exit.

The saved-state bundle is discarded precisely when the task is removed, so the platform answers
this for free. This is the same guarantee the app *already ships on* for the focus-restore gate
(`restoredOrientation` survives process death and dies on swipe-away) — so adopting it for the
timestamp is not a new unverified assumption.

### Case table (current design)

| What happens | Task | Bundle on relaunch | Result |
|---|---|---|---|
| First launch | — | empty | fresh |
| Pause → resume (2m) | kept | `T` written, cleared on resume | preserve |
| Background 15m → resume | kept | `T` = pause time | **reset** |
| Background → kill (task kept) | kept | `T` survives bundle | **reset** |
| Foreground → kill (task kept) | kept | `T` = null (cleared at resume) | fresh |
| Swipe, foreground | gone | never written | fresh |
| Background → swipe | gone | bundle discarded | fresh |
| Upgrade / redeploy | — | `T` = null if recently foreground | fresh |
| Rotation | kept | `T` cleared on resume | no spurious reset |

**The one case the old code got wrong:** background → swipe-away preserved instead of going
fresh. That was the entire bug. Everything else was already correct.

### "Clear `T` on resume" is load-bearing, not a debugging leftover

You originally added it weeks ago possibly for debugging. It has a real job: it makes the
*foreground-kill* case robust. `T` is null whenever the app is in the foreground, so a process
death that happens while foregrounded reads null and starts fresh **whether or not the bundle
happened to survive that particular kill**. It removes a dependency on the one lifecycle detail
that is least guaranteed. Keep it.

---

## 4. The "flash" — why you see tomorrow briefly before it snaps to today

This is expected and documented, and it is **not** a logic bug.

The reset runs on the `ON_RESUME` lifecycle observer, which fires **after** the first
composition. So on relaunch, `HomeViewModel` reads the stale date out of DataStore, the home
screen composes with it for one frame, and *then* `onReturnToForeground()` writes today.

The date staying in DataStore is correct and necessary — it is durable user data and must survive
process death. Only the *timestamp* moved. The flash is purely the ordering artifact.

### Is the flash avoidable?

**Partly, and it's worth knowing the split.**

* **Fresh-launch case (process died): yes, avoidable.** Run the reset in `onCreate`, before
  `setContent`, and `HomeViewModel`'s first read is already today. But there's a catch: the
  `StateFlow` starts at `null` (`stateIn(..., SharingStarted.Eagerly, null)` on `uiStateFlow` in
  `HomeViewModel`), so even with
  perfect ordering a new collector first sees `null` and `HomeScreen` renders nothing. You'd trade
  a wrong-date flash for a blank-screen flash unless you also seed the StateFlow with a real
  value.

* **Live-resume case (process survived, 10 min backgrounded): no.** The activity is not
  recreated, so `onCreate` doesn't run. The reset has to fire on `ON_RESUME`, after composition.
  So if the user was on a non-today date at background time, the stale-date frame is
  near-unavoidable.

**Verdict:** the flash is avoidable for the fresh-launch case but not cleanly for live-resume,
and even the fresh-launch fix needs the null-initial-value problem handled too. It was decided
to leave as-is.

### The date-change animation is not guaranteed in all cases

`HomeScreen` crossfades the date label (`Crossfade(targetState = dateLabel)` in `HomeScreen`). This
fires only when the home screen composes with a stale date and then updates — i.e. when you were
**on home** at background time. If you were on another screen (history, settings), the reset writes
today to DataStore *before* the `popUpTo(home)` navigation, so home composes fresh with today
already in place and there is no date to animate *from*.

So: backgrounded-on-home → date crossfade. Backgrounded-elsewhere → silent landing on home. Both
give *a* signal; they're just different mechanisms, each appropriate to its case. This was judged
acceptable and left alone.

Note also that the home destination is the one case with no `enterTransition` set — every other
destination declares an explicit `slideInHorizontally` or `slideInVertically`. So the reset
navigation back to home doesn't slide, while a user-driven navigation into any other screen does.
That asymmetry looks deliberate and is probably correct: an automatic reset shouldn't animate like
a user-driven nav, because you didn't press anything — you were just returned to where you belong.

---

## 5. The open question: should the selected date, protection override, and history filter
   also move to a `SavedStateHandle`?

### Where the question came from

The question originally put to this design was whether the selected date and the protection
override "should be in a savedstatehandle/viewmodel instead of a datastore". Fair question. The
short answer is **no, not now** — but the reasoning that settled it is weaker than it looks, and
the alternative is genuinely viable. This section is the honest version.

### Why they currently live in DataStore

1. **Multi-screen observability.** `selectedDate`, `protectionOverride`, and the history filter
   are observed as `Flow`s by `HomeViewModel` *and* `HistoryViewModel`. DataStore is the
   established observable store here. A `SavedStateHandle` lives in one ViewModel; sharing it
   across ViewModels means threading it through or duplicating state. Real cost, not nothing.

2. **Atomicity of date + override.** `AppStateRepository.setSelectedDate` writes the date *and*
   drops the override in one DataStore edit. Splitting them across two stores loses that
   atomicity — a crash between writes could leave a date with a stale override.

3. **DataStore is the better observable store.** Per-key typed `Flow`s, composed easily
   (`HomeViewModel.dateStateFlow` combines three of them). A handle's `getStateFlow` is clunkier,
   holds one untyped value per key, and doesn't compose the same way.

### The argument this rests on — and its weakness

The case leaned on a phrase in `SPEC.md` §Protection:

> "a manual override therefore lasts until the next date change, **surviving the app being
> killed and restarted in the meantime**."

That has been read as requiring the override to survive a swipe-away, which only DataStore does.
**That reading is too strong.** "Killed and restarted" most naturally means *the OS kills the
process and we come back reincarnated*, not *the user swipes the task away*. The SPEC is ambiguous,
and behaviour in corner cases is a judgement call rather than something to treat as binding.

So: the override's container is a **genuine taste call**, not a SPEC-forced one. Both readings are
defensible.

### The alternative is viable

Under a `SavedStateHandle` design for the date, the swipe cases work without engineered logic:

* background 5m, task kept, kill → bundle survives → date preserved → <10 min → no reset → preserved ✓
* swipe away → bundle discarded → date null → first-launch logic → today ✓

"Fresh on swipe" becomes automatic, exactly like the timestamp. The 10-minute rule **still needs
reset logic** in both designs (background 15m, task kept → bundle has yesterday → must overwrite),
so you don't save the machinery — you just stop using the timestamp to *drag* the DataStore date
around.

### The clean split, if you ever did it

| Container | Would hold | Lifetime |
|---|---|---|
| `SavedStateHandle` (session ViewModel) | background timestamp, selected date, protection override, history filter | task-bound / session |
| DataStore | entries, categories, settings, history snapshots | durable, survives everything |

That is internally consistent: **session state in the handle, durable data in DataStore**. The
current architecture is *mixed* — DataStore holds session state (date, filter, override) *and*
durable data. That mixing is the source of the "it feels a bit artificial" feeling.

### Honest costs of the clean split

* Loses multi-screen sharing of the date/override (needs threading or duplication).
* Loses the atomic date+override write.
* Bigger migration; the tests that currently pin DataStore state would need rewriting.
* The reset logic stays the same size — it just operates on session state in the handle instead
   of on DataStore state via a signal.

### Where we landed

**Don't do it now.** The current design is correct, tested, and the convolutedness is contained in
one place (the timestamp signal). The clean split is a "someday" refactor — for v2 or v3, not
v1 — and it's worth having written down as the target direction.

If you ever do it, the direction is: session state → `SavedStateHandle`, durable data → DataStore.
The timestamp move already proved the mechanism works; extending it is the same pattern, more of
it.

## 6. Honest open doubts

Written down because they're the kind of thing that bites later. None of them block the current
code.

1. **Does the saved-state bundle reliably survive a background-kill?** Probably yes — this is the
   guarantee `SavedStateHandle` is built on, and the app already depends on it for the focus gate.
   Not verified on a device.

2. **Does the bundle reliably get discarded on swipe-away?** This is the shakier of the two. The
   reasoning is that the bundle lives in ActivityManager outside the app process and is discarded
   with the task. Historically task-removal + `onSaveInstanceState` behaviour has been
   version-dependent. **This is the linchpin of the whole design.** If it ever *didn't* discard,
   you'd get the *preserve* direction on a background-then-swipe — which is no worse than the
   trivial alternative, so the downside is bounded.

3. **Does the bundle survive an upgrade/redeploy?** Murky — install events mangle task state.
   With clear-on-resume, a recently-foregrounded app has `T = null` → fresh on redeploy, which is
   probably what you want for a new build anyway. So it's not a problem, but it's not *guaranteed*
   to be fresh — it's fresh *because* of clear-on-resume, not because of the bundle.

4. **Does the bundle survive a foreground-kill (app killed while visible, task kept)?** Uncertain.
   This is exactly why clear-on-resume matters: it makes that case robust regardless.

5. **Is "fresh on swipe" actually the right UX?** It is not a documented Android convention, and
   SPEC says the opposite for a brief absence. It is a defensible app-level policy ("go away, I
   don't want to see you here, invite me back fresh"). But it is a preference, and a swipe-away is
   often a mistake, so preserve is friendlier. Genuinely 50/50, and either is fine.

6. **Should a swipe-away lose the protection override?** The current leaning is yes, no strong
   objection. Under "fresh on swipe", a fresh today means default protection for today, which is
   *unprotected* — so losing the override on swipe lands today unlocked, which is exactly the
   default. That's coherent. But a user who locked today and then swiped away might expect the
   lock to survive. Genuine taste call.

## 7. Quick reference: the one question that decides everything

> **Is this state *user data*, or is it *session signal*?**

* **User data** (entries, categories, settings, history snapshots, and — for now — the selected
  date and protection override) → **DataStore**. Survives everything. That is the *correct*
  lifetime for durable data.
* **Session signal** (the background timestamp) → **SavedStateHandle**. Task-bound. That is the
  *correct* lifetime for a signal.

The timestamp was the exception, not the rule. It moved because it's the only piece of app state
here whose lifetime is task-bound. Once you split on *that* axis instead of "does it survive
process death?", the answer falls out without thinking about corner cases at all.

---

## 8. What was recorded in SPEC.md (September 2026)

The ambiguity in SPEC.md §Protection ("surviving the app being killed and restarted") was
deliberately left rather than sharpened. A note now records that the phrase could mean either an OS
kill with reincarnation or a user swipe-away, that it is **not** being used to pin down swipe-away
behaviour, and that the current implementation (fresh on swipe; override does not survive it) is a
chosen behaviour, not a SPEC requirement, and may change.

The override-on-swipe question was **not** resolved. The leaning — that a swipe-away should lose
the protection override — is recorded as plausible and coherent (a fresh today defaults to
unprotected), but it is a taste call and was not acted on.
