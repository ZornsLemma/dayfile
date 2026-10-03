# Engineering Philosophy

## Purpose

This document describes the engineering values that guide the development of Dayfile.

Unlike `DEVELOPMENT.md`, which contains conventions and practical guidance, this document explains the principles behind those conventions.

When making design decisions that are not explicitly covered elsewhere, these principles should guide the direction of the project.

---

# A long-lived project

Dayfile is intended to become a mature, stable application.

The objective is not continual expansion, but to produce a tool that performs its intended purpose well and remains pleasant to maintain over many years.

The codebase should therefore optimise for long-term maintainability rather than rapid short-term development.

---

# Code quality is part of the product

The internal quality of the code is considered part of the product.

Users may never see the implementation directly, but they benefit from software that is easier to maintain, easier to reason about and less likely to contain defects.

Engineering quality should therefore be valued for its own sake as well as for its practical consequences.

---

# Appropriate architecture

Architecture should reflect the actual scale and needs of the project.

The goal is neither to minimise architecture nor to imitate large enterprise systems.

Abstraction is valuable when it solves real problems.

Abstraction created solely for hypothetical future requirements usually is not.

The preferred architecture is the simplest one that clearly expresses the design of the application.

---

# Clarity over cleverness

The project values code that is straightforward to understand.

This does **not** mean avoiding modern Kotlin or Android features.

Rather, it means using language features because they improve clarity, correctness or maintainability.

Code should not be made artificially simplistic.

Equally, cleverness should never become an objective in its own right.

---

# Evolution over prediction

The project is expected to evolve through experience.

Early designs should not attempt to anticipate every possible future requirement.

When requirements genuinely change, the implementation should change with them.

Refactoring is a normal part of development.

---

# Documentation is an investment

Documentation exists to preserve knowledge.

Its purpose is not to describe every line of code, but to explain decisions, intentions and design rationale that would otherwise be forgotten.

Good documentation reduces the cost of future maintenance.

Documentation should evolve alongside the software.

---

# AI as a collaborator

AI is a development tool, not a substitute for engineering judgement.

AI-generated code should meet the same standards as manually written code.

The project should never become dependent upon AI in order to understand or modify its own implementation.

A competent developer should always be able to understand, review and extend the code without requiring further AI assistance.

---

# Respect for the future maintainer

Every significant design decision should consider the experience of the person maintaining the project years later.

In most cases, that person will be the current project owner.

Future maintainers should be able to understand not only what the code does, but why it was written that way.

Reducing future maintenance effort is generally more valuable than reducing today's implementation effort.

---

# Continuous improvement

The project is expected to improve over time.

Prototype implementations are acceptable while exploring a design.

Once a better understanding has been reached, those prototypes should either be refined into permanent solutions or replaced.

The objective is steady improvement rather than immediate perfection.

---

# Boring software is a compliment

Dayfile aims to be dependable rather than exciting.

The same philosophy applies to its implementation.

The best solution is often the one that is easiest to understand, easiest to maintain and least surprising to future developers.

The project values thoughtful engineering over novelty.

If, after several years, the code still feels calm, coherent and unsurprising, then it has succeeded.

