COMPOSE = docker compose -f infra/docker-compose.yml
MISE = mise exec --

.PHONY: dev infra-up migrate migrate\:ls migrate\:revert verify-db infra-down clean test test-api test-web lint-web typecheck-web build-web validate

dev: infra-up migrate
	$(COMPOSE) up -d --build api worker web

infra-up:
	$(COMPOSE) up -d postgres redis minio

migrate:
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; $(COMPOSE) --profile migration run --rm sqitch deploy "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

# Print Sqitch event history with full change IDs, without opening a pager.
migrate\:ls:
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; $(COMPOSE) --profile migration run --rm sqitch --no-pager log --format=oneline --abbrev 40 "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

# Revert CHANGE itself and all later changes; Sqitch prompts before execution.
migrate\:revert:
	@set -eu; change_id='$(CHANGE)'; \
	if [ "$${#change_id}" -ne 40 ] || ! printf '%s\n' "$$change_id" | grep -Eq '^[[:xdigit:]]{40}$$'; then \
		echo 'Usage: make migrate:revert CHANGE=<40-character Sqitch change ID>' >&2; exit 2; \
	fi; \
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; \
	echo "Reverting Sqitch change $$change_id and every later change; confirm at the Sqitch prompt."; \
	$(COMPOSE) --profile migration run --rm sqitch revert --to-change "$${change_id}^" "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

verify-db:
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; $(COMPOSE) --profile migration run --rm sqitch verify "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

infra-down:
	$(COMPOSE) down

# Destructive: removes all local documents, runs, queue state, and stored objects.
clean:
	$(COMPOSE) down --volumes --remove-orphans

test: test-api test-web lint-web typecheck-web build-web

test-api:
	cd api && $(MISE) ./gradlew test

test-web:
	cd web && $(MISE) pnpm test

lint-web:
	cd web && $(MISE) pnpm run lint

typecheck-web:
	cd web && $(MISE) pnpm run typecheck

build-web:
	cd web && $(MISE) pnpm run build

validate: test
