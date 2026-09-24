# Use shadcn/ui as the web UI foundation

**Status:** Accepted

The web app adopts Tailwind CSS v4 and shadcn/ui's Base UI component set as its default interface system. We chose locally generated, project-owned component source over a closed runtime component package so Paper T-Rail can keep interaction behavior, accessibility, and its editorial paper-and-forest theme reviewable and adaptable in-repository. The visual tokens, composition rules, accessible interaction patterns, and contribution guidance are defined in [the Web UI Design System](../ui-design-system.md) and enforced for `web/` changes by `web/AGENTS.md`.
