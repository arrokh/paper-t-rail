# Issue tracker: GitHub

Issues and specs for this repo live in GitHub Issues for `arrokh/paper-t-rail`. Use the `gh` CLI.

## Conventions

- Create: `gh issue create`
- Read: `gh issue view <number> --comments`
- List: `gh issue list` with appropriate state/label filters
- Comment: `gh issue comment <number> --body "..."`
- Apply/remove labels: `gh issue edit <number> --add-label/--remove-label "..."`
- Block dependencies: use GitHub's native issue dependencies (`gh api --method POST repos/<owner>/<repo>/issues/<blocked>/dependencies/blocked_by -F issue_id=<blocker-database-id>`). Use a `Blocked by` body list only if native dependencies are unavailable.

Infer the repository from `git remote -v`. PRs as a request surface: **no**.

When a skill says “publish to the issue tracker,” create a GitHub issue. When it says “fetch the relevant ticket,” use `gh issue view <number> --comments`.
