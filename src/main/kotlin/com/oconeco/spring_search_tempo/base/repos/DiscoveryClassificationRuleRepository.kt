package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.DiscoveryClassificationRule
import org.springframework.data.jpa.repository.JpaRepository

interface DiscoveryClassificationRuleRepository : JpaRepository<DiscoveryClassificationRule, Long> {
    fun findByEnabledTrueOrderByRuleGroupAscIdAsc(): List<DiscoveryClassificationRule>
}

