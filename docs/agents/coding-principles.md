# Shared Coding Principles

These conventions apply to both the Kotlin API (`api/`) and the Next.js web service (`web/`). Service-specific instructions supplement this document in [`api/AGENTS.md`](../../api/AGENTS.md) and [`web/AGENTS.md`](../../web/AGENTS.md).

## Simplicity and SOLID

Choose the smallest design that keeps responsibilities clear, behavior explicit, and future changes local. When a class, module, or file is already doing too much, refactor it before adding another responsibility.

Apply SOLID principles in both services:

- **Single Responsibility:** give each class and module one cohesive reason to change.
- **Open/Closed and Dependency Inversion:** isolate real provider, storage, framework, and external-service boundaries so implementations can change without spreading details through the application.
- **Liskov Substitution:** keep implementations faithful to the contracts their consumers rely on.
- **Interface Segregation:** expose focused contracts shaped by their consumers.

Prefer composition and direct, understandable code. Add an interface or abstraction when it represents a real boundary or variation point, not as boilerplate around every class or function.

## Types and files

Default to one named production type per source file. When introducing a class, data class, enum, interface, sealed type, object, or other named domain type, give it a focused file named for that type and place it with its domain or feature. When a file or class has multiple responsibilities, prefer refactoring those responsibilities into cohesive files and types instead of continuing to grow it.

Keep multiple declarations together only when they form one tightly coupled unit (for example, a small private helper inseparable from its owner). Treat that as a narrow exception; do not use it to accumulate unrelated or independently meaningful types. Avoid broad, unrelated file moves when a task does not benefit from them.

## Guard clauses and control flow

Use guard clauses in both services: validate preconditions and handle invalid, unavailable, or terminal cases at the start of a function, then keep the normal path shallow and easy to follow. Validate before side effects such as persistence, queue publication, or provider calls. Prefer early returns or explicit domain errors over deeply nested conditionals. Do not hide invalid state with silent defaults or catch-and-ignore behavior; preserve expected domain outcomes as explicit outcomes rather than misclassifying them as failures.
