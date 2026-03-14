package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.ConceptHierarchyService
import com.oconeco.spring_search_tempo.base.domain.ConceptHierarchy
import com.oconeco.spring_search_tempo.base.domain.ConceptNode
import com.oconeco.spring_search_tempo.base.model.ConceptHierarchyOption
import com.oconeco.spring_search_tempo.base.model.ConceptNodeDetail
import com.oconeco.spring_search_tempo.base.model.ConceptNodeSummary
import com.oconeco.spring_search_tempo.base.model.ConceptSearchResult
import com.oconeco.spring_search_tempo.base.model.ConceptHierarchySummary
import com.oconeco.spring_search_tempo.base.model.OconecoImportResult
import com.oconeco.spring_search_tempo.base.repos.ConceptHierarchyRepository
import com.oconeco.spring_search_tempo.base.repos.ConceptNodeRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class ConceptHierarchyServiceImpl(
    private val conceptHierarchyRepository: ConceptHierarchyRepository,
    private val conceptNodeRepository: ConceptNodeRepository
) : ConceptHierarchyService {

    companion object {
        private val log = LoggerFactory.getLogger(ConceptHierarchyServiceImpl::class.java)
        private const val OCONECO_CODE = "OCONECO"
        private val EXTERNAL_KEY_HEADERS = setOf(
            "external key", "external_key", "key", "code", "id", "node id", "node_id",
            "concept id", "concept_id", "oconeco id", "oconeco_id"
        )
        private val LABEL_HEADERS = setOf("label", "name", "title", "concept", "preferred label", "preferred_label")
        private val PARENT_HEADERS = setOf(
            "parent", "parent key", "parent_key", "parent id", "parent_id",
            "parent concept", "parent concept id", "broader", "broader id", "broader_id"
        )
        private val DESCRIPTION_HEADERS = setOf("description", "definition", "summary", "notes")
        private val ADDRESS_HEADERS = setOf("address", "hierarchy address", "path address", "oconeco address")
        private val URI_HEADERS = setOf("uri")
        private val WIKIDATA_HEADERS = setOf("wikidata", "wikidata id", "wikidata_id")
        private val OPENALEX_HEADERS = setOf("openalex", "openalex id", "openalex_id")
    }

    @Transactional(readOnly = true)
    override fun listHierarchies(): List<ConceptHierarchyOption> =
        conceptHierarchyRepository.findAllByOrderByLabelAsc().map { hierarchy ->
            ConceptHierarchyOption(
                code = requireNotNull(hierarchy.code),
                label = requireNotNull(hierarchy.label)
            )
        }

    @Transactional(readOnly = true)
    override fun getHierarchySummary(code: String): ConceptHierarchySummary {
        val hierarchy = getHierarchy(code)
        return ConceptHierarchySummary(
            code = requireNotNull(hierarchy.code),
            label = requireNotNull(hierarchy.label),
            description = hierarchy.description,
            activeNodeCount = conceptNodeRepository.countByHierarchyCodeAndActiveTrue(code),
            rootNodeCount = conceptNodeRepository.countByHierarchyCodeAndParentIsNullAndActiveTrue(code),
            lastImportedAt = hierarchy.lastImportedAt
        )
    }

    @Transactional(readOnly = true)
    override fun getRootNodes(hierarchyCode: String): List<ConceptNodeSummary> =
        conceptNodeRepository.findAllByHierarchyCodeAndParentIsNullAndActiveTrueOrderByLabelAsc(hierarchyCode)
            .map { node -> toSummary(node, childCount = childCount(node)) }

    @Transactional(readOnly = true)
    override fun getNodeDetail(nodeId: Long): ConceptNodeDetail {
        val node = conceptNodeRepository.findWithHierarchyAndParentById(nodeId)
            ?: throw NotFoundException("Concept node not found: $nodeId")
        val breadcrumbs = buildBreadcrumbs(node)
        val children = conceptNodeRepository.findAllByParentIdAndActiveTrueOrderByLabelAsc(requireNotNull(node.id))
            .map { child -> toSummary(child, childCount = childCount(child)) }
        return ConceptNodeDetail(
            node = toSummary(node, childCount = children.size),
            breadcrumbs = breadcrumbs,
            children = children
        )
    }

    @Transactional(readOnly = true)
    override fun search(query: String, hierarchyCode: String?, limit: Int): List<ConceptSearchResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }
        val nodes = if (hierarchyCode.isNullOrBlank()) {
            conceptNodeRepository.searchActive(normalizedQuery)
        } else {
            conceptNodeRepository.searchActiveInHierarchy(hierarchyCode, normalizedQuery)
        }
        return nodes.take(limit).map { node ->
            ConceptSearchResult(
                id = requireNotNull(node.id),
                hierarchyCode = requireNotNull(node.hierarchy?.code),
                hierarchyLabel = requireNotNull(node.hierarchy?.label),
                externalKey = requireNotNull(node.externalKey),
                label = requireNotNull(node.label),
                description = node.description,
                address = node.address,
                path = node.path
            )
        }
    }

    override fun importOconecoHierarchy(inputStream: InputStream, originalFilename: String?): OconecoImportResult {
        val hierarchy = getHierarchy(OCONECO_CODE)
        val parsed = parseWorkbook(inputStream)
        val existingNodes = conceptNodeRepository.findAllByHierarchyId(requireNotNull(hierarchy.id))
        val existingByKey = existingNodes.associateBy { requireNotNull(it.externalKey) }.toMutableMap()
        val touchedKeys = linkedSetOf<String>()

        var created = 0
        var updated = 0

        parsed.rows.forEach { row ->
            val node = existingByKey[row.externalKey]
            if (node == null) {
                existingByKey[row.externalKey] = ConceptNode().apply {
                    this.hierarchy = hierarchy
                    this.externalKey = row.externalKey
                }
                created++
            } else {
                updated++
            }
            touchedKeys += row.externalKey
        }

        val allNodes = existingByKey.values.toList()
        parsed.rows.forEach { row ->
            val node = existingByKey.getValue(row.externalKey)
            node.hierarchy = hierarchy
            node.externalKey = row.externalKey
            node.label = row.label
            node.description = row.description
            node.address = row.address
            node.wikidataId = row.wikidataId
            node.openAlexId = row.openAlexId
            node.uri = row.uri ?: buildNodeUri(hierarchy, row.externalKey)
            node.active = true
            node.leaf = false
            node.parent = null
            node.path = null
            node.depthLevel = null
        }
        conceptNodeRepository.saveAll(allNodes)

        parsed.rows.forEach { row ->
            val node = existingByKey.getValue(row.externalKey)
            node.parent = row.parentExternalKey?.let { parentKey ->
                require(parentKey != row.externalKey) {
                    "Node ${row.externalKey} cannot list itself as parent"
                }
                existingByKey[parentKey]
                    ?: throw IllegalArgumentException("Missing parent '$parentKey' for node '${row.externalKey}'")
            }
        }

        val activeNodes = parsed.rows.map { existingByKey.getValue(it.externalKey) }
        computeTreeMetadata(activeNodes)

        var deactivated = 0
        existingNodes.asSequence()
            .filter { !touchedKeys.contains(requireNotNull(it.externalKey)) && it.active }
            .forEach { node ->
                node.active = false
                node.leaf = false
                node.parent = null
                node.path = null
                node.depthLevel = null
                deactivated++
            }

        conceptNodeRepository.saveAll(existingByKey.values)
        hierarchy.lastImportedAt = OffsetDateTime.now()
        conceptHierarchyRepository.save(hierarchy)

        log.info(
            "Imported OconEco hierarchy from {}: rows={}, created={}, updated={}, deactivated={}",
            originalFilename ?: "uploaded workbook",
            parsed.rows.size,
            created,
            updated,
            deactivated
        )

        return OconecoImportResult(
            hierarchyCode = OCONECO_CODE,
            importedRows = parsed.rows.size,
            createdNodes = created,
            updatedNodes = updated,
            deactivatedNodes = deactivated,
            rootNodes = activeNodes.count { it.parent == null },
            detectedColumns = parsed.detectedColumns
        )
    }

    private fun parseWorkbook(inputStream: InputStream): ParsedWorkbook {
        WorkbookFactory.create(inputStream).use { workbook ->
            val sheet = workbook.getSheetAt(0) ?: throw IllegalArgumentException("Workbook must contain at least one sheet")
            val headerRow = sheet.firstOrNull() ?: throw IllegalArgumentException("Workbook must contain a header row")
            val headers = headerMap(headerRow)

            val externalKeyIdx = resolveHeader(headers, EXTERNAL_KEY_HEADERS, "external key / id")
            val labelIdx = resolveHeader(headers, LABEL_HEADERS, "label / name")
            val parentIdx = optionalHeader(headers, PARENT_HEADERS)
            val descriptionIdx = optionalHeader(headers, DESCRIPTION_HEADERS)
            val addressIdx = optionalHeader(headers, ADDRESS_HEADERS)
            val uriIdx = optionalHeader(headers, URI_HEADERS)
            val wikidataIdx = optionalHeader(headers, WIKIDATA_HEADERS)
            val openAlexIdx = optionalHeader(headers, OPENALEX_HEADERS)

            val formatter = DataFormatter()
            val rows = mutableListOf<ImportedConceptRow>()
            val seenKeys = mutableSetOf<String>()

            for (rowIndex in headerRow.rowNum + 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                if (rowIsBlank(row, formatter)) {
                    continue
                }

                val externalKey = cellValue(row, externalKeyIdx, formatter)
                val label = cellValue(row, labelIdx, formatter)
                require(externalKey.isNotBlank()) { "Row ${rowIndex + 1} is missing an external key" }
                require(label.isNotBlank()) { "Row ${rowIndex + 1} is missing a label" }
                require(seenKeys.add(externalKey)) { "Duplicate external key '$externalKey' on row ${rowIndex + 1}" }

                rows += ImportedConceptRow(
                    externalKey = externalKey,
                    label = label,
                    parentExternalKey = parentIdx?.let { cellValue(row, it, formatter).ifBlank { null } },
                    description = descriptionIdx?.let { cellValue(row, it, formatter).ifBlank { null } },
                    address = addressIdx?.let { cellValue(row, it, formatter).ifBlank { null } },
                    uri = uriIdx?.let { cellValue(row, it, formatter).ifBlank { null } },
                    wikidataId = wikidataIdx?.let { cellValue(row, it, formatter).ifBlank { null } },
                    openAlexId = openAlexIdx?.let { cellValue(row, it, formatter).ifBlank { null } }
                )
            }

            require(rows.isNotEmpty()) { "Workbook does not contain any importable data rows" }

            return ParsedWorkbook(
                rows = rows,
                detectedColumns = headers.entries
                    .sortedBy { it.value }
                    .map { it.key }
            )
        }
    }

    private fun computeTreeMetadata(nodes: List<ConceptNode>) {
        val byParentId = nodes.groupBy { it.parent?.externalKey }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(node: ConceptNode, depth: Int, pathPrefix: String?) {
            val nodeKey = requireNotNull(node.externalKey)
            require(visiting.add(nodeKey)) { "Cycle detected involving node '$nodeKey'" }

            node.depthLevel = depth
            node.path = if (pathPrefix.isNullOrBlank()) {
                requireNotNull(node.label)
            } else {
                "$pathPrefix > ${requireNotNull(node.label)}"
            }

            val children = byParentId[nodeKey].orEmpty().filter { it.active }
            node.leaf = children.isEmpty()
            children.forEach { child -> visit(child, depth + 1, node.path) }

            visiting.remove(nodeKey)
            visited += nodeKey
        }

        nodes.filter { it.parent == null && it.active }
            .sortedBy { it.label }
            .forEach { root -> visit(root, 0, null) }

        val unresolved = nodes.filter { it.active && !visited.contains(it.externalKey) }.map { it.externalKey }
        require(unresolved.isEmpty()) { "Unable to resolve tree metadata for nodes: ${unresolved.joinToString(", ")}" }
    }

    private fun getHierarchy(code: String): ConceptHierarchy =
        conceptHierarchyRepository.findByCode(code) ?: throw NotFoundException("Concept hierarchy not found: $code")

    private fun buildBreadcrumbs(node: ConceptNode): List<ConceptNodeSummary> {
        val breadcrumbs = mutableListOf<ConceptNodeSummary>()
        var current: ConceptNode? = node
        while (current != null) {
            breadcrumbs += toSummary(current, childCount = childCount(current))
            current = current.parent?.id?.let { conceptNodeRepository.findWithHierarchyAndParentById(it) }
        }
        return breadcrumbs.asReversed()
    }

    private fun toSummary(node: ConceptNode, childCount: Int): ConceptNodeSummary =
        ConceptNodeSummary(
            id = requireNotNull(node.id),
            hierarchyCode = requireNotNull(node.hierarchy?.code),
            hierarchyLabel = requireNotNull(node.hierarchy?.label),
            externalKey = requireNotNull(node.externalKey),
            label = requireNotNull(node.label),
            description = node.description,
            address = node.address,
            path = node.path,
            depthLevel = node.depthLevel,
            active = node.active,
            leaf = node.leaf,
            childCount = childCount
        )

    private fun childCount(node: ConceptNode): Int =
        if (node.id == null) 0 else conceptNodeRepository.findAllByParentIdAndActiveTrueOrderByLabelAsc(node.id!!).size

    private fun buildNodeUri(hierarchy: ConceptHierarchy, externalKey: String): String {
        val hierarchyCode = requireNotNull(hierarchy.code).lowercase()
        val encodedKey = URLEncoder.encode(externalKey, StandardCharsets.UTF_8)
        return "concept-node:$hierarchyCode:$encodedKey"
    }

    private fun headerMap(headerRow: Row): Map<String, Int> =
        buildMap {
            val formatter = DataFormatter()
            for (cellIndex in headerRow.firstCellNum until headerRow.lastCellNum) {
                if (cellIndex < 0) continue
                val normalized = formatter.formatCellValue(headerRow.getCell(cellIndex)).normalizeHeader()
                if (normalized.isNotBlank()) {
                    put(normalized, cellIndex)
                }
            }
        }

    private fun resolveHeader(headers: Map<String, Int>, aliases: Set<String>, label: String): Int =
        optionalHeader(headers, aliases)
            ?: throw IllegalArgumentException("Workbook is missing a required column for $label")

    private fun optionalHeader(headers: Map<String, Int>, aliases: Set<String>): Int? =
        aliases.firstNotNullOfOrNull { alias -> headers[alias.normalizeHeader()] }

    private fun rowIsBlank(row: Row, formatter: DataFormatter): Boolean {
        for (cellIndex in row.firstCellNum until row.lastCellNum) {
            if (cellIndex < 0) continue
            if (formatter.formatCellValue(row.getCell(cellIndex)).isNotBlank()) {
                return false
            }
        }
        return true
    }

    private fun cellValue(row: Row, cellIndex: Int, formatter: DataFormatter): String =
        formatter.formatCellValue(row.getCell(cellIndex)).trim()

    private fun String.normalizeHeader(): String =
        trim().lowercase().replace("_", " ").replace(Regex("\\s+"), " ")

    private data class ParsedWorkbook(
        val rows: List<ImportedConceptRow>,
        val detectedColumns: List<String>
    )

    private data class ImportedConceptRow(
        val externalKey: String,
        val label: String,
        val parentExternalKey: String?,
        val description: String?,
        val address: String?,
        val uri: String?,
        val wikidataId: String?,
        val openAlexId: String?
    )
}
