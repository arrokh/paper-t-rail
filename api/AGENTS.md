# API Service Instructions

For cross-service simplicity, maintainability, SOLID, guard-clause, and type/file conventions, follow [Shared Coding Principles](../docs/agents/coding-principles.md).

## Kotlin imports

Kotlin imports are file-scoped: keep them in the import block immediately below the `package` declaration. Never write fully qualified class or type names inline when an ordinary import is appropriate; use an explicit import alias only to resolve a genuine name collision.
