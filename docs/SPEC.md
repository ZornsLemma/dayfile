# Dayfile Specification

# Dayfile

## Purpose

Dayfile is a simple, reliable and professional Android application for recording short notes about daily life with as little ceremony as possible.

The application's purpose is to help users capture information before it is forgotten. Some notes become part of a permanent personal record. Others are temporary reminders that will later be copied into another system. Both are equally valid uses of the application.

The application deliberately favours speed, simplicity and reliability over feature richness.

---

# Product philosophy

Dayfile exists to minimise the effort required for a user to record information.

The application should never require the user to think about *how* to record information before they can simply write it down.

The application deliberately keeps structure to a minimum. The user chooses an appropriate category, then writes unrestricted text for that category. Beyond selecting the category, the application intentionally imposes no structure on the user's notes.

This deliberately sacrifices automatic analysis and reporting in exchange for allowing users to record whatever they wish, in whatever wording makes sense to them, with minimal interruption.

The application is intended to become a mature, stable tool. Once it fulfils its purpose well, future development is expected to consist primarily of refinement, bug fixes and carefully chosen quality-of-life improvements rather than continual expansion of scope.

When making design decisions, prefer the solution that reduces friction for everyday note-taking.

---

# Design principles

The following principles should guide design decisions throughout the application.

* Fast note-taking is more important than feature richness.
* Reliability is more important than novelty.
* Simplicity is preferred over flexibility unless flexibility provides clear user value.
* User data is valuable.
* The application should feel calm, predictable and professional.
* Standard Android behaviour should normally be followed unless there is a good reason to differ.

---

# Example use cases

The application supports many different kinds of note-taking through its category system.

Examples include:

* "I just ate four slices of buttered toast. I want to make a note of that."

* "I just spent $4.88 at the corner shop. I'll later enter this into my accounting software on my PC, but I need somewhere reliable to note it down immediately."

* "I just went for an hour's walk. Let me record that so I can look back later and see how consistently I've been exercising."

These examples deliberately include both permanent records and temporary reminders. Dayfile exists to capture information reliably; what the user ultimately does with that information is up to them.

The central concept is that information is grouped into logical days, and the primary action performed by the application is adding or editing notes for the current day.

---

# Current development status

Version 0.1 is the first public release and is the version this document describes. All intended
functionality exists and is believed to work, but as of writing it has not been used in anger by
anyone other than the author, so treat the first public version as a period of bug reports rather
than a finished product. The code carries no outstanding `TODO` or `REVIEW` comments;
`docs/TESTING_LIMITATIONS_AND_REVIEW.md` and `docs/ROADMAP.md` record what is and is not covered
instead.

The specification itself will evolve alongside the application, although large changes are not likely at this stage.

Where the specification leaves behaviour undefined, ambiguous or is inconsistent with the actual implementation, clarification should be sought before implementing significant changes. It may be that the specification is out of date rather than the implementation being incorrect.

As design decisions become permanent they should be reflected in this specification.

---

# Core concepts

## Category

Categories are user-defined headings.

The application is pre-populated with the following default categories:

* Money
* Diet
* Exercise
* Miscellaneous

Each category has:

* a display name
* a stable ordering value
* an enabled/disabled flag

Categories do not need consecutive ordering values.

Disabled categories remain in the database but do not appear on the home screen.

## Entry

An entry is the unrestricted text belonging to one category on one logical day.

Entries intentionally contain plain text only.

The application does not attempt to impose structure such as exercise types, food databases, spending categories, formatting or rich text.

## Day

The application groups entries into logical days rather than strict calendar days.

The user defines the time of day at which a new day begins. This is specified as a full `hh:mm` value (for example, `04:00`).

The default value is 4:00 AM.

For example, if the configured day starts at 4:00 AM then an entry made at 00:30 on 5 July belongs to the logical day of 4 July.

Note: Earlier drafts specified only an hour (ignoring minutes). This was revised to a full time of day because Android's standard Material Design 3 time-picker component naturally produces an `hh:mm` value. Using the standard component provides a more familiar and obvious interface for users, and avoiding a custom hour-only picker prevents presenting (and then discarding) minute values that the user might select. Either approach would satisfy the application's functional needs; the full-time approach was chosen for user-interface familiarity and to follow standard Android behaviour.

---

# Functional requirements

## Home screen

The home screen is the primary interface of the application.

Its basic layout consists of:

* a Top App Bar
* a "toolbar" with previous date (left aligned), current date (centred within the available middle area, left aligned within that area, and tappable to bring up a date picker), optional protection icon (see below) and next date buttons (both right aligned)
* one section per enabled category
* one editable text field beneath each category heading

The home screen is fundamentally an editing screen.

There is intentionally no explicit Save operation.

If a user opens the application, edits text and leaves the application, they are entitled to assume that their information has been safely recorded.

Within that constraint it may be desirable to support limited undo or cancellation of accidental edits. Such functionality must never introduce a manual save workflow.

### Returning to the application

The application distinguishes between temporarily leaving the application and starting a new session.

If the application has been out of the foreground for less than 10 minutes, returning to it resumes the previous state exactly as it was left: the same date is shown, and where possible the same scroll position on the home screen's category list.

If the application has been out of the foreground for 10 minutes or more, returning to it starts a new session. The application displays the home screen and selects the current logical date, discarding the back stack regardless of which screen the user was on when they left the app. The one exception is that when the user left from the home screen itself, that entry is retained rather than recreated: the visible outcome is identical, and recreating it would throw away the home screen's ViewModel and in-progress field state for no benefit. See `docs/HOME_SCREEN_NOTES.md` §7.7.

Starting a new session discards any transient UI state from the previous session, including partially completed edits on settings or category-management screens, and any date picker left open on the home screen. Changes already committed to the application's data are unaffected.

This behaviour is intended to balance normal Android state restoration with Dayfile's primary use case of quickly recording something happening now. If the user is moving between dates in the app and toggling between other apps as they multi-task or cross-reference things, state restoration is important. If the user has "left" the app in a non-default state and returns to it later, being able to make a note related to the current day with minimum fuss is important. We use the 10 minute threshold to infer the user's likely intention.

The 10 minute threshold is currently hard-coded as a constant in `ResetViewModel`.

If the app remains continuously in the foreground, the passage of time alone never changes anything: the selected date and its protection/current-day classification are only re-evaluated when the user navigates or changes a relevant setting, even if the clock crosses the day-start boundary while the app is open.

### Focus and the on-screen keyboard

The home screen is both a viewer and the app's primary editor, so the on-screen keyboard is a help while the user is editing and a hindrance while they are browsing. To keep the two apart, focus and the keyboard are **not** part of the state restored on return:

* When the app leaves the foreground, any focused text field loses focus and the keyboard is hidden. Returning to the app therefore never re-pops the keyboard behind the user's back; the screen is a clean browse, and the user taps the field they want to edit to bring the keyboard back. Because edits are persisted immediately, nothing is lost by this - resuming is simply a tap on the field.
* The one exception is rotating the phone while the app stays on screen: the focused field keeps its focus (and the keyboard) across the rotation, so the user can keep typing or switch to a landscape keyboard without having to re-tap. This is standard Android behaviour.
* Dismissing the keyboard with the back button while staying in the app does **not** remove focus. This is deliberate, so that a user who dismisses the on-screen keyboard in order to type on a physical or Bluetooth keyboard can keep typing without re-tapping the field.

The category add/edit dialog is a separate, modal sub-task and keeps stock Android focus behaviour: the user is unlikely to leave the app with it up, and clearing focus there would fight the dialog's own focus model. 

Since returning to the foreground after 10 minutes or more is conceptually behaving as if "the app is starting fresh", some other actions are tied to it:
- User filters on the history screen are reset to the defaults.
- History pruning is triggered at this point. The exact time this is done generally doesn't matter to the user, but by doing it here the "0 days of history" case fits in with the mental model that the app's internal state is discarded after 10 minutes in the background.

### Protection

Entries for dates other than the current date are automatically protected, so the user cannot accidentally edit them by mistake after browsing around. The current date starts off unprotected. 

All dates, including the current date, display a lock icon in the toolbar that indicates the protection state: locked (protected) or unlocked (unprotected). The user can tap the icon to toggle protection. Moving to another date always resets to the default protection state for that date; a manual override therefore lasts until the next date change, surviving the app being killed and restarted in the meantime. The 10-minute automatic reset (above) resets protection only when it actually moves the selection to a different day: an absence that ends on the same day it began changes nothing the user can see. When the icon is showing "protected", the text field components on the page are set to read only (not disabled). This allows the user to select and copy text out of them freely, but not edit them without unprotecting first.

> **Note on "killed and restarted" (added September 2026):** the phrase above is ambiguous and we have
> deliberately left it rather than sharpening it. It most naturally reads as *Android kills the
> process and the app comes back reincarnated*, but it could also be read as covering the user
> swiping the task away from the overview screen. We are **not** using this sentence to pin down
> swipe-away behaviour — that is a vague area of the SPEC we have chosen not to resolve, and the
> implementation may deviate from either reading. Current behaviour: a swipe-away starts a fresh
> session (today, default protection, home screen), because the background timestamp lives in a
> `SavedStateHandle` that is discarded with the task; a manual override set before the swipe does
> not survive it. That is a chosen behaviour, not a SPEC requirement, and may change.

### Recovery

The application provides a local history of recent entry text to help recover from accidental edits.

History is recorded automatically as the user edits; snapshots are kept for a configurable number of days (default 7). The history is accessed from the home screen overflow menu and shows entries for the currently selected date.

History is stored separately from the main database, and is not included in the application's *own*
backup (see "Backup and restore" below). It *is* included in Android's automatic cloud backup and
device-to-device transfer, because the manifest opts in with `allowBackup` and the auto-backup rule
files deliberately include and exclude nothing. That is a conscious choice for now: the OS's
facilities are opt-in and the user controls them, and staying out of the way avoids a class of bug
where a change to these rules silently loses or duplicates data. It does mean "not included in
backups" should be read as "not included in the app's backup", and that a user relying on Google
cloud backup alone is relying on behaviour nobody has tested against this app. History is retained
as a safety net even after a database restore.

History is noisy by design: it attempts to capture everything rather than decide what is
significant, so it is better to be noisy than over-compressed and missing the lost edit. It
is not intended to be a readable permanent changelog; a user who has not made an accidental
edit should not be looking at the history at all. It may be filtered for readability, and
the current filter is a display-time prefix collapse: when snapshots are rendered in
chronological order, any snapshot whose text is an exact initial substring of the immediately
following snapshot's text is hidden. This collapses typing bursts to the final, most
complete version while preserving any snapshot that diverged. All rows remain in the
database; the filter is applied at render time only.

Snapshots are recorded with leading and trailing whitespace stripped. Whitespace at
either end of an entry is an intermediate state of typing rather than something the user
meant to write, and keeping it would make "30 " and "30m" two unrelated snapshots - two
rows the collapse above cannot relate to each other - where they should be one entry
caught mid-edit. Whitespace within an entry is significant and is always preserved.

Pruning happens on every 10-minute session reset, whatever the retention setting: rows older than
the configured number of days are deleted at that point, so the retention setting is an upper bound
rather than something the app waits around to enforce. Doing it here rather than on a timer means
a 0-day setting behaves as "prune on reset", severely limiting the history without discarding it
every time the app is backgrounded, even for something as short as an incoming phone call.

The history screen shows entries for the currently selected date as read-only, copyable text
with timestamps, newest first. A category filter (all enabled categories, or a single one)
and an "include deleted categories" toggle are available; both are persisted and reset to
defaults after a 10-minute background. There is no in-app "restore" action; recovery is
performed by the user through copy and paste.

# Visual design

The application follows Material Design 3.

It should feel like a high-quality tool rather than a fashionable or attention-seeking application.

Animation should exist only where it improves usability or understanding.

Sibling screens slide in from the right over the visually static outgoing screen and slide away to the right when dismissed.

The visual metaphor is that each screen exists on a card. New screens are placed on top of the stack and dismissed by removing the top card, revealing the previous screen beneath.

Full-screen modal flows use the same metaphor but slide vertically from the bottom.

The application supports Material You colours where available and respects the system dark mode preference.

---

# Date and time

The database is organised around logical dates rather than timestamps.

The exact time at which a user edits a note is generally unimportant.

If modification timestamps are stored they should normally use UTC.

Time zones are therefore of relatively little significance to the application's data model.

---

# Internationalisation

The primary language is US English.

British English overrides should be provided where wording differs.

Spanish translations are provided. Additional translations are expected once the application stabilises, and should be treated as a mechanical exercise: the source strings are already complete in US English.

---

# Privacy

Dayfile is privacy-first.

The application operates entirely offline.

It requires no network access, and declares no network permission — there is nothing in it that could phone home.

One caveat to "entirely offline": Android's automatic cloud backup and device-to-device transfer will copy the app's databases and settings off the device if the user has those OS facilities switched on. That is the user's choice rather than the app's, and the app neither requests nor blocks it, but it does mean data can leave the device by a route the application is not involved in. See "Recovery" for what this covers in practice.

The primary data store is a local SQLite database accessed through Room.

The application deliberately avoids cloud services or online accounts.

The application is not designed for users with extremely high threat models. In particular, data may remain present in the internal SQLite database files after being notionally deleted and recoverable by powerful adversaries. No attempt is made to protect internal state beyond the standard Android mechanisms. No attempt is made to aggressively scrub expired internal state.

---

# Data portability

The application provides two ways to get data out, chosen to complement each other.

## Backup and restore

Backup copies the application's main database to a user-selected location through the Android
Storage Access Framework; restore replaces that main database from such a copy. Backup and
restore are separate actions, each confirmed before anything happens, and each reports its
outcome.

Restore is destructive for the main category and entry data: it replaces the entire main
database. The user is warned twice before a restore proceeds, and after a successful restore
the application restarts. A manual backup contains categories and entries only. It does not
contain history, settings, selected date/protection, or other app-state DataStore values;
manual restore leaves those existing local values in place, so it is not a complete portable
app backup. The backup is a faithful copy of the main database, but its format is not intended
to be read or edited by hand and is not a public contract.

## CSV export

Export writes the user's entries to a CSV file at a user-selected location. It is a convenience
view of the data, not a backup: it contains entries only, with no settings and no history. Each
row carries its category as a column, so categories are present in the file but are not exported
as a separate section.

The CSV uses fixed English headers `Date,Category,Category Order,Entry`. Rows are sorted by
date ascending, then by category ordering ascending. Blank entries are excluded before export.
The file is written as UTF-8; a user-configurable setting (default off) controls whether a
UTF-8 BOM is written at the start of the file, to support opening the file in applications
that expect one.

Whether a row appears in an export depends on two things: the entry's text must be non-blank,
and the entry's category must be among the selected categories. By default only enabled
categories are exported; the export bottom sheet offers an "Include disabled categories"
option. Disabled categories are never exported unless that option is switched on.

---

# Platform requirements

Dayfile is intended to be released as open source under the MIT licence.

It should be suitable for distribution through repositories such as F-Droid.

Only open-source dependencies compatible with this licensing model should be used.

Open-source Android distributions such as LineageOS and GrapheneOS are first-class supported platforms rather than secondary considerations.

The application should also function correctly on stock Android.

The minimum supported Android version is Android 11 (API 30).

This minimum version is chosen primarily because SQLite support for `VACUUM INTO` provides a safe and reliable mechanism for implementing database backup.

## Reproducible builds

The application should build reproducibly: building from a given source commit twice should produce
the same output.

The reason is the signing key rather than any particular distribution channel. A released build is
signed with the author's personal key, and Android does not allow an installed app to be updated by
a version signed with a different key, so that key cannot be rotated without every user
reinstalling. No second party can therefore vouch for a released binary, and reproducibility is the
only thing tying it to source anyone else can read.

Adopting it is also only cheap once, which is to say before there is a first public release. After
that it is settled by circumstance rather than by choice.

---

# Quality goals

The application should:

* start quickly
* remain responsive even on older hardware
* avoid unnecessary visual distraction
* behave predictably
* preserve user data
* integrate naturally with Android conventions

Supporting older devices is desirable. The application has modest resource requirements and should remain lightweight.

---

# Database upgrades

Version 0.1 is the first public release. It ships both databases at Room schema version 1, with no
migrations, and it has no established body of production data behind it yet.

From that release onwards, though, real user data exists, and preserving it is a primary engineering
requirement. Adding a migration is therefore a deliberate decision rather than a free change, and the
first one added should also settle the open question in `docs/ROADMAP.md` about keeping the backup
file format compatible across versions. Schema changes are expected to remain rare; when one happens
it comes with a Room migration unless the maintainer explicitly decides to ship a destructive change.

A migration is only ever needed to carry data between two *released* builds. The next release needs at
most one version bump and one migration however much the schema is revised while it is being built;
`AI_GUIDELINES.md` spells that out in full, since the obvious mistake is writing a migration for
every intermediate version that only ever existed on a development machine.
