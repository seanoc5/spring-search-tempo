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
}
