# V1 processing caps and runtime matrix

**Measured:** 2026-09-27. **Purpose:** record the Issue #12 representative article/dissertation processing results and the runtime/provider versions used to select the initial configurable processing caps. These are observations from two local ARM64 runs, not service-level guarantees.

## Reproduce the benchmark

From the repository root, run:

```sh
make benchmark-processing
```

Prerequisites are Docker Engine with Docker Compose, `mise`/Python 3, and `curl`. The script builds the API and worker from this checkout, starts an isolated Compose project on dynamically selected host ports, deploys the schema, downloads the two source PDFs to a temporary directory, verifies their SHA-256 hashes, uploads each through the normal API, and polls until each Analysis Run is `PARSED`. It prints the measured results as JSON. It ignores the repository's `.env`, sets the documented caps and runtime/provider options explicitly, and disables Crossref, Unpaywall, and Ollama; the benchmark therefore uses the local GROBID parser and the configured recorded/local providers, without external provider calls. On exit (including failures after Compose startup), the script removes the benchmark containers, network, volumes, and its project-tagged API/worker images. Source PDFs are not added to the repository.

The script samples `docker stats` about every 0.5 seconds. Memory columns below are absolute peak container usage, including the already-warm service baseline; brief spikes between samples may be missed. CPU is the maximum sampled per-container percentage (Docker may report more than 100% when multiple cores are used). In each run the samples execute sequentially in one warm stack, so the dissertation's starting memory includes the stack already warmed by the article. Reported ranges are min–max across two complete runs on the same host, not statistical intervals; other host/Docker workloads were not controlled. `upload → PARSED` starts after the upload response; `total` starts just before the upload. `PARSED` means parsing, claim extraction/linking, and the applicable local processing reached the ready parsed state; it does not mean semantic Evidence Judgements ran.

## Measurement environment

| Component | Tested value |
| --- | --- |
| Host | MacBook Pro, Apple M4 Max (14 CPU cores), 36 GiB RAM, macOS 15.7.7 |
| Docker daemon | Docker Engine 29.4.0; Linux `aarch64`; 14 CPUs and 16,818,978,816 bytes (15.66 GiB) available to containers |
| Compose | Docker Compose 5.1.2 |
| Host tooling | mise 2026.9.6; Python 3.14.7; curl 8.22.0 |
| JVM/toolchain | OpenJDK 21.0.2+13; Gradle Wrapper 8.14.3 |
| API/worker build and runtime | [`api/Dockerfile`](../../api/Dockerfile): Gradle 8.14.3 / JDK 21 Alpine build stage; Eclipse Temurin `21.0.2_13-jre-jammy` runtime; Spring Boot 3.5.3; Kotlin 2.1.21 |
| PDF/language preflight | Apache PDFBox `3.0.5`; Optimaize language detector `0.6` |
| S3 client | MinIO Java client `8.5.17` |

## Representative results

| Sample | Source (license) | PDF bytes | Pages | Citation occurrences | Bibliography entries | Atomic Claims | Claim–Citation Pairs | Upload (s) | Upload → `PARSED` (s) | Total (s) | Peak API / worker / GROBID memory (MiB; min–max) | Peak API / worker / GROBID CPU (%; min–max) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Journal article | [Gurney et al., “Conversational technology and reactions to withheld information”](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0301382), PLOS ONE (CC BY 4.0) | 1,169,931 | 22 | 77 | 55 | 57 | 80 | 0.824–0.837 | 19.327–21.353 | 20.176–22.213 | 649.6–729.1 / 513.9–594.5 / 3,825.7–3,855.4 | 30.7–31.2 / 316.2–374.6 / 169.1–227.8 |
| Thesis/dissertation | [Danai Liodaki, *Exploring “alternatives to development”: A study on makerspaces in Greece and Germany*](https://ul.qucosa.de/id/qucosa%3A100361), Qucosa (CC BY 4.0) | 2,355,957 | 208 | 936 | 298 | 734 | 611 | 0.844–1.273 | 26.590–34.122 | 27.457–35.421 | 630.1–761.3 / 549.5–626.6 / 4,281.3–5,054.5 | 20.3–31.2 / 15.6–21.9 / 297.0–305.1 |

The source PDFs are downloaded by [`scripts/benchmark-processing-caps.py`](../../scripts/benchmark-processing-caps.py) and their SHA-256 hashes are pinned there. Results count parsed structures and inferred links, not human-verified claims or semantic judgments.

## Initial configurable caps

| Setting | Default | Benchmark context and rationale |
| --- | ---: | --- |
| `PAPER_MAX_UPLOAD_BYTES` | `52428800` bytes (50 MiB) | Preserves the existing 50 MiB limit. It is about 22 times the largest measured PDF; `PAPER_MAX_REQUEST_SIZE=51MB` leaves room for multipart overhead. |
| `PAPER_MAX_PAGES` | `500` | Preserves the existing page limit and is about 2.4 times the 208-page dissertation. |
| `PAPER_MAX_CLAIM_CITATION_PAIRS` | `5000` | New run-snapshotted cap; about 8.2 times the 611 pairs measured in the dissertation. It bounds downstream per-link work without silently dropping claims or targets. |

The 5,000-pair value is a bounded initial operating limit, not a claim that a 5,000-pair document was benchmarked or that runtime scales linearly. All three values are configurable. The worker counts the claim × persisted Citation Target links after parsing/extraction and before persisting parsed output. If the count exceeds the snapshotted cap, the run fails with the observed count and configured maximum; it does not truncate or persist a partial parsed structure. Integration coverage checks rejection above the cap and acceptance exactly at the cap.

## Pinned runtime and provider matrix

The Compose service tags are exact release tags (no `latest`). OCI digests below are the repository digests observed on the tested ARM64 platform; they make the measured images traceable. The API and worker are built from the checked-out source rather than pulled from a mutable application image.

| Role | Pin used |
| --- | --- |
| PostgreSQL + pgvector | `pgvector/pgvector:0.8.0-pg17`; PostgreSQL 17.6, pgvector 0.8.0; ARM64 digest `sha256:40b404964359299eefdd5f8518facf1886c562848cf4de13b6eaf91cb70c2b87` |
| Redis Streams | `redis:7.4.2-alpine` (Redis 7.4.2; satisfies the Redis 6.2+ requirement); ARM64 digest `sha256:02419de7eddf55aa5bcf49efb74e88fa8d931b4d77c07eff8a6b2144472b6952` |
| GROBID | `grobid/grobid:0.9.1-crf`; parser version recorded as `0.9.1-crf`; ARM64 digest `sha256:223957791ac2bbe48609dcc58a689b16b60baeae13a8734ef440ae6bfb38f4cd` |
| Object store | `quay.io/minio/minio:RELEASE.2025-02-07T23-21-09Z`; ARM64 digest observed as `sha256:640c22768ed5dbc92eacc14502a1b06a1c708fa60431345c78dfc22917062e93` |
| Optional Ollama service | `ollama/ollama:0.34.4`; ARM64 digest `sha256:8262851b2846b87c649eddf3e76beb270c52f4d1bc94559f47efde16b0841551`. Ollama was disabled during these measurements. |
| Optional Ollama embedding model | [`nomic-embed-text:v1.5`](https://ollama.com/library/nomic-embed-text/tags), 768 dimensions, Apache-2.0; Ollama library manifest digest `0a109f422b47`. The benchmark disables model initialization and makes no embedding-provider calls. |
| Schema migration | `sqitch/sqitch:v1.6.1.3`; ARM64 digest `sha256:f247ab0e0b66e9c2d09a400864f7314358893f5cf209cddcc4f213f7d5bfe4d3` |
| Local claim extraction | `heuristic:v1` (versioned in the run configuration) |
| Bibliography resolution | `recorded-fixtures:v1` provider; threshold `0.9`; no remote metadata requests |
| Open-access discovery | `recorded-fixtures:v1`; no external content-host requests |
| System One | `mock:v1` is selected as the local default but no semantic verification call is made in a `PARSED` run |
| Embeddings / retrieval | `feature-hash-384-v1` and `postgres-hybrid-rrf-v1`; deterministic local retrieval. No semantic verifier was invoked in these `PARSED` runs. |
| Web build matrix (not part of the processing measurement) | Node `24.11.0-alpine`; pnpm `12.6.0`; Next.js `16.3.6`; React `19.1.0`; dependency versions locked by `web/pnpm-lock.yaml` |

Redis Streams reclaim uses the deployed Redis 7.4.2 runtime, above the 6.2 minimum associated with `XAUTOCLAIM`; the current worker implementation uses pending-entry inspection and `XCLAIM` rather than issuing `XAUTOCLAIM` directly.
