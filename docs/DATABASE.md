# docs/DATABASE.md

# Database Design

## Purpose

This document explains the application's database schemas and the reasoning behind them.

It focuses on design decisions rather than implementation details.

There are two databases, not one. `MainDatabase` holds the user's actual notes — the categories and
the entries — and is the only one that is ever backed up or restored. `HistoryDatabase` holds the
edit history that powers accidental-edit recovery, which is disposable by design and deliberately
kept apart. Both are covered below; where the split matters (deletion, restore, pruning) the reason
is spelled out at the point it comes up.

---

# Design goals

The database should:

* represent the application's concepts naturally
* remain easy to understand
* support future evolution
* preserve user data reliably
* avoid unnecessary complexity

---

# Core entities

## Category

A category represents one area of a user's life.

The application is pre-populated with the following default categories:

* Money
* Diet
* Exercise
* Miscellaneous

Each category contains:

* `id` – integer primary key, auto-generated
* `name` – text, unique, the display name shown to the user
* `ordering` – integer, used to sort categories on the home screen
* `enabled` – boolean, whether the category appears on the home screen
* `auto_correct` – boolean, hint to the IME whether to offer autocorrection in this category's text field (default true)
* `capitalization` – text, one of `None`, `Sentences`, `Words`, `Characters`; hint to the IME for auto-capitalization behaviour (default `None`)

Ordering values need not be consecutive.

Disabled categories remain in the database.

## Entry

An entry represents the text for one category on one logical day.

Each entry consists of:

* `id` – integer primary key, auto-generated
* `categoryId` – integer, foreign key referencing `category.id`
* `date` – text, the logical day stored as a `YYYY-MM-DD` string
* `text` – text, unrestricted user input

A uniqueness constraint is applied to the pair (`categoryId`, `date`) so that there is exactly one entry per category per logical day.

The application intentionally stores plain text rather than structured information.

Deleting a category cascades to its entries (`ON DELETE CASCADE` on `entry.category_id`). The
alternative — orphaned rows with no category to attribute them to — would only be reachable if the
cascade were disabled, and nothing in the application benefits from an entry whose category does not
exist.

---

# The history database

A separate `HistoryDatabase` records a snapshot of an entry's text each time the user edits it, so
that an accidental over-type can be recovered by copying an older version back.

It contains two tables:

* `history_entry` — `id` (auto-generated), `categoryId`, `date` (a `YYYY-MM-DD` logical day),
  `text`, and `savedAt` (a UTC epoch-millis timestamp of *when the change was made*).
* `history_category` — a `categoryId` to `name` map. This exists so that history belonging to a
  category that has since been **deleted** can still be shown under that category's final name,
  which the main database can no longer supply.

There is deliberately **no foreign key** from `history_entry.categoryId` to `history_category.id`.
The main database is authoritative for every category that has not been deleted, and the two
databases cannot share a transaction, so a referential constraint here would turn an ordinary
interruption into a write failure: add a category, be killed before the history database hears
about it, then edit an entry in that category, and the insert would be rejected for referencing a
row that does not exist. Consistency is obtained instead by ordering operations carefully across
both databases at deletion time, and by tolerating orphans on read.

## Why it is separate

Three reasons, in increasing order of how much they matter:

1. **Deletion.** The main database's rows are the user's content and are what a backup protects.
   History is a transient safety net with its own retention setting, and tying the two lifetimes
   together would mean either deleting content along with history or preserving history along with
   content.
2. **Restore.** Backup and restore copy `main.db` and nothing else. Restoring onto a device that
   already has history leaves that history in place, which is deliberate: the user manual describes
   it as a second chance if they restore the wrong backup, at the cost of some rows referring to
   category IDs that no longer exist. Those rows are dropped when they are read rather than being
   an error.
3. **Cost.** Pruning old history is a bulk delete on a database nobody reads until they need to.
   Keeping it separate means that delete cannot touch the user's notes at all, even by mistake.

Both databases are at Room schema version 1, and their exported schemas are under
`app/schemas/app.zornslemma.dayfile.data.MainDatabase/` and `.../HistoryDatabase/`.

The `history_category` map is kept in step with the main database on a best-effort basis while a
category is active, and is written *before* the main row is deleted so the name is always available
afterwards. `CategoryRepository` documents the ordering constraints in detail.

## Retention

`history_entry` rows are pruned by timestamp, not by the date of the entry: an edit made today to an
entry from 1903 is retained for the configured number of days from today. Pruning happens when a
10-minute background reset occurs rather than on a timer, so with the retention setting at 0 the
effective behaviour is "prune on reset" rather than "prune immediately".

---

# Logical days

The application groups information into logical days.

A configurable "day starts at" preference determines which logical day an edit belongs to.

This reflects how people naturally think about days rather than rigid calendar boundaries.

Logical days are stored as `YYYY-MM-DD` strings representing the calendar date on which the logical day begins. For example, if the day starts at 4:00 AM, an entry made at 00:30 on 5 July belongs to the logical day `2026-07-04`. This format is human-readable and sorts correctly under lexical comparison.

---

# Deliberate limitations

The database intentionally does **not** attempt to understand user-entered text.

For example, it does not identify:

* foods
* exercises
* monetary values
* tags
* locations

The application deliberately prioritises unrestricted note-taking over structured analysis.

---

# Future evolution

The schema is expected to keep evolving, but no longer freely: version 0.1 is the first public
release, so real user data exists from here on and preserving it is a primary design constraint.

Both databases are currently at Room schema version 1 with no migrations, and the exported schemas
under `app/schemas/` are the record of that. Adding a migration is a deliberate decision to be
discussed rather than a routine part of a schema change; see `docs/ROADMAP.md` for the open
question about backup file format stability.

---

# Design principles

Prefer straightforward schemas.

Avoid premature normalisation.

Avoid introducing tables for hypothetical future features.

The schema should model today's application rather than tomorrow's possibilities.
