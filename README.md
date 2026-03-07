# LCMM Documentation Draft

This repository is a working documentation space for LCMM: a modular monolith style built around explicit module boundaries, message-based cooperation, and application assembly that stays readable under growth.

The point of this repository is not only to describe ideas, but to make them concrete enough that a developer can open the docs, inspect a runnable application, and understand how the pieces fit together without guessing. The emphasis is on practical structure: how modules communicate, how the application is assembled, how responsibilities stay separated, and how the system remains understandable as it evolves.

What is here:
- documentation drafts in `docs/`;
- runnable examples in the repository, including a compact booking sample and a larger reference-style application under active development.

**Minimal developer setup**
- `Java 25` (required for virtual threads support)
- Installed `Clojure` CLI (`clj`)
- Installed `Babashka` (`bb`)

**Where to start**

Start with the root ideas in `docs/`, then follow links from there depending on the question you are trying to answer. The documentation is meant to be read as a connected set of working notes, not as a frozen specification index.

If you want to see code instead of theory first, open one of the runnable examples and read it together with the corresponding documents. That is usually the fastest way to understand what LCMM is trying to preserve: explicit boundaries, predictable composition, and a system that can grow without turning into a tangle of hidden dependencies.
