package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.domain.ConceptHierarchy
import com.oconeco.spring_search_tempo.base.repos.ConceptHierarchyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.boot.DefaultApplicationArguments

class ConceptHierarchyInitializerTest {

    @Test
    fun `creates expected core hierarchies when missing`() {
        val repository = mock(ConceptHierarchyRepository::class.java)
        val initializer = ConceptHierarchyInitializer(repository)
        val savedCaptor = ArgumentCaptor.forClass(ConceptHierarchy::class.java)

        `when`(repository.existsByCode(anyString())).thenReturn(false)
        `when`(repository.save(any(ConceptHierarchy::class.java))).thenAnswer { it.arguments[0] }

        initializer.run(DefaultApplicationArguments(*emptyArray<String>()))

        verify(repository, times(3)).save(savedCaptor.capture())
        val saved = savedCaptor.allValues.associateBy { it.code }

        assertEquals(setOf("OCONECO", "OPENALEX_CONCEPTS", "OPENALEX_TOPICS"), saved.keys)
        assertTrue(saved.getValue("OCONECO").supportsAddress)
        assertEquals(60_000L, saved.getValue("OPENALEX_CONCEPTS").expectedNodeCount)
        assertEquals("OpenAlex Topics", saved.getValue("OPENALEX_TOPICS").label)
    }

    @Test
    fun `does not recreate hierarchies that already exist`() {
        val repository = mock(ConceptHierarchyRepository::class.java)
        val initializer = ConceptHierarchyInitializer(repository)

        `when`(repository.existsByCode(anyString())).thenReturn(true)

        initializer.run(DefaultApplicationArguments(*emptyArray<String>()))

        verify(repository, times(0)).save(any(ConceptHierarchy::class.java))
    }
}
