# docs/ARCHITECTURE.md

# Architecture

## Purpose

This document describes the application's overall architecture and the reasoning behind it.

It deliberately documents the architecture that currently exists, not hypothetical future architecture.

---

# Design goals

The architecture should:

* remain easy for a single experienced developer to understand
* support incremental growth
* avoid unnecessary abstraction
* follow standard Android development practices
* remain pleasant to maintain over many years

The project deliberately avoids architecture intended primarily for very large teams or extremely large applications.

---

# High-level architecture

```
Compose UI
        │
        ▼
ViewModels
        │
        ▼
Repositories
        │
        ▼
Room
        │
        ▼
SQLite
```

Each layer has a clearly defined responsibility.

Dependencies flow downwards only.

---

# UI layer

The UI is implemented using Jetpack Compose.

Composable functions should primarily describe the visual structure of the interface.

Business logic should generally not live inside composables.

State should normally originate from ViewModels.

---

# ViewModels

ViewModels expose UI state and coordinate user actions.

They translate user interactions into repository operations.

They should remain independent of Compose wherever practical.

---

# Repository layer

Repositories provide the application's view of the data model.

They hide persistence details from the rest of the application.

Repositories should not become generic frameworks.

Each repository should exist because it provides meaningful behaviour rather than because every entity "must have a repository".

---

# Persistence

The application's persistent storage is Room backed by SQLite.

The database is the source of truth.

The application is designed to function entirely offline.

---

# Design philosophy

Architecture should evolve in response to genuine requirements.

Avoid introducing abstraction before it solves a real problem.

Avoid adding layers solely because they are common in enterprise applications.

Refactoring is encouraged when it produces a simpler or clearer architecture.

---

# Current status

This document intentionally describes only architecture that currently exists.

Future architecture should be documented only after it becomes part of the application.

