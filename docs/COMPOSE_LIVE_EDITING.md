# Editing text without a Save button

*A field guide to live-persisted editors in Jetpack Compose.*

Written for a developer who already knows the standard Compose rhythm — a ViewModel exposes a
flow, the composable collects it, events flow back up as callbacks, recomposition just happens —
and who can build a form with a Save button without thinking. This document is about the case
where that rhythm runs out: an editing surface with **no Save button**, where every keystroke
must already be durable, the same text can arrive at a field from several directions, and the
field has to coexist with an input method editor (IME) that has strong opinions about your text.

Everything here is generic on purpose. Section 9 says where this app implements the pattern and
where its specific decisions are recorded; if the code drifts from this document, that is fine —
this document describes the pattern, not the code.

---

## 1. Why live editing is a different problem

With a Save button, roles are clean: the database is the source of truth, the field is a scratch
area, and "commit" is a single event that pushes scratch into truth. Nothing else writes during
the edit, so there are no conflicts and no ambiguity about who is current.

Remove the button and writing never *happens*; it is always happening. Durability wants every
keystroke in the store as soon as possible. Correctness of the editing experience wants the field
left completely alone while the user is in it. These pull in opposite directions, and the classic
failure modes are what happens when one quietly wins:

* text reverting to an older version mid-typing
* the cursor jumping to the start or end
* autocorrect and swipe input behaving strangely
* the keyboard appearing or disappearing unbidden
* edits silently lost after scrolling away

All of them have the same root: somebody fed store data into a live field, or treated a
recomposition as an instruction.

---

## 2. The ownership models

There are only a few ways to own editable text. Most pain comes from mixing them accidentally,
so name the model you are using before writing any code.

### Model A — commit on save

Local editor state; the store is untouched until an explicit commit. Trivially correct, and the
right default whenever a save action is acceptable: dialogs, settings, anything with a natural
"done" moment. If you can use Model A, use Model A.

### Model B — the field renders the store

The field's value is always derived from store state; each change is written, the flow re-emits,
the field recomposes with the new value. This looks like pure unidirectional data flow, and for
non-text values that change at human speed — switches, sliders, chips, selections — it is exactly
right. For live text it is a trap:

* Every keystroke comes back to you as a new emission. You must suppress the echo ("was this
  emission caused by me?"), which needs bookkeeping that is itself a second copy of the truth.
* Recomposing a text field with a new value while the IME is mid-composition (the half-finished
  word before autocorrect commits) fights the input method: cursor jumps, broken composition
  regions, misbehaving predictions.
* Every keystroke now has a round trip through the persistence layer, and a slow one makes
  typing feel wrong.

Reserve Model B for values that do not change at typing speed.

### Model C — the field is master; the store is a durability sink

Invert the dependency. While the user is editing, the field's own editor state is the authority
on the text. Persistence is a *side effect* of edits (write-through), not the source of the
field's value. The store is read exactly once per field lifetime — when the editor state is
created — and is never fed back into a live field.

This sounds wrong to a database-trained instinct, so be precise about what "source of truth"
means here. The truth is split by role:

* **The editor state is the truth about the editing session.**
* **The store is the truth about durability**: what will be there after the app dies.

They may diverge for milliseconds — never seconds, because the write-through is immediate. If
they diverge for longer, you have built Model D and accepted its risks.

### Model D — C with debounced writes

A variant of C where the write-through is debounced for performance. This is not a free
optimisation: it changes the durability contract from "a few milliseconds of exposure" to "a
debounce window of exposure, per field, at exactly the moment the user puts the phone down or the
process dies". If you choose it, choose it deliberately, document the window, and consider pairing
it with saved-instance-state capture of pending text. Debrief history/undo capture is a *separate*
concern that should be debounced (see rule 6); debouncing the primary write is a different and
more dangerous decision.

---

## 3. Choosing: a short checklist

* Is there a natural commit moment? → Model A.
* Is the value non-text and slow-changing? → Model B is fine.
* Must free text survive an app kill with at most a few milliseconds of loss, with no Save
  button? → Model C with immediate write-through.
* Is a debounce window acceptable, knowingly? → Model D, documented as such.

---

## 4. The rules that make Model C safe

### Rule 1 — Use `TextFieldState`; never mirror text into Strings in the ViewModel

The modern Compose text APIs own the text, the selection and the IME composition region as one
object. That is precisely the state which must not be duplicated:

* Do not hoist the *text* into the ViewModel as a `String` and feed it back into the field. That
  is Model B with extra steps, plus a stale-copy bug waiting to happen.
* Do create and keep the `TextFieldState` in the right scope (per field, per item) and read
  `state.text` at the moment you need to persist.
* `rememberTextFieldState` installs a Saver for free: text and cursor survive rotation and process
  death through saved instance state with no extra work. One of Model C's annoying sub-questions
  ("what happens on rotation?") is thereby outsourced to the framework.

### Rule 2 — Seed exactly once, keyed by identity

The field reads the store once: when its state object is created. Tie that creation to the
*identity* of what is being edited, not to "the latest data":

```kotlin
key(itemId, day) { rememberTextFieldState(initialText) }
```

Use a stable identity (database key, composite key). List position is acceptable only when
position is stable; prefer IDs.

The `key(...)` matters in both directions. When identity changes (the user switched day, item or
account), the old state is discarded and a fresh state seeds from the store — that is the one
moment the store is read. When identity has *not* changed and the store re-emits, the field
deliberately ignores it. If you ever find yourself writing "update the field when the store
changes", stop: during an editing session, a store emission that differs from the field is almost
always your own echo, and applying it is the bug.

### Rule 3 — Hook edits at a point seeding cannot reach

You need "notify me when the user changed the text" without "notify me when the field was seeded
or restored". With the state-based APIs, an `InputTransformation` is the natural tap-point:
transformations run when input is committed by the user or the IME, not when you construct or
restore the state. That asymmetry — seeding invisible, editing visible — is the whole trick, and
it also gives constraints like max length a single natural home:

```kotlin
InputTransformation.maxLength(2000)
    .then { onEdit(asCharSequence().toString()) }
```

Know the caveat: depending on the tap-point, *selection changes* can masquerade as edits (the
callback fires with unchanged text). Either pick a tap-point without that property or absorb it
at the sink — next rule.

### Rule 4 — Make the sink idempotent (read-decide-write, with a no-op filter)

Funnel all edits into one write function structured as: read the existing row for the natural
key; if the incoming text equals what is stored, do nothing; if the incoming text is blank,
delete the row (represent emptiness by absence — or tombstones, but pick one and document it);
otherwise insert or update.

The equality check looks redundant and is anything but. It makes the sink idempotent, which is
what lets the field safely ignore the store (rule 2), and it protects append-only side-logs such
as undo history from phantom edits whose only content is a newer timestamp. A selection change
that walks the whole pipeline should arrive at the sink and be absorbed in silence.

The per-edit read feels expensive. It is the price of correctness without a cache; a "last
persisted text" cache is a second copy of session truth with its own invalidation problem. Text
rows are small: measure before optimising.

### Rule 5 — Keep ordering with a channel and a single consumer

UI callbacks must not suspend and must not launch an independent coroutine per keystroke — you
would lose ordering guarantees. Push each edit onto an unbounded channel and consume it in one
coroutine. The unlimited buffer is fine: edits are tiny and the consumer is a local write. If the
send fails, crash — you have no way to silently recover a keystroke.

### Rule 6 — History/undo is a separate pipeline with a different rhythm

Persistence is per keystroke and immediate. History, if you keep one, wants *snapshots*:
debounced by a few hundred milliseconds and compacted (dedupe near-identical consecutive states,
cap volume), because a history is a record of states, not of every intermediate frame. Run two
pipelines from the same edit event with different policies, and keep them decoupled. Debounce the
history; never silently debounce the main write (that is Model D, rule above).

### Rule 7 — Expect the field to die

Editor state lives per composition, and composition is disposable:

* **Lazy containers dispose off-screen items.** Scroll a field out of view and its state is
  destroyed; scroll back and a fresh state seeds from the store. The text is correct — the store
  caught up, that is rule 4 working — but the cursor position is lost. Accept it, or pay for
  keeping states alive outside composition, which is an eviction problem you almost certainly do
  not want.
* **Identity switches** (rule 2's key) destroy state deliberately; that is the seed-from-store
  moment doing its job.
* **Rotation and process death** are handled by the Saver (rule 1).
* Be wary of `rememberSaveable` for editor text *inside* lazy items: it scales badly and competes
  with your DB seeding. For text, the store is the durability mechanism; saved instance state is
  for UI chrome.

### Rule 8 — Focus is a separate axis, with its own traps

Most "focus bugs" on such screens are really "why did this recompose?" bugs. A fresh composition
looks identical whether it was caused by rotation, returning from a child screen, a lazy item
being recycled, or data re-emitting. An effect that restores focus "on composition" therefore
fires in all four cases, and three of them are wrong. Consequences:

* Never grab focus merely because an item (re)composed.
* If you want platform-parity focus restoration — classic Android restores the focused view
  across rotation — you need a signal that survives composition and distinguishes configuration
  changes from everything else. In practice: a small saveable token (for example the last-seen
  orientation), consumed exactly once by the restore pass. Design consumption carefully: with
  lazy layouts the restore target may compose *after* your first pass, so "consume when actually
  handled, at the target" beats "consume after launching the scroll".
* Read-only fields should stay *focusable* (read-only, not disabled) when users need to select
  and copy from them. That means focus can legitimately sit on a field that cannot be typed
  into, and the IME behaviour around it — no keyboard while read-only, keyboard appearing if the
  field becomes editable while focused — is stock and logical.

---

## 5. A minimal worked example

The smallest correct live-saving field, combining rules 1–5 (no history side-channel shown):

```kotlin
// UI: seed once by identity; report only real user edits.
@Composable
fun EntryField(
    itemId: Long,
    day: LocalDate,
    seed: String,
    onEdit: (String) -> Unit
) {
    val state = key(itemId, day) { rememberTextFieldState(seed) }
    TextField(
        state = state,
        inputTransformation = InputTransformation.maxLength(2000)
            .then { onEdit(asCharSequence().toString()) },
        modifier = Modifier.fillMaxWidth()
    )
}

// Sink: ordering + idempotence. The store is never read back into the field.
class EntrySink(private val dao: EntryDao, scope: CoroutineScope) {
    private data class Edit(val categoryId: Long, val day: LocalDate, val text: String)

    private val edits = Channel<Edit>(Channel.UNLIMITED)

    init {
        scope.launch { for (edit in edits) persist(edit) }
    }

    fun submit(categoryId: Long, day: LocalDate, text: String) {
        check(edits.trySend(Edit(categoryId, day, text)).isSuccess)
    }

    private suspend fun persist(edit: Edit) {
        val existing = dao.get(edit.categoryId, edit.day.toString())
        if (edit.text == (existing?.text ?: "")) return       // no-op absorbed (rule 4)
        if (edit.text.isBlank()) {
            existing?.let { dao.delete(it) }                  // blank = absent
        } else if (existing == null) {
            dao.insert(/* new row from edit */)
        } else {
            dao.update(existing.copy(text = edit.text))
        }
    }
}
```

Note what is *absent*: no flow of text back to the field, no echo suppression, no debouncer on
the main path, no String copy of the text in a ViewModel. The field ignores the store; the store
absorbs the field's traffic; correctness survives scrolling, identity switches and rotation.

---

## 6. Symptom → usual cause

* Text reverts to an older version mid-typing → something feeds store emissions into a live
  field (violates rule 2).
* Cursor jumps to the start/end while typing → a value round-trip (Model B) or state recreation
  mid-edit.
* Keyboard hides or reappears unexpectedly during navigation → focus re-grabbed or held across
  transitions (rule 8).
* History full of duplicates with ever-newer timestamps → the no-op filter is missing at the
  sink (rule 4).
* Edits lost after scrolling away → the reseed read stale data (seed not taken from the store),
  or writes were not immediate (accidental Model D).
* First characters mangled with autocorrect on → fighting the IME composition region, usually a
  String-mirror design.

---

## 7. Testing this pattern

The failure modes are recomposition-timing-dependent, so put confidence at two levels:

* **JVM, sink logic** (fast, most of the value): read-decide-write decisions, no-op absorption,
  blank-deletion, ordering, history debouncing — with the field abstracted away and the store
  faked. A fake must mirror the real query contract (including ordering), or it will make correct
  production code look wrong.
* **Instrumented, contracts at the UI**: (a) forcing a data re-emission upstream must not reseed
  a typed-into field, and further typing must append; (b) a cursor-move gesture must not write
  history; (c) a re-composed item must not grab focus. Each pins one of the rules above. Rotation
  itself is poorly served by instrumentation; verify it manually once, then rely on the contracts.

Write the seed-contract and no-op tests *before* refactoring such a screen. They are cheap, and
they catch exactly the regressions this design is prone to.

---

## 8. One-page summary

* Pick an ownership model deliberately: commit-on-save (A), store-renders-field (B, non-text),
  field-is-master/store-is-sink (C, live text). Never mix accidentally.
* In C: seed once by identity; write through immediately; read-decide-write with a no-op filter;
  channel + single consumer for ordering; history as a separate debounced pipeline.
* `TextFieldState` + `InputTransformation` give you the seeding/notify asymmetry and free
  rotation survival.
* Lazy disposal recreates state by design: text survives via the store, cursors do not.
* Focus is a separate axis: restore only on a genuine configuration change, never on mere
  recomposition; keep read-only fields focusable so select/copy keeps working.

---

## 9. Where this app implements it

This app's home screen is a worked example of Model C: immediate write-through of entries, a
debounced and compacted history side-channel, keyed seeding per (category, day), and a
configuration-gated focus restoration. The design rationale — including the alternatives rejected
and the observed misbehaviours that motivated each rule above — lives in
`docs/HOME_SCREEN_NOTES.md` (especially its §2 and §6), and the contracts are pinned by the
instrumented tests referenced there. Read that document for the specifics; this document will not
track them.

---

[I asked ChatGPT to give an opinion on this document, which was written at my request by GLM 5.3 Flash at the end of the latest round of discussion and code improvements in this area. After some back and forth and a brief discussion about InputTransformation, ChatGPT wrote - at my request - the following commentary, here presented as section 10.]

# 10. Commentary on this tutorial

The tutorial above presents a coherent design rather than a complete taxonomy of Jetpack Compose text-editing architectures. That distinction is worth keeping in mind. Its central recommendations are sensible for the particular problem it describes — a live-persisted text editor where the editing experience must remain authoritative during an editing session while persistence happens continuously — but some statements are deliberately stronger and more categorical than they would need to be in a general-purpose Compose reference.

The most important idea is the separation between **editing state** and **durable application state**. A `TextFieldState` contains information that is inherently part of an interactive editing session: not merely the characters, but also selection and IME composition state. Treating that whole object as an ordinary projection of a database value can cause problems that do not arise with simpler UI state such as a checkbox or selected item. For this kind of editor, allowing the text field to remain authoritative while editing, and treating persistence as a write-through side effect, is a legitimate and useful architecture.

That does not mean that the database can never update the field, or that externally controlled text is intrinsically wrong. It means that **continuously feeding persisted values back into an actively edited text field is not automatically a harmless consequence of unidirectional data flow**. If an application needs external changes to an actively edited field to appear immediately, it needs an explicit policy for reconciling those changes with the user's current editing state. This tutorial intentionally chooses not to solve that problem: the active editor wins, and persistence records its edits. That is an architectural decision rather than a universal property of Compose.

### A note about `InputTransformation`

The use of `InputTransformation` as an edit-notification point deserves particular care.

The state-based Compose text APIs deliberately distinguish operations that modify the editing buffer from the act of initially constructing or restoring a `TextFieldState`. That makes an input transformation a useful place to observe or react to input that passes through the text editing pipeline, while avoiding the particularly undesirable pattern of observing a `String` and then writing that `String` back into the field.

In this design, the transformation is doing two jobs: it performs an actual input constraint (`maxLength`), and it provides a convenient point at which the resulting text can be handed to the persistence pipeline. That is somewhat more than the most obvious interpretation of an `InputTransformation`, so it is worth treating it as a deliberate use of the API rather than assuming that `InputTransformation` is generally intended to be an application-wide "text changed" callback.

The important thing is the documented/observed behaviour of the particular Compose API and version being used. If the callback can also run for operations that do not actually alter the characters — for example, certain selection changes — the persistence layer should not have to care. The sink's read/compare/write behaviour makes such events harmless. In other words, the architecture does not require the UI callback to perfectly distinguish *every possible kind of editor-state mutation* from a textual edit. It only requires the persistence sink to distinguish a real change in durable content from a no-op.

That is a useful property in its own right: **make the boundary tolerant of harmless extra notifications rather than making the UI responsible for proving that every notification is semantically meaningful.**

If a future version of Compose provides a more direct, stable and appropriate mechanism for observing exactly the desired text-edit events, it would be reasonable to reconsider this implementation. Conversely, there is no particular virtue in replacing a working arrangement merely because the callback is being used slightly beyond its most obvious role. The relevant question is whether the behaviour is supported by the API contract and whether the resulting semantics are tested.

### A note about saved state and durability

`TextFieldState` being saveable and the database being durable are complementary mechanisms, not interchangeable ones.

Saved instance state can preserve useful editing state across configuration changes and other forms of activity recreation for which Android provides saved state. It is therefore valuable that `rememberTextFieldState` participates in Compose's saving machinery. But saved instance state should not be treated as the application's durable store, nor should "survives process death" be interpreted as "the text is guaranteed to survive arbitrary process termination."

The database is still the important durability boundary in this design. Saved state is useful for preserving the *editing session* when Android can restore that session; the database is what allows the application to reconstruct the correct content when the composition, activity, or saved state is gone.

This distinction also explains why losing a cursor position when a lazy item leaves composition is not necessarily a data-loss bug. The editor's ephemeral state and the entry's durable content have intentionally different lifetimes.

### A note about the channel

The tutorial recommends a channel with a single consumer. That is a reasonable implementation of an important invariant, but the invariant itself is broader:

> **Edits must reach persistence through a serialized path that preserves their ordering.**

A channel is one way to accomplish this. A future implementation might use a different mechanism while preserving the same guarantee. The reason for the single consumer is not that Compose requires channels; it is that launching an independent persistence coroutine for every keystroke makes ordering and concurrency unnecessarily difficult to reason about.

Likewise, `Channel.UNLIMITED` should be understood as an implementation choice justified by the expected size and local nature of these events, rather than as a general recommendation for text editing. If the persistence path ever becomes slow, remote, failure-prone, or otherwise substantially different, buffering and backpressure deserve to be reconsidered.

### A note about external writers

One architectural question intentionally left mostly implicit is what happens if something other than the active editor changes the same database row.

The design works particularly cleanly when the database emission observed by the rest of the application is normally just an echo of the user's own persistence. If another screen, synchronisation mechanism, background process, or other external actor can modify the same entry while it is being edited, then the statement that a differing store value is "almost always your own echo" is no longer universally true.

That does not invalidate the architecture. It simply means that an application with genuine concurrent writers needs an explicit conflict policy. Possible policies include allowing the active editor to continue and subsequently overwrite the external change, notifying the user, merging changes, or adopting some other application-specific rule. What should be avoided is allowing this policy to emerge accidentally from whatever happens to trigger recomposition.

### A note about the strength of the rules

Several rules in this tutorial are intentionally phrased as prohibitions:

* never mirror text into a ViewModel `String`;
* never feed store emissions back into an active field;
* never grab focus merely because something recomposed;
* never debounce the primary write unless the durability contract explicitly permits it.

These should be read primarily as **rules for this architecture**, not as claims that the opposite is always bad Compose programming.

There are perfectly legitimate applications using hoisted `String` values, externally controlled text fields, delayed persistence, or programmatic focus. The reason those approaches are problematic here is that they interact badly with the particular contract being sought: continuous durability without disrupting an active IME-driven editing session.

That distinction is worth preserving because it makes the document more durable as project documentation. A future maintainer should be able to ask "does this invariant still hold?" rather than mechanically following a recipe even after the surrounding application has changed.

### Overall assessment

The design described here has a useful and internally consistent division of responsibilities:

**The text field owns the live editing session.
The persistence layer owns durable content.
The persistence boundary is serialized and idempotent.
The database is not continuously used to overwrite an active editor.
History is a separate concern from durability.
Focus is a separate concern from recomposition.**

Those are the important invariants. Individual implementation details — the precise transformation callback, the channel, the database query, the Compose API used to save state, or the mechanism used to restore focus — are more replaceable.

If the implementation changes in the future, these invariants are therefore a better guide than preserving the exact code shown in the worked example. In particular, a future refactor should be suspicious of any change that causes persisted text to be continuously round-tripped through an actively edited `TextFieldState`, introduces concurrent unordered writes, makes durability dependent on a debounce window without explicitly changing the contract, or treats recomposition as an instruction to manipulate focus.

The tutorial is consequently best regarded as a **field guide to one carefully chosen solution**, rather than a statement of universal Compose doctrine. Within that scope, its mental model is sound and provides a useful defence against the kinds of superficially "cleaner" refactors that can make interactive text editing substantially less reliable.

