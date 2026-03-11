# LCMM Documentation Draft

`LCMM` (`Loosely Coupled Modular Monolith`) is an architectural approach to building a modular monolith with weak coupling between modules.

In LCMM, module interaction stays explicit in two ways: asynchronous cooperation through messages and controlled synchronous reads through a read-provider registry.

This repository is a working documentation space for LCMM: a way to build a modular monolith with explicit boundaries, message-based cooperation, and application assembly that remains readable as the system grows.

The main value of LCMM is not novelty for its own sake. It is an attempt to keep a growing codebase understandable by forcing important architectural decisions into the open: who owns data, how modules talk to each other, where synchronous reads are allowed, where side effects belong, and how the whole application is assembled without hidden coupling.

This repository exists to make those ideas concrete enough to be used, questioned, and improved. The docs are not meant to be a decorative theory layer on top of code. They are meant to describe a practical style of building systems that can stay coherent under change, especially when more than one person needs to understand and evolve them from shared written rules rather than tribal knowledge.

What is here:
- documentation drafts in `docs/`;
- supporting material for runnable examples in the repository.

## Examples

This repository contains two runnable LCMM examples in `examples/`:

- [examples/booking-simple](examples/booking-simple/README.md) shows a small single-project example with a minimal reactive flow;
- [examples/booking-full](examples/booking-full/README.md) shows a fuller modular monolith assembled from an application project and several modules.

Both examples are meant to be started locally and then checked through browser-safe `GET` endpoints.

**Minimal developer setup**
- `Java 25` (required for virtual threads support)
- Installed `Clojure` CLI (`clj`)
- Installed `Babashka` (`bb`)

**Where to start**

Start with `docs/`, not by jumping randomly between files, but by following the intended reading path. The recommended order is collected in [docs/READING_ORDER.md](docs/READING_ORDER.md).

If you are trying to understand what matters most in LCMM, focus on this first:
- modules must have clear boundaries;
- synchronous cross-module reads must stay explicit;
- side effects should move through messages instead of direct calls;
- application assembly must be understandable as a whole, not hidden in scattered glue code.

That is the thread running through the repository. The exact set of examples and supporting documents may still change, but this is the core idea the repository is trying to preserve. 
