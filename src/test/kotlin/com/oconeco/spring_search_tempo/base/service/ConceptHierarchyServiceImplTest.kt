package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.ConceptHierarchy
import com.oconeco.spring_search_tempo.base.domain.ConceptNode
import com.oconeco.spring_search_tempo.base.repos.ConceptHierarchyRepository
import com.oconeco.spring_search_tempo.base.repos.ConceptNodeRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class ConceptHierarchyServiceImplTest {

    @Test
    fun `lists roots and builds node detail breadcrumbs`() {
        val hierarchyRepo = mock(ConceptHierarchyRepository::class.java)
        val nodeRepo = mock(ConceptNodeRepository::class.java)
        val service = ConceptHierarchyServiceImpl(hierarchyRepo, nodeRepo)
        val hierarchy = hierarchy("OCONECO", "OconEco")
        val root = node(1L, hierarchy, "ROOT", "Root").apply { leaf = false }
        val child = node(2L, hierarchy, "CHILD", "Child").apply {
            parent = root
            depthLevel = 1
        }
        root.path = "Root"
        child.path = "Root > Child"

        `when`(nodeRepo.findAllByHierarchyCodeAndParentIsNullAndActiveTrueOrderByLabelAsc("OCONECO"))
            .thenReturn(listOf(root))
        `when`(nodeRepo.findAllByParentIdAndActiveTrueOrderByLabelAsc(1L))
            .thenReturn(listOf(child))
        `when`(nodeRepo.findAllByParentIdAndActiveTrueOrderByLabelAsc(2L))
            .thenReturn(emptyList())
        `when`(nodeRepo.findWithHierarchyAndParentById(2L)).thenReturn(child)
        `when`(nodeRepo.findWithHierarchyAndParentById(1L)).thenReturn(root)

        val roots = service.getRootNodes("OCONECO")
        val detail = service.getNodeDetail(2L)

        assertEquals(1, roots.size)
        assertEquals("ROOT", roots.first().externalKey)
        assertEquals(1, roots.first().childCount)
        assertEquals(listOf("Root", "Child"), detail.breadcrumbs.map { it.label })
        assertEquals("Root > Child", detail.node.path)
        assertTrue(detail.children.isEmpty())
    }

    @Test
    fun `searches in current hierarchy or globally`() {
        val hierarchyRepo = mock(ConceptHierarchyRepository::class.java)
        val nodeRepo = mock(ConceptNodeRepository::class.java)
        val service = ConceptHierarchyServiceImpl(hierarchyRepo, nodeRepo)
        val oconeco = hierarchy("OCONECO", "OconEco")
        val topics = hierarchy("OPENALEX_TOPICS", "OpenAlex Topics")
        val localMatch = node(1L, oconeco, "ROOT", "Climate").apply { path = "Root > Climate" }
        val globalMatch = node(2L, topics, "T1", "Climate Change").apply { path = "Topics > Climate Change" }

        `when`(nodeRepo.searchActiveInHierarchy("OCONECO", "climate")).thenReturn(listOf(localMatch))
        `when`(nodeRepo.searchActive("climate")).thenReturn(listOf(localMatch, globalMatch))

        val scoped = service.search("climate", "OCONECO")
        val global = service.search("climate", null)

        assertEquals(listOf("OCONECO"), scoped.map { it.hierarchyCode })
        assertEquals(listOf("OCONECO", "OPENALEX_TOPICS"), global.map { it.hierarchyCode })
    }

    @Test
    fun `imports oconeco workbook with alias headers and tree metadata`() {
        val hierarchyRepo = mock(ConceptHierarchyRepository::class.java)
        val nodeRepo = mock(ConceptNodeRepository::class.java)
        val service = ConceptHierarchyServiceImpl(hierarchyRepo, nodeRepo)
        val hierarchy = ConceptHierarchy().apply {
            id = 1L
            code = "OCONECO"
            uri = "concept-hierarchy:oconeco"
            label = "OconEco"
        }
        val savedSnapshots = mutableListOf<List<ConceptNode>>()

        `when`(hierarchyRepo.findByCode("OCONECO")).thenReturn(hierarchy)
        `when`(nodeRepo.findAllByHierarchyId(anyLong())).thenReturn(emptyList())
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val nodes = (invocation.arguments[0] as Iterable<ConceptNode>).toList()
            savedSnapshots += nodes
            nodes
        }.`when`(nodeRepo).saveAll(org.mockito.ArgumentMatchers.anyIterable<ConceptNode>())

        val result = service.importOconecoHierarchy(
            ByteArrayInputStream(workbookBytes(
                listOf("Concept ID", "Name", "Parent ID", "Address", "Description"),
                listOf("ROOT", "Root", "", "1", "Root node"),
                listOf("CHILD", "Child", "ROOT", "1.1", "Child node")
            )),
            "oconeco.xlsx"
        )

        verify(nodeRepo, times(2)).saveAll(org.mockito.ArgumentMatchers.anyIterable<ConceptNode>())
        val savedNodes = savedSnapshots.last().associateBy { it.externalKey }

        assertEquals(2, result.importedRows)
        assertEquals(2, result.createdNodes)
        assertEquals(0, result.updatedNodes)
        assertEquals(0, result.deactivatedNodes)
        assertEquals(1, result.rootNodes)
        assertEquals("Root", savedNodes.getValue("ROOT").path)
        assertEquals(0, savedNodes.getValue("ROOT").depthLevel)
        assertTrue(savedNodes.getValue("ROOT").active)
        assertFalse(savedNodes.getValue("ROOT").leaf)
        assertEquals("Root > Child", savedNodes.getValue("CHILD").path)
        assertEquals(1, savedNodes.getValue("CHILD").depthLevel)
        assertTrue(savedNodes.getValue("CHILD").leaf)
        assertEquals("1.1", savedNodes.getValue("CHILD").address)
        assertEquals("ROOT", savedNodes.getValue("CHILD").parent?.externalKey)
    }

    @Test
    fun `deactivates existing nodes missing from latest import`() {
        val hierarchyRepo = mock(ConceptHierarchyRepository::class.java)
        val nodeRepo = mock(ConceptNodeRepository::class.java)
        val service = ConceptHierarchyServiceImpl(hierarchyRepo, nodeRepo)
        val hierarchy = ConceptHierarchy().apply {
            id = 1L
            code = "OCONECO"
            uri = "concept-hierarchy:oconeco"
            label = "OconEco"
        }
        val existingRoot = ConceptNode().apply {
            id = 10L
            this.hierarchy = hierarchy
            externalKey = "ROOT"
            label = "Old Root"
            uri = "concept-node:oconeco:ROOT"
            active = true
        }
        val stale = ConceptNode().apply {
            id = 11L
            this.hierarchy = hierarchy
            externalKey = "STALE"
            label = "Stale"
            uri = "concept-node:oconeco:STALE"
            active = true
        }
        val savedSnapshots = mutableListOf<List<ConceptNode>>()

        `when`(hierarchyRepo.findByCode("OCONECO")).thenReturn(hierarchy)
        `when`(nodeRepo.findAllByHierarchyId(1L)).thenReturn(listOf(existingRoot, stale))
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val nodes = (invocation.arguments[0] as Iterable<ConceptNode>).toList()
            savedSnapshots += nodes
            nodes
        }.`when`(nodeRepo).saveAll(org.mockito.ArgumentMatchers.anyIterable<ConceptNode>())

        val result = service.importOconecoHierarchy(
            ByteArrayInputStream(workbookBytes(
                listOf("id", "label"),
                listOf("ROOT", "Updated Root")
            )),
            "oconeco.xlsx"
        )

        verify(nodeRepo, times(2)).saveAll(org.mockito.ArgumentMatchers.anyIterable<ConceptNode>())
        val finalNodes = savedSnapshots.last().associateBy { it.externalKey }

        assertEquals(1, result.updatedNodes)
        assertEquals(1, result.deactivatedNodes)
        assertTrue(finalNodes.getValue("ROOT").active)
        assertEquals("Updated Root", finalNodes.getValue("ROOT").label)
        assertFalse(finalNodes.getValue("STALE").active)
    }

    private fun workbookBytes(headers: List<String>, vararg rows: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("OconEco")
            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { index, value -> headerRow.createCell(index).setCellValue(value) }
            rows.forEachIndexed { rowIndex, row ->
                val sheetRow = sheet.createRow(rowIndex + 1)
                row.forEachIndexed { cellIndex, value -> sheetRow.createCell(cellIndex).setCellValue(value) }
            }
            workbook.write(output)
        }
        return output.toByteArray()
    }

    private fun hierarchy(code: String, label: String): ConceptHierarchy =
        ConceptHierarchy().apply {
            id = if (code == "OCONECO") 1L else 2L
            this.code = code
            uri = "concept-hierarchy:${code.lowercase()}"
            this.label = label
        }

    private fun node(id: Long, hierarchy: ConceptHierarchy, key: String, label: String): ConceptNode =
        ConceptNode().apply {
            this.id = id
            this.hierarchy = hierarchy
            externalKey = key
            this.label = label
            uri = "concept-node:${requireNotNull(hierarchy.code).lowercase()}:$key"
            active = true
            leaf = true
        }
}
