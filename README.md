<div style="text-align: center;">

# Corexie

**A clean foundation for predictable Java applications.**

</div>

Corexie is a lightweight foundation designed for building Java applications with a clear, predictable lifecycle.

By providing a single abstract class, Corexie organizes your application into a logical flow: **startup**, **execution**, and **shutdown**. Everything runs on a dedicated thread with explicit state management, making your application's behavior easier to reason about and debug.

Corexie is built for developers who need control and consistency without reinventing application boilerplate every time.

---

<div style="text-align: center;">

## License

</div>

This project uses a split-licensing strategy:

- **Core Module (`corexie-core`):** Licensed under **LGPL-3.0-only**. You can link this library (via Maven, Gradle, etc.) into proprietary applications without being required to release your application's source code. Any modifications to the library itself must be shared under the same license.
- **Examples & Snippets (`corexie-example`):** Licensed under **MIT**. All code in the `example` module and documentation snippets can be used, copied, and modified without restriction.

See the [LICENSE](LICENSE.md) file for the full details and legal texts.

---

<div style="text-align: center;">

**Copyright © 2026 Nullumie**

</div>