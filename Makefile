COMPOSE = docker compose --env-file "$$(if [ -f .env ]; then printf .env; else printf .env.example; fi)" -f infra/docker-compose.yml
MISE = mise exec --
LAYA_EVALUATION_SOURCE_PATHS = api infra/laya infra/docker-compose.yml Makefile .dockerignore .gitignore \
	docs/benchmarks/laya-human-calibration-protocol-v1.md docs/laya-evaluation.md docs/agents/provider-matrix.md
# Keep command-line CHANGE data out of shell source text in the revert target.
export CHANGE

.PHONY: dev dev-laya dev-app homepage-dev homepage-build infra-up migrate migrate\:ls migrate\:revert verify-db infra-down clean test test-api test-laya test-web lint-web typecheck-web build-web calibrate benchmark-processing laya-up laya-model-download benchmark-laya laya-evaluation-fingerprint laya-evaluate validate

DEV_SELECTABLE_SERVICES := api worker web homepage laya
DEV_APP_SERVICES := api worker web homepage
# Treat service names after `dev` as selectors and reject unknown names before prerequisites run.
DEV_REQUESTED_SERVICES := $(if $(filter dev,$(MAKECMDGOALS)),$(filter-out dev,$(MAKECMDGOALS)))
DEV_INVALID_SERVICES := $(filter-out $(DEV_SELECTABLE_SERVICES),$(DEV_REQUESTED_SERVICES))
DEV_LAYA_SELECTED := $(if $(filter laya,$(DEV_REQUESTED_SERVICES)),true,false)

ifneq ($(filter dev,$(MAKECMDGOALS)),)
ifneq ($(strip $(DEV_INVALID_SERVICES)),)
$(error Unsupported service(s) for `make dev`: $(DEV_INVALID_SERVICES). Choose from: $(DEV_SELECTABLE_SERVICES))
endif

ifeq ($(strip $(DEV_REQUESTED_SERVICES)),)
DEV_BUILD_SERVICES := $(DEV_APP_SERVICES)
DEV_BUILD_LAYA := true
DEV_PREREQUISITES := dev-laya
else
DEV_BUILD_SERVICES := $(filter $(DEV_APP_SERVICES),$(DEV_REQUESTED_SERVICES))
DEV_BUILD_LAYA := $(DEV_LAYA_SELECTED)
ifneq ($(strip $(DEV_REQUESTED_SERVICES)),)
DEV_PREREQUISITES := dev-laya
endif
endif

.PHONY: $(DEV_REQUESTED_SERVICES)
$(DEV_REQUESTED_SERVICES):
	@:
else
DEV_BUILD_LAYA := true
.PHONY: api worker web homepage laya
api worker web homepage laya:
	@echo 'Use `make dev $@` to start or rebuild this service.' >&2
	@exit 2
endif

dev: $(DEV_PREREQUISITES)
ifneq ($(strip $(DEV_BUILD_SERVICES)),)
	$(COMPOSE) build $(DEV_BUILD_SERVICES)
	$(COMPOSE) up -d $(DEV_BUILD_SERVICES)
endif

dev-app: dev-laya
	$(COMPOSE) build api worker web homepage
	$(COMPOSE) up -d api worker web homepage

homepage-dev:
	cd homepage && $(MISE) pnpm dev

homepage-build:
	cd homepage && $(MISE) pnpm build

dev-laya: migrate
	@set -eu; \
	laya_enabled=$$(python3 scripts/prepare_local_laya_env.py --enabled-only); \
	if [ "$$laya_enabled" = true ]; then \
		echo 'Preparing and starting the local Laya sidecar (first run downloads the pinned model).'; \
		$(COMPOSE) --profile laya-evaluation run --rm laya-model-download; \
		if [ "$(DEV_BUILD_LAYA)" = true ]; then $(COMPOSE) --profile laya-evaluation build laya; fi; \
		$(COMPOSE) --profile laya-evaluation up -d --wait laya; \
	elif [ "$(DEV_LAYA_SELECTED)" = true ]; then \
		echo 'Cannot select laya because LAYA_ENABLED=false.' >&2; exit 2; \
	else \
		echo 'LAYA_ENABLED=false; skipping the optional Laya sidecar.'; \
	fi

infra-up:
	$(COMPOSE) up -d postgres redis minio

migrate: infra-up
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; $(COMPOSE) --profile migration run --rm sqitch deploy "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

# Print each Sqitch event oldest-first as ID, local timestamp/action, and title.
migrate\:ls:
	@set -eu; \
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; \
	local_timezone="$${TZ:-$$(readlink /etc/localtime 2>/dev/null | sed -n 's#^.*/zoneinfo/##p')}"; \
	if [ -z "$$local_timezone" ]; then echo 'Could not determine local timezone; set TZ to an IANA timezone.' >&2; exit 1; fi; \
	timezone_label=$$(TZ="$$local_timezone" date +%Z); \
	printf 'Sqitch timestamps use local timezone %s (%s), oldest first.\n' "$$local_timezone" "$$timezone_label"; \
	$(COMPOSE) --profile migration run --rm -e TZ="$$local_timezone" sqitch --no-pager log --reverse --format='format:%H%v    %{date:strftime:%Y-%m-%d %H:%M:%S %Z %z}c - %e %o:%n%v    > %s' --abbrev 40 "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

# Revert CHANGE itself and all later changes; Sqitch prompts before execution.
migrate\:revert:
	@set -eu; change_id="$${CHANGE:-}"; \
	if [ "$${#change_id}" -ne 40 ] || ! printf '%s\n' "$$change_id" | grep -Eq '^[[:xdigit:]]{40}$$'; then \
		echo 'Usage: make migrate:revert CHANGE=<40-character Sqitch change ID>' >&2; exit 2; \
	fi; \
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; \
	echo "Reverting Sqitch change $$change_id and every later change; confirm at the Sqitch prompt."; \
	$(COMPOSE) --profile migration run --rm sqitch revert --to-change "$${change_id}^" "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

verify-db:
	set -a; if [ -f .env ]; then . ./.env; fi; set +a; $(COMPOSE) --profile migration run --rm sqitch verify "db:pg://$${POSTGRES_USER:-papertrail}:$${POSTGRES_PASSWORD:-local-only-change-me}@postgres:5432/$${POSTGRES_DB:-papertrail}"

infra-down:
	$(COMPOSE) --profile laya-evaluation down

# Destructive: removes all local documents, runs, queue state, and stored objects.
clean:
	$(COMPOSE) --profile laya-evaluation down --volumes --remove-orphans

test: test-api test-laya test-web lint-web typecheck-web build-web

test-api:
	cd api && $(MISE) ./gradlew test

test-laya:
	python3 -m unittest discover -s infra/laya -p 'test_*.py' -v

laya-up: dev-laya
	@set -eu; \
	laya_enabled=$$(python3 scripts/prepare_local_laya_env.py --enabled-only); \
	if [ "$$laya_enabled" != true ]; then \
		echo 'Set LAYA_ENABLED=true in .env to start the optional Laya sidecar.' >&2; exit 1; \
	fi
	$(COMPOSE) up -d --build --force-recreate api worker

laya-model-download:
	$(COMPOSE) --profile laya-evaluation run --rm laya-model-download

# Compute dataset and held-out hashes locally before a human freezes the plan. No inference occurs.
laya-evaluation-fingerprint:
	@set -eu; \
	if [ -z "$${DATA_DIR:-}" ]; then echo 'Usage: make laya-evaluation-fingerprint DATA_DIR=<private-dir>'; exit 2; fi; \
	evaluation_dir=$$(cd "$$DATA_DIR" && pwd -P); \
	repo_root=$$(pwd -P); \
	case "$$evaluation_dir/" in "$$repo_root"/.laya-evaluation/*) ;; "$$repo_root"/*) echo 'Keep human data outside the checkout or under .laya-evaluation/.' >&2; exit 2 ;; esac; \
	test -f "$$evaluation_dir/dataset.json" || { echo 'DATA_DIR must contain dataset.json.' >&2; exit 2; }; \
	if ! git diff --quiet -- $(LAYA_EVALUATION_SOURCE_PATHS) || [ -n "$$(git status --porcelain -- $(LAYA_EVALUATION_SOURCE_PATHS))" ]; then \
		echo 'Commit the evaluation code, boundary configuration, and protocol before freezing a dataset fingerprint.' >&2; exit 2; fi; \
	application_revision=$$(git rev-parse --verify HEAD); \
	export PAPER_TRAIL_APPLICATION_REVISION="$$application_revision" LAYA_EVALUATION_DIR="$$evaluation_dir" \
		PAPER_TRAIL_EVALUATOR_UID="$$(id -u)" PAPER_TRAIL_EVALUATOR_GID="$$(id -g)"; \
	$(COMPOSE) --profile laya-evaluation build laya-evaluator; \
	$(COMPOSE) --profile laya-evaluation run --rm --no-deps --entrypoint ./gradlew laya-evaluator \
		--offline fingerprintLaya -PlayaDataset=/evaluation/dataset.json

# Evaluator input/output stays in a caller-supplied local directory ignored by Git.
# The Laya sidecar must already be running; the one-shot evaluator publishes no host port.
laya-evaluate:
	@set -eu; \
	if [ -z "$${DATA_DIR:-}" ]; then echo 'Usage: make laya-evaluate DATA_DIR=<private-dir> SPLIT=calibration|held-out'; exit 2; fi; \
	case "$${SPLIT:-}" in calibration|held-out) split="$$SPLIT" ;; *) echo 'SPLIT must be calibration or held-out.' >&2; exit 2 ;; esac; \
	evaluation_dir=$$(cd "$$DATA_DIR" && pwd -P); \
	repo_root=$$(pwd -P); \
	case "$$evaluation_dir/" in "$$repo_root"/.laya-evaluation/*) ;; "$$repo_root"/*) echo 'Keep human data outside the checkout or under .laya-evaluation/.' >&2; exit 2 ;; esac; \
	test -f "$$evaluation_dir/dataset.json" || { echo 'DATA_DIR must contain dataset.json.' >&2; exit 2; }; \
	if [ "$$split" = held-out ] && [ ! -f "$$evaluation_dir/plan.json" ]; then echo 'Held-out evaluation requires DATA_DIR/plan.json.' >&2; exit 2; fi; \
	if ! git diff --quiet -- $(LAYA_EVALUATION_SOURCE_PATHS) || [ -n "$$(git status --porcelain -- $(LAYA_EVALUATION_SOURCE_PATHS))" ]; then \
		echo 'Commit the evaluation code, boundary configuration, and protocol before running a pre-registered evaluation.' >&2; exit 2; fi; \
	application_revision=$$(git rev-parse --verify HEAD); \
	export PAPER_TRAIL_APPLICATION_REVISION="$$application_revision" LAYA_EVALUATION_DIR="$$evaluation_dir" \
		PAPER_TRAIL_EVALUATOR_UID="$$(id -u)" PAPER_TRAIL_EVALUATOR_GID="$$(id -g)"; \
	$(COMPOSE) --profile laya-evaluation build laya-evaluator; \
	plan_arg=''; if [ -f "$$evaluation_dir/plan.json" ]; then plan_arg='-PlayaPlan=/evaluation/plan.json'; fi; \
	$(COMPOSE) --profile laya-evaluation run --rm --no-deps laya-evaluator \
		-PlayaDataset=/evaluation/dataset.json \
		-PlayaSplit="$$split" \
		-PlayaResults="/evaluation/$$split-results.json" \
		-PlayaReport="/evaluation/$$split-report.md" \
		$$plan_arg

benchmark-laya:
	$(COMPOSE) --profile laya-evaluation exec -T laya python /app/benchmark_runtime.py

test-web:
	cd web && $(MISE) pnpm test

lint-web:
	cd web && $(MISE) pnpm run lint

typecheck-web:
	cd web && $(MISE) pnpm run typecheck

build-web:
	cd web && $(MISE) pnpm run build

# Optional diagnostic calibration harness; not required by the standard test/validate targets.
calibrate:
	cd api && $(MISE) ./gradlew calibrate

benchmark-processing:
	$(MISE) python3 scripts/benchmark-processing-caps.py

validate: test
