# Paper T-Rail — Academic Evidence

Paper T-Rail audits citation-backed claims in an academic source document by connecting each claim to cited scholarly works and evidence. This context defines the domain language used in an Evidence Coverage Report.

## Documents and citations

**Source Document**:
The academic document being audited; it contains claims and citations.
_Avoid_: source paper when it could be confused with a cited work.

**Citation Marker**:
An in-text marker that points to one or more bibliography entries.
_Avoid_: citation when referring specifically to the marker text.

**Citation Occurrence**:
One observed appearance of a Citation Marker in the Source Document, with its source span and parsed target links.

**Citation Target**:
A reference destination associated with a Citation Marker; in V1, each target points to a Bibliography Entry. One marker may have multiple targets.

**Citation Context**:
The smallest citation-bearing clause around one or more markers; use the containing sentence when clause boundaries are unclear. Do not share citation targets across separate clause contexts. Claims are linked to all targets in their own context by an inferred, not author-confirmed, association; duplicate claims within a run are recognized by source span.
_Avoid_: citation sentence as a synonym; a context is clause-level or falls back to one containing sentence.

**Bibliography Entry**:
A reference as written in the Source Document; it is not necessarily a resolved identity for a scholarly work.
_Avoid_: canonical paper when referring to the raw entry.

**Cited Paper**:
A scholarly work named by a Bibliography Entry and used as a candidate evidence source for an Atomic Claim.
_Avoid_: source paper when referring to a cited work.

**Canonical Paper**:
The normalized identity of a Cited Paper, distinct from the Bibliography Entry that describes it.
_Avoid_: reference when referring to the work itself.

**Cited Reference**:
A Bibliography Entry reached through a Citation Target and assessed for a particular Atomic Claim; it may remain unresolved or map to a Canonical Paper.

## Claims and evidence

**Atomic Claim**:
A proposition that can be assessed independently; splitting compound claims preserves qualifiers that affect meaning, such as population, conditions, scope, and uncertainty.
_Avoid_: sentence when referring to a proposition extracted from a sentence.

**Evidence Passage**:
A passage from a Cited Paper considered as evidence for or against an Atomic Claim.
_Avoid_: evidence when referring to a passage whose source or location is not identified.

**Evidence Role**:
Whether an Evidence Passage reports the Cited Paper's own finding, the authors' synthesis, or a result attributed to another work. Role, directness, claim-scope match, study design, relevance, and calibrated judgement inform evidence-strength comparisons; model confidence alone is not decisive.
_Avoid_: evidence type when referring to support/contradiction judgements.

**Evidence Judgement**:
A passage-level assessment of how one Evidence Passage relates to an Atomic Claim, such as direct support, partial support, contradiction, or unrelated. It is distinct from the final Claim–Paper Verification status.

**System One**:
The configured provider boundary that makes narrow Evidence Judgements about claim/passage pairs; deterministic application policy, not System One, assigns the final verification status.

**Claim–Paper Verification**:
An assessment of one Atomic Claim against one Cited Reference; when the reference resolves, the assessment is against its Cited Paper and may use one or more Evidence Passages.
_Avoid_: claim verification when the specific cited work is material.

## Analysis and review

**Analysis Run**:
One analysis of a Source Document under a defined analysis configuration. Results are immutable during normal use; explicit user deletion is the privacy exception. Re-analysis creates a new run rather than replacing prior results.
_Avoid_: analysis when referring to a specific execution.

**Evidence Coverage Report**:
A traceable summary and drilldown of Claim–Paper Verifications. It is a triage aid, not certification of truth or an assessment of the whole paper.
_Avoid_: paper grade, citation certification.

**Human Review**:
A person's separate assessment of a machine result; it does not rewrite the original machine result.
_Avoid_: override when referring to any review that does not explicitly change the displayed human assessment.
