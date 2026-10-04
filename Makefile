COMPOSE = docker compose --env-file "$$(if [ -f .env ]; then printf .env; else printf .env.example; fi)" -f infra/docker-compose.yml
MISE = mise exec --
LAYA_EVALUATION_SOURCE_PATHS = api infra/laya infra/docker-compose.yml Makefile .dockerignore .gitignore \
	docs/benchmarks/laya-human-calibration-protocol-v1.md docs/laya-evaluation.md docs/agents/provider-matrix.md
# Keep command-line CHANGE data out of shell source text in the revert target.
export CHANGE

.PHONY: local dev dev-stop dev-laya dev-app homepage-dev homepage-build infra-up migrate migrate\:ls migrate\:revert verify-db infra-down clean test test-local test-api test-laya test-web lint-web typecheck-web build-web calibrate benchmark-processing laya-up laya-model-download benchmark-laya laya-evaluation-fingerprint laya-evaluate validate

DEV_SELECTABLE_SERVICES := api worker web homepage laya
DEV_STOP_SELECTABLE_SERVICES := $(DEV_SELECTABLE_SERVICES) docling
DEV_APP_SERVICES := api worker web homepage
DEV_STOP_REQUESTED_SERVICES := $(if $(filter dev-stop,$(MAKECMDGOALS)),$(filter-out dev-stop,$(MAKECMDGOALS)))
DEV_STOP_INVALID_SERVICES := $(filter-out $(DEV_STOP_SELECTABLE_SERVICES),$(DEV_STOP_REQUESTED_SERVICES))
DEV_STOP_COMPOSE_PROFILE := $(if $(filter laya,$(DEV_STOP_REQUESTED_SERVICES)),--profile laya-evaluation)
# Select container-backed services and host-run frontends after `local`.
LOCAL_SELECTABLE_SERVICES := api worker web homepage laya
LOCAL_REQUESTED_SERVICES := $(if $(filter local,$(MAKECMDGOALS)),$(filter-out local,$(MAKECMDGOALS)))
LOCAL_INVALID_SERVICES := $(filter-out $(LOCAL_SELECTABLE_SERVICES),$(LOCAL_REQUESTED_SERVICES))
LOCAL_SELECTED_SERVICES := $(if $(strip $(LOCAL_REQUESTED_SERVICES)),$(LOCAL_REQUESTED_SERVICES),api worker web homepage)
LOCAL_CONTAINER_SERVICES := $(filter api worker laya,$(LOCAL_SELECTED_SERVICES))
LOCAL_FRONTEND_SERVICES := $(filter web homepage,$(LOCAL_SELECTED_SERVICES))
LOCAL_API_PORT_ENV_FILE := $(if $(wildcard .env),.env,.env.example)
LOCAL_API_PORT ?= $(or $(API_HOST_PORT),$(shell sed -n 's/^API_HOST_PORT=//p' "$(LOCAL_API_PORT_ENV_FILE)" | head -n 1 | tr -d '"'),8080)
LOCAL_API_ORIGIN ?= $(or $(PAPER_T_RAIL_API_ORIGIN),http://127.0.0.1:$(LOCAL_API_PORT))
WEB_PORT ?= 3000
HOMEPAGE_PORT ?= 4321
# Treat service names after `dev` as selectors and reject unknown names before prerequisites run.
DEV_REQUESTED_SERVICES := $(if $(filter dev,$(MAKECMDGOALS)),$(filter-out dev,$(MAKECMDGOALS)))
DEV_INVALID_SERVICES := $(filter-out $(DEV_SELECTABLE_SERVICES),$(DEV_REQUESTED_SERVICES))
DEV_LAYA_SELECTED := $(if $(filter laya,$(DEV_REQUESTED_SERVICES)),true,false)

ifneq ($(filter local,$(MAKECMDGOALS)),)
ifneq ($(strip $(LOCAL_INVALID_SERVICES)),)
$(error Unsupported service(s) for `make local`: $(LOCAL_INVALID_SERVICES). Choose from: $(LOCAL_SELECTABLE_SERVICES))
endif
ifneq ($(filter dev,$(MAKECMDGOALS)),)
$(error Choose either `make local ...` or `make dev ...`, not both.)
endif

.PHONY: $(LOCAL_REQUESTED_SERVICES)
$(LOCAL_REQUESTED_SERVICES):
	@:
else
ifneq ($(filter dev-stop,$(MAKECMDGOALS)),)
ifeq ($(strip $(DEV_STOP_REQUESTED_SERVICES)),)
$(error Usage: make dev-stop <service...>. Choose from: $(DEV_STOP_SELECTABLE_SERVICES))
endif
ifneq ($(strip $(DEV_STOP_INVALID_SERVICES)),)
$(error Unsupported service(s) for `make dev-stop`: $(DEV_STOP_INVALID_SERVICES). Choose from: $(DEV_STOP_SELECTABLE_SERVICES))
endif

.PHONY: $(DEV_STOP_REQUESTED_SERVICES)
$(DEV_STOP_REQUESTED_SERVICES):
	@:
else
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
endif
endif

local:
	mise install
ifneq ($(strip $(LOCAL_FRONTEND_SERVICES)),)
	@set -eu; \
	for service in $(LOCAL_FRONTEND_SERVICES); do \
		echo "Checking $$service dependencies."; \
		$(MISE) pnpm --dir "$$service" install --frozen-lockfile; \
	done
endif
ifneq ($(strip $(LOCAL_CONTAINER_SERVICES)),)
	$(MAKE) dev $(LOCAL_CONTAINER_SERVICES)
endif
ifneq ($(strip $(LOCAL_FRONTEND_SERVICES)),)
	@case ' $(LOCAL_FRONTEND_SERVICES) ' in \
		*' web '*) \
			api_origin="$${PAPER_T_RAIL_API_ORIGIN:-$(LOCAL_API_ORIGIN)}"; api_origin="$${api_origin%/}"; \
			echo "Waiting for the API at $$api_origin/api/v1/health"; \
			api_ready=false; attempt=0; \
			while [ "$$attempt" -lt 60 ]; do \
				if curl --fail --silent --max-time 5 "$$api_origin/api/v1/health" >/dev/null 2>&1; then api_ready=true; break; fi; \
				attempt=$$((attempt + 1)); sleep 2; \
			done; \
			if [ "$$api_ready" != true ]; then echo "The API did not become ready at $$api_origin. Check the API service and its host port." >&2; exit 1; fi ;; \
	 esac
	@if running=$$($(COMPOSE) ps --status running -q $(LOCAL_FRONTEND_SERVICES) 2>/dev/null); then \
		if [ -n "$$running" ]; then \
			echo 'Stopping the selected Compose frontend(s) to free their local ports.'; \
			$(COMPOSE) stop $(LOCAL_FRONTEND_SERVICES); \
		fi; \
	fi
	@set -eu; \
	pids=''; \
	case ' $(LOCAL_FRONTEND_SERVICES) ' in \
		*' web '*) \
			web_listener=$$(lsof -tiTCP:"$(WEB_PORT)" -sTCP:LISTEN 2>/dev/null | head -n 1 || :); \
			if [ -n "$$web_listener" ]; then \
				web_directory=$$(lsof -a -p "$$web_listener" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' || :); \
				web_command=$$(ps -p "$$web_listener" -o command= 2>/dev/null || :); \
				case "$$web_command" in \
					*"next-server (v"*|*"next/dist/bin/next dev"*) \
						if [ "$$web_directory" != "$(CURDIR)/web" ]; then \
							echo "Port $(WEB_PORT) is serving a Next.js app outside this worktree." >&2; exit 1; \
						fi; \
						echo "Using the existing web workspace at http://127.0.0.1:$(WEB_PORT)" ;; \
					*) echo "Port $(WEB_PORT) is already in use by another process." >&2; exit 1 ;; \
				esac; \
			else \
				echo "Starting the web workspace at http://127.0.0.1:$(WEB_PORT)"; \
				PAPER_T_RAIL_API_ORIGIN="$${PAPER_T_RAIL_API_ORIGIN:-http://127.0.0.1:$(LOCAL_API_PORT)}" \
					$(MISE) pnpm --dir web exec next dev --hostname 127.0.0.1 --port "$(WEB_PORT)" & \
				pids="$$pids $$!"; \
			fi ;; \
	esac; \
	case ' $(LOCAL_FRONTEND_SERVICES) ' in \
		*' homepage '*) \
			homepage_listener=$$(lsof -tiTCP:"$(HOMEPAGE_PORT)" -sTCP:LISTEN 2>/dev/null | head -n 1 || :); \
			if [ -n "$$homepage_listener" ]; then \
				homepage_directory=$$(lsof -a -p "$$homepage_listener" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' || :); \
				homepage_command=$$(ps -p "$$homepage_listener" -o command= 2>/dev/null || :); \
				case "$$homepage_command" in \
					*"astro.mjs dev"*) \
						if [ "$$homepage_directory" != "$(CURDIR)/homepage" ]; then \
							echo "Port $(HOMEPAGE_PORT) is serving an Astro app outside this worktree." >&2; exit 1; \
						fi; \
						echo "Using the existing homepage at http://127.0.0.1:$(HOMEPAGE_PORT)" ;; \
					*) echo "Port $(HOMEPAGE_PORT) is already in use by another process." >&2; exit 1 ;; \
				esac; \
			else \
				echo "Starting the homepage at http://127.0.0.1:$(HOMEPAGE_PORT)"; \
				PUBLIC_WORKSPACE_URL="$${PUBLIC_WORKSPACE_URL:-http://127.0.0.1:$(WEB_PORT)}" \
					$(MISE) pnpm --dir homepage exec astro dev --host 127.0.0.1 --port "$(HOMEPAGE_PORT)" & \
				pids="$$pids $$!"; \
			fi ;; \
	esac; \
	trap 'for pid in $$pids; do kill "$$pid" 2>/dev/null || :; done' INT TERM EXIT; \
	wait
endif

dev-stop:
	@set -eu; \
	containers=$$($(COMPOSE) $(DEV_STOP_COMPOSE_PROFILE) ps --all -q $(DEV_STOP_REQUESTED_SERVICES)); \
	if [ -n "$$containers" ]; then \
		echo 'Stopping selected Compose service(s): $(DEV_STOP_REQUESTED_SERVICES)'; \
		$(COMPOSE) $(DEV_STOP_COMPOSE_PROFILE) stop $(DEV_STOP_REQUESTED_SERVICES); \
	else \
		echo 'No containers found for selected service(s): $(DEV_STOP_REQUESTED_SERVICES)'; \
	fi

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
	$(COMPOSE) up -d postgres redis object-storage

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

# Destructive: removes active Compose volumes; pre-migration object data is left untouched.
clean:
	$(COMPOSE) --profile laya-evaluation down --volumes --remove-orphans

test: test-local test-api test-laya test-web lint-web typecheck-web build-web

test-local:
	python3 -m unittest discover -s scripts -p 'test_*.py' -v

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
