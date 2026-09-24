import assert from "node:assert/strict";
import test from "node:test";
import { consentRequirements, createRunConfiguration, missingConsents } from "../lib/provider-configuration.ts";

const directory = {
  providers: [
    {
      role: "claimExtractor",
      providerId: "heuristic",
      displayName: "Heuristic",
      version: "v1",
      model: null,
      trustBoundary: "LOCAL",
      dataCategories: ["citation_context"],
      retentionDisclosure: null,
    },
    {
      role: "claimExtractor",
      providerId: "hosted-ai",
      displayName: "Hosted AI",
      version: "v2",
      model: "model-2",
      trustBoundary: "EXTERNAL",
      dataCategories: ["citation_context"],
      retentionDisclosure: "Provider retention terms reviewed for this deployment.",
    },
    {
      role: "embedding",
      providerId: "local",
      displayName: "Local embeddings",
      version: "v1",
      model: "e5-small-v2",
      trustBoundary: "LOCAL",
      dataCategories: ["cited_paper_chunks", "embedding_input"],
      retentionDisclosure: null,
    },
    {
      role: "embedding",
      providerId: "hosted-ai",
      displayName: "Hosted AI",
      version: "v2",
      model: "embed-2",
      trustBoundary: "EXTERNAL",
      dataCategories: ["cited_paper_chunks", "embedding_input"],
      retentionDisclosure: "Provider retention terms reviewed for this deployment.",
    },
    {
      role: "systemOne",
      providerId: "mock",
      displayName: "Mock",
      version: "v1",
      model: "mock-v1",
      trustBoundary: "LOCAL",
      dataCategories: ["atomic_claims", "evidence_passages"],
      retentionDisclosure: null,
    },
  ],
  dataCategories: [],
};

test("local defaults have no external provider consent requirements", () => {
  const requirements = consentRequirements(directory, {
    claimExtractorProvider: "heuristic",
    embeddingProvider: "local",
    systemOneProvider: "mock",
  });

  assert.deepEqual(requirements, []);
  assert.deepEqual(missingConsents(requirements, {}), []);
  assert.deepEqual(createRunConfiguration({
    claimExtractorProvider: "heuristic",
    embeddingProvider: "local",
    systemOneProvider: "mock",
  }, requirements, {}), {
    claimExtractorProvider: "heuristic",
    embeddingProvider: "local",
    systemOneProvider: "mock",
    externalProviderConsents: [],
  });
});

test("external provider disclosure and saved consent include every selected request category", () => {
  const selections = {
    claimExtractorProvider: "hosted-ai",
    embeddingProvider: "hosted-ai",
    systemOneProvider: "mock",
  };
  const requirements = consentRequirements(directory, selections);
  assert.deepEqual(requirements, [{
    providerId: "hosted-ai",
    displayName: "Hosted AI",
    dataCategories: ["citation_context", "cited_paper_chunks", "embedding_input"],
    retentionDisclosure: "Provider retention terms reviewed for this deployment.",
  }]);
  assert.deepEqual(missingConsents(requirements, { "hosted-ai": ["citation_context"] }), requirements);

  const approval = { "hosted-ai": ["citation_context", "cited_paper_chunks", "embedding_input"] };
  assert.deepEqual(missingConsents(requirements, approval), []);
  assert.deepEqual(createRunConfiguration(selections, requirements, approval).externalProviderConsents, [{
    providerId: "hosted-ai",
    dataCategories: ["citation_context", "cited_paper_chunks", "embedding_input"],
  }]);
});
