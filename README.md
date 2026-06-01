<div style="text-align: center;">

# Corexie

**A high-performance, predictable lifecycle framework for Java.**

</div>

Corexie is a lightweight foundation designed for building manageable services and applications driven by a highly structured state machine.

By extending a single abstract class, Corexie eliminates application boilerplate and organizes your execution logic into precise, sequential phases running on a dedicated thread. It gives developers total control over threading behavior, real-time cycle throttling, and fallback exception handling without sacrificing simplicity.

---

## Features

* **Rigid Lifecycle Hooks:** Built-in orchestration for initialization (`onStartup`), processing cycles (`onExecute`), suspensions (`onPause`/`onResume`), pacing loops (`onIdle`), and graceful resource extraction (`onShutdown`).
* **Dual Execution Flavors:** Spin up your application asynchronously in a managed thread (`start()`) or block the caller context synchronously (`run()`).
* **Conscious Concurrency Assertions:** Built-in execution checks (`ensureOnThread()` and `ensureOffThread()`) that immediately detect hazardous, multi-threaded interactions early in development.
* **Self-Healing Exception Fallbacks:** Includes an unhandled exception interceptor (`onException`) that mitigates crashes by automatically dropping the application into a safe, structured shutdown routine if an execution step fails.

---

## License

This project uses a split-licensing strategy:

* **Core Module (`corexie-core`):** Licensed under **LGPL-3.0-only**. You can link this library (via Maven, Gradle, etc.) into proprietary applications without being required to release your application's source code. Any modifications to the library itself must be shared under the same license.
* **Examples & Snippets (`corexie-example`):** Licensed under **MIT**. All code in the `example` module and documentation snippets can be used, copied, and modified without restriction.

See the [LICENSE](LICENSE.md) file for full details and legal texts.

---

<div style="text-align: center;">

*Copyright © 2026 Nullumie*

</div>