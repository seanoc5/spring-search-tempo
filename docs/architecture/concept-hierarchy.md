# Concept Hierarchy Approach

`ConceptHierarchy` is the parent wrapper for a named taxonomy import. It lets the system keep multiple concept spaces side by side instead of flattening everything into one global node table.

Current core hierarchies:

- `OCONECO`: the OconEco tree, expected to carry address values on nodes.
- `OPENALEX_CONCEPTS`: the legacy OpenAlex concepts taxonomy.
- `OPENALEX_TOPICS`: the newer OpenAlex topics hierarchy.

`ConceptNode` belongs to exactly one `ConceptHierarchy` and may point to a parent `ConceptNode` inside that same hierarchy. This gives each hierarchy its own rooted forest while still allowing shared repository and import code.

Important boundary:

- This model is separate from the existing `ContentChunk.concept -> FSFile` field.
- That older field still means "file parent for a chunk" and should not be reused for taxonomy work.
- A later cleanup can rename that chunk field if the terminology becomes too confusing.

Suggested import contract per node:

- `externalKey`: source-native stable identifier.
- `uri`: system-stable identifier.
- `label` and optional `description`.
- `parent`: direct parent in the same hierarchy.
- `address`: optional, mainly for OconEco.
- `wikidataId` / `openAlexId`: optional crosswalk identifiers.
- `path` and `depthLevel`: denormalized helpers for browsing and import validation.
