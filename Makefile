COMPOSE = docker compose -f infra/docker-compose.yml
MISE = mise exec --

.PHONY: dev infra-up migrate verify-db infra-down clean test test-api lint-web typecheck-web build-web validate

dev: infra-up migrate
	$(COMPOSE) up -d --build api worker web

infra-up:
	$(COMPOSE) up -d postgres redis minio

migrate:
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; $(COMPOSE) --profile migration run --rm sqitch deploy "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

verify-db:
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; $(COMPOSE) --profile migration run --rm sqitch verify "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

infra-down:
	$(COMPOSE) down

# Destructive: removes all local documents, runs, queue state, and stored objects.
clean:
	$(COMPOSE) down --volumes --remove-orphans

test: test-api lint-web typecheck-web build-web

test-api:
	cd api && $(MISE) ./gradlew test

lint-web:
	cd web && $(MISE) npm run lint

typecheck-web:
	cd web && $(MISE) npm run typecheck

build-web:
	cd web && $(MISE) npm run build

validate: test
