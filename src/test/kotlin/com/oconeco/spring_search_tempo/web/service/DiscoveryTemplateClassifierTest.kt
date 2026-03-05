package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.domain.DiscoveryClassificationRule
import com.oconeco.spring_search_tempo.base.domain.DiscoveryRuleGroup
import com.oconeco.spring_search_tempo.base.domain.DiscoveryRuleOperation
import com.oconeco.spring_search_tempo.base.domain.SuggestedStatus
import com.oconeco.spring_search_tempo.base.repos.DiscoveryClassificationRuleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class DiscoveryTemplateClassifierTest {

    private val classifier = DiscoveryTemplateClassifier()

    @Test
    fun `guesses programmer and skips third party artifacts`() {
        val plan = classifier.buildPlan(
            osType = "LINUX",
            rootPaths = listOf("/home/sean"),
            folders = listOf(
                TemplateFolderInput("/home/sean/projects/app/src", "src", 3),
                TemplateFolderInput("/home/sean/projects/app/node_modules", "node_modules", 3),
                TemplateFolderInput("/home/sean/Documents", "Documents", 1)
            )
        )

        assertEquals(DiscoveryUserProfile.PROGRAMMER, plan.profile)
        assertEquals(SuggestedStatus.SKIP, plan.statusByPath["/home/sean/projects/app/node_modules"])
        assertEquals(SuggestedStatus.ANALYZE, plan.statusByPath["/home/sean/Documents"])
    }

    @Test
    fun `guesses manager and skips windows temp`() {
        val plan = classifier.buildPlan(
            osType = "WINDOWS",
            rootPaths = listOf("C:\\Users\\Alice"),
            folders = listOf(
                TemplateFolderInput("C:\\Users\\Alice\\Documents", "Documents", 3),
                TemplateFolderInput("C:\\Users\\Alice\\Reports", "Reports", 3),
                TemplateFolderInput("C:\\Users\\Alice\\AppData\\Local\\Temp", "Temp", 5)
            )
        )

        assertEquals(DiscoveryUserProfile.MANAGER, plan.profile)
        assertEquals(SuggestedStatus.SKIP, plan.statusByPath["C:\\Users\\Alice\\AppData\\Local\\Temp"])
        assertEquals(SuggestedStatus.ANALYZE, plan.statusByPath["C:\\Users\\Alice\\Documents"])
    }

    @Test
    fun `falls back to power user and indexes media`() {
        val plan = classifier.buildPlan(
            osType = "MACOS",
            rootPaths = listOf("/Users/sean"),
            folders = listOf(
                TemplateFolderInput("/Users/sean/Documents", "Documents", 3),
                TemplateFolderInput("/Users/sean/Music", "Music", 3),
                TemplateFolderInput("/Users/sean/Automation", "Automation", 3)
            )
        )

        assertEquals(DiscoveryUserProfile.POWER_USER, plan.profile)
        assertEquals(SuggestedStatus.ANALYZE, plan.statusByPath["/Users/sean/Documents"])
        assertEquals(SuggestedStatus.INDEX, plan.statusByPath["/Users/sean/Music"])
    }

    @Test
    fun `media child overrides office parent`() {
        val plan = classifier.buildPlan(
            osType = "linux",
            rootPaths = listOf("/home/sean"),
            folders = listOf(
                TemplateFolderInput("/home/sean/Documents", "Documents", 2),
                TemplateFolderInput("/home/sean/Documents/Pictures", "Pictures", 3)
            )
        )

        assertEquals(SuggestedStatus.ANALYZE, plan.statusByPath["/home/sean/Documents"])
        assertEquals(SuggestedStatus.INDEX, plan.statusByPath["/home/sean/Documents/Pictures"])
    }

    @Test
    fun `win11 os label still applies windows skip rules`() {
        val plan = classifier.buildPlan(
            osType = "Win11",
            rootPaths = listOf("C:\\"),
            folders = listOf(
                TemplateFolderInput("C:\\Windows\\WinSxS", "WinSxS", 2),
                TemplateFolderInput("C:\\Users\\Sean\\Downloads", "Downloads", 3)
            )
        )

        assertEquals(SuggestedStatus.SKIP, plan.statusByPath["C:\\Windows\\WinSxS"])
        assertEquals(SuggestedStatus.LOCATE, plan.statusByPath["C:\\Users\\Sean\\Downloads"])
    }

    @Test
    fun `db rules can add and remove default office hints`() {
        val repo = mock(DiscoveryClassificationRuleRepository::class.java)
        val classifierWithDbRules = DiscoveryTemplateClassifier(repo)

        val removeDocuments = DiscoveryClassificationRule().apply {
            ruleGroup = DiscoveryRuleGroup.OFFICE_HINT
            operation = DiscoveryRuleOperation.REMOVE
            matchValue = "documents"
        }
        val addProposals = DiscoveryClassificationRule().apply {
            ruleGroup = DiscoveryRuleGroup.OFFICE_HINT
            operation = DiscoveryRuleOperation.ADD
            matchValue = "proposals"
        }
        val removeDocumentsFromPowerUserAnalyze = DiscoveryClassificationRule().apply {
            ruleGroup = DiscoveryRuleGroup.POWER_USER_ANALYZE
            operation = DiscoveryRuleOperation.REMOVE
            matchValue = "documents"
        }
        val removeDocumentsFromManagerAnalyze = DiscoveryClassificationRule().apply {
            ruleGroup = DiscoveryRuleGroup.MANAGER_ANALYZE
            operation = DiscoveryRuleOperation.REMOVE
            matchValue = "documents"
        }

        `when`(repo.findByEnabledTrueOrderByRuleGroupAscIdAsc())
            .thenReturn(
                listOf(
                    removeDocuments,
                    addProposals,
                    removeDocumentsFromPowerUserAnalyze,
                    removeDocumentsFromManagerAnalyze
                )
            )

        val plan = classifierWithDbRules.buildPlan(
            osType = "LINUX",
            rootPaths = listOf("/home/sean"),
            folders = listOf(
                TemplateFolderInput("/home/sean/Documents", "Documents", 2),
                TemplateFolderInput("/home/sean/Proposals", "Proposals", 2)
            )
        )

        assertEquals(SuggestedStatus.LOCATE, plan.statusByPath["/home/sean/Documents"])
        assertEquals(SuggestedStatus.ANALYZE, plan.statusByPath["/home/sean/Proposals"])
    }
}
