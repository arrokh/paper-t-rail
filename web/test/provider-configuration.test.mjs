import assert from "node:assert/strict";
import test from "node:test";
import { availableProviderSelections, consentRequirements, createRunConfiguration, missingConsents } from "../features/providers/provider-configuration.ts";

const directory = {
  providers: {
    claimExtractor: [
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
    ],
    embedding: [
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
    ],
    systemOne: [
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
    openAccess: [
      {
        role: "openAccess",
        providerId: "recorded-fixtures",
        displayName: "Recorded open-access fixtures",
        version: "v1",
        model: null,
        trustBoundary: "LOCAL",
        dataCategories: ["bibliographic_metadata", "cited_paper_location"],
        retentionDisclosure: null,
      },
      {
        role: "openAccess",
        providerId: "unpaywall",
        displayName: "Unpaywall and discovered open-access hosts",
        version: "v2",
        model: null,
        trustBoundary: "EXTERNAL",
        dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"],
        retentionDisclosure: "Reviewed Unpaywall request and retention disclosure.",
      },
    ],
    scholarlyMetadata: [
      {
        role: "scholarlyMetadata",
        providerId: "recorded-fixtures",
        displayName: "Recorded scholarly metadata fixtures",
        version: "v1",
        model: null,
        trustBoundary: "LOCAL",
        dataCategories: ["bibliographic_metadata"],
        retentionDisclosure: null,
      },
      {
        role: "scholarlyMetadata",
        providerId: "crossref",
        displayName: "Crossref REST API",
        version: "v1",
        model: null,
        trustBoundary: "EXTERNAL",
        dataCategories: ["bibliographic_metadata"],
        retentionDisclosure: "Crossref request logging and retention disclosure.",
      },
    ],
  },
  dataCategories: [],
};

const localSelections = {
  claimExtractorProvider: "heuristic",
  embeddingProvider: "local",
  systemOneProvider: "mock",
  scholarlyMetadataProvider: "recorded-fixtures",
  openAccessProvider: "recorded-fixtures",
};

test("provider selections fall back to an available open-access provider", () => {
  const selections = availableProviderSelections(directory, {
    ...localSelections,
    openAccessProvider: "removed-provider",
  });

  assert.equal(selections.openAccessProvider, "recorded-fixtures");
});

test("local defaults have no external provider consent requirements", () => {
  const requirements = consentRequirements(directory, localSelections);

  assert.deepEqual(requirements, []);
  assert.deepEqual(missingConsents(requirements, {}), []);
  assert.deepEqual(createRunConfiguration(localSelections, requirements, {}), {
    ...localSelections,
    externalProviderConsents: [],
  });
});

test("external provider disclosure and saved consent include every selected request category", () => {
  const selections = {
    ...localSelections,
    claimExtractorProvider: "hosted-ai",
    embeddingProvider: "hosted-ai",
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

test("Open-access discovery and acquisition require consent for the actual metadata, contact email, and content location", () => {
  const selections = { ...localSelections, openAccessProvider: "unpaywall" };
  const requirements = consentRequirements(directory, selections);

  assert.deepEqual(requirements, [{
    providerId: "unpaywall",
    displayName: "Unpaywall and discovered open-access hosts",
    dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"],
    retentionDisclosure: "Reviewed Unpaywall request and retention disclosure.",
  }]);
  assert.deepEqual(missingConsents(requirements, {}), requirements);
});

test("Crossref selection requires explicit per-run consent for bibliographic metadata", () => {
  const selections = { ...localSelections, scholarlyMetadataProvider: "crossref" };
  const requirements = consentRequirements(directory, selections);
  assert.deepEqual(requirements, [{
    providerId: "crossref",
    displayName: "Crossref REST API",
    dataCategories: ["bibliographic_metadata"],
    retentionDisclosure: "Crossref request logging and retention disclosure.",
  }]);
  assert.deepEqual(missingConsents(requirements, {}), requirements);

  const approved = { crossref: ["bibliographic_metadata"] };
  assert.deepEqual(missingConsents(requirements, approved), []);
  assert.deepEqual(createRunConfiguration(selections, requirements, approved), {
    ...selections,
    externalProviderConsents: [{ providerId: "crossref", dataCategories: ["bibliographic_metadata"] }],
  });
});
