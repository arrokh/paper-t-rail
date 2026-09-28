import assert from "node:assert/strict";
import test from "node:test";
import {
  availableProviderSelections,
  consentRequirements,
  createRunConfiguration,
  DEFAULT_PROVIDER_SELECTIONS,
  missingConsents,
  retainRequiredApprovals,
  selectableProviderOptions,
} from "../features/providers/provider-configuration.ts";

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
      {
        role: "claimExtractor",
        providerId: "unclassified-ai",
        displayName: "Unclassified AI",
        version: "v1",
        model: null,
        trustBoundary: "UNREVIEWED",
        dataCategories: ["citation_context"],
        retentionDisclosure: null,
      },
      {
        role: "claimExtractor",
        providerId: "incomplete-ai",
        displayName: "Incomplete AI disclosure",
        version: "v1",
        model: null,
        trustBoundary: "EXTERNAL",
        dataCategories: [],
        retentionDisclosure: null,
      },
    ],
    embedding: [
      {
        role: "embedding",
        providerId: "local",
        displayName: "Local embeddings",
        version: "v1",
        model: "feature-hash-384-v1",
        trustBoundary: "LOCAL",
        dataCategories: ["atomic_claims", "cited_paper_chunks", "embedding_input"],
        retentionDisclosure: null,
      },
      {
        role: "embedding",
        providerId: "hosted-ai",
        displayName: "Hosted AI",
        version: "v2",
        model: "embed-2",
        trustBoundary: "EXTERNAL",
        dataCategories: ["atomic_claims", "cited_paper_chunks", "embedding_input"],
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

function selectionsWith(overrides) {
  return { ...localSelections, ...overrides };
}

test("local configuration defaults to Laya when it is available", () => {
  const directoryWithLaya = {
    ...directory,
    providers: {
      ...directory.providers,
      systemOne: [
        ...directory.providers.systemOne,
        {
          role: "systemOne",
          providerId: "laya",
          displayName: "Laya System One (local evaluation)",
          version: "laya-serve-pinned",
          model: "typed-decisions",
          trustBoundary: "LOCAL",
          dataCategories: ["atomic_claims", "evidence_passages"],
          retentionDisclosure: null,
        },
      ],
    },
  };

  assert.equal(availableProviderSelections(directory, DEFAULT_PROVIDER_SELECTIONS).systemOneProvider, "mock");
  assert.equal(availableProviderSelections(directoryWithLaya, DEFAULT_PROVIDER_SELECTIONS).systemOneProvider, "laya");
});

test("provider selections fall back to an available open-access provider", () => {
  const selections = availableProviderSelections(directory, selectionsWith({
    openAccessProvider: "removed-provider",
  }));

  assert.equal(selections.openAccessProvider, "recorded-fixtures");
});

test("local defaults produce a valid configuration without external consent", () => {
  assert.deepEqual(consentRequirements(directory, localSelections), []);
  assert.deepEqual(createRunConfiguration(directory, localSelections, {}), {
    ...localSelections,
    externalProviderConsents: [],
  });
});

test("default reconciliation and available choices exclude unclassified or incomplete providers", () => {
  assert.deepEqual(
    selectableProviderOptions(directory, "claimExtractor").map(({ providerId }) => providerId),
    ["heuristic", "hosted-ai"],
  );
  assert.deepEqual(
    availableProviderSelections(directory, selectionsWith({ claimExtractorProvider: "unclassified-ai" })),
    localSelections,
  );
});

test("one external provider selected for multiple roles receives the deduplicated category union", () => {
  const selections = selectionsWith({
    claimExtractorProvider: "hosted-ai",
    embeddingProvider: "hosted-ai",
  });
  const requirements = consentRequirements(directory, selections);
  const expectedCategories = ["atomic_claims", "citation_context", "cited_paper_chunks", "embedding_input"];
  assert.deepEqual(requirements, [{
    providerId: "hosted-ai",
    displayName: "Hosted AI",
    dataCategories: expectedCategories,
    retentionDisclosure: "Provider retention terms reviewed for this deployment.",
  }]);

  const partialApproval = { "hosted-ai": ["citation_context"] };
  assert.deepEqual(missingConsents(requirements, partialApproval), requirements);
  assert.throws(() => createRunConfiguration(directory, selections, partialApproval), /Approve every disclosed data category/);

  const fullApproval = { "hosted-ai": [...expectedCategories, "not-required"] };
  assert.deepEqual(createRunConfiguration(directory, selections, fullApproval), {
    ...selections,
    externalProviderConsents: [{ providerId: "hosted-ai", dataCategories: expectedCategories }],
  });
});

test("Open-access discovery and acquisition require consent for the actual metadata, contact email, and content location", () => {
  const selections = selectionsWith({ openAccessProvider: "unpaywall" });
  const requirements = consentRequirements(directory, selections);

  assert.deepEqual(requirements, [{
    providerId: "unpaywall",
    displayName: "Unpaywall and discovered open-access hosts",
    dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"],
    retentionDisclosure: "Reviewed Unpaywall request and retention disclosure.",
  }]);
  assert.deepEqual(missingConsents(requirements, {}), requirements);
});

test("changing selections recalculates required approvals and drops approvals no longer required", () => {
  const externalSelections = selectionsWith({ claimExtractorProvider: "hosted-ai" });
  const priorRequirements = consentRequirements(directory, externalSelections);
  const priorApproval = { "hosted-ai": ["citation_context"] };
  assert.deepEqual(missingConsents(priorRequirements, priorApproval), []);

  const nextRequirements = consentRequirements(directory, localSelections);
  assert.deepEqual(nextRequirements, []);
  assert.deepEqual(retainRequiredApprovals(nextRequirements, priorApproval), {});
});

test("Crossref requires explicit per-run approval of bibliographic metadata", () => {
  const selections = selectionsWith({ scholarlyMetadataProvider: "crossref" });
  const requirements = consentRequirements(directory, selections);
  assert.deepEqual(requirements, [{
    providerId: "crossref",
    displayName: "Crossref REST API",
    dataCategories: ["bibliographic_metadata"],
    retentionDisclosure: "Crossref request logging and retention disclosure.",
  }]);
  assert.throws(() => createRunConfiguration(directory, selections, {}), /Approve every disclosed data category/);
  assert.deepEqual(createRunConfiguration(directory, selections, { crossref: ["bibliographic_metadata"] }), {
    ...selections,
    externalProviderConsents: [{ providerId: "crossref", dataCategories: ["bibliographic_metadata"] }],
  });
});

test("run configuration rejects provider selections outside the classified directory", () => {
  const selections = selectionsWith({ claimExtractorProvider: "unclassified-ai" });
  assert.throws(() => createRunConfiguration(directory, selections, { "unclassified-ai": ["citation_context"] }), /enabled, classified provider/);
});
