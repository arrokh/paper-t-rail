# Paper T-Rail Synthetic Source Document Fixture

Upload this PDF to validate source parsing and Citation Context behavior. Page footers are the visible QA oracle.

## Page 1: A Controlled Test of Traceable Citation Evidence

Paper T-Rail QA Fixture Team | Synthetic manuscript for local validation

TEST DOCUMENT - The scenarios below are synthetic. Use this Source Document only in a local/test environment. Footer text is the visible QA oracle, not manuscript content.

### Section
Abstract

This synthetic manuscript exercises citation parsing, claim boundaries, reference matching, and source-span tracing. It includes one exact match for the repository's recorded local scholarly-work fixture, one deliberately unmatched journal reference, and one website reference.

### Section
1. Introduction

### Caseheading
Scenario C-01 | Simple cited proposition

Conservative reference resolution prevents ambiguous bibliography entries from being assigned an unsupported paper identity [1].

The final Evidence Coverage Report is a triage aid, not a truth certificate. This sentence is intentionally uncited and should not create a claim verification.

> **Claim expectation footer:** C-01: one Atomic Claim; R1 matches the checked-in DOI/full-text fixture. Final semantic status depends on provider selection; this PDF is not model-accuracy evidence.

## Page 2: 2. Claim Boundaries and Qualifiers

### Section
2.1 Shared population qualifier

### Caseheading
Scenario C-02 | Coordinated predicates

Among older adults, treatment reduced pain and improved mobility [1, 2].

### Section
2.2 Ambiguous negation

### Caseheading
Scenario C-03 | Do not guess negation scope

Treatment did not improve symptoms and reduce dropout [1].

### Section
2.3 Ambiguous trailing qualifier

### Caseheading
Scenario C-04 | Preserve uncertain qualifier scope

Treatment reduced pain in older adults and improved mobility [1].

The examples are fixtures for claim-shape behavior; they are not empirical findings about the cited paper.

> **Claim expectation footer:** C-02: two claims with the shared qualifier and both targets; C-03/C-04: one conservative claim each, with negation and scope preserved.

## Page 3: 3. Citation Contexts and Source Spans

### Section
3.1 Multiple markers in one context

### Caseheading
Scenario C-05 | Two markers, one context

Prior work supports this method [1] and reports similar outcomes [2].

### Section
3.2 Citation-bearing clauses

### Caseheading
Scenario C-06 | Separate clauses

The treatment improved symptoms [1]; however, controls found no effect [2].

### Section
3.3 Uncertain boundary and duplicate wording

### Caseheading
Scenario C-07 | Sentence fallback

Prior work supports the method [1], which remains under discussion [2].

### Caseheading
Scenario C-08 | Same wording, different locations

The intervention improved mobility [1].

The intervention improved mobility [1].

### Section
3.4 Uncited and author-date examples

### Caseheading
Scenario C-09 | No citation

A proposition without a citation appears here and should not be verified.

### Caseheading
Scenario C-10 | Author-date marker

The study describes a conservative reference-resolution method (Example & Researcher, 2024).

> **Claim expectation footer:** C-05 shared targets; C-06 separate clauses; C-07 fallback; C-08 distinct spans; C-09 uncited; C-10 author-date if parsed. R1 exact DOI -> RESOLVED; R2 unmatched -> UNRESOLVED; inspect R3 type before unsupported outcome.

## Page 4: References

### Section
References

[1] Example, Riley, and Jordan Researcher. 2024. A fixture study of conservative scholarly reference resolution. Journal of Reference Resolution 1 (1): 1-2. DOI: 10.5555/papertrail.fixture.reference-resolution.2024.

[2] Sample, Morgan. 2026. Unmatched test reference for QA. Journal of Validation Cases 4 (2): 12-19. DOI: 10.5555/papertrail.qa.missing.2026.

[3] Test Automation Office. 2025. User Manual for the Synthetic Validation Toolkit. Web page: https://qa.example.test/manual.
