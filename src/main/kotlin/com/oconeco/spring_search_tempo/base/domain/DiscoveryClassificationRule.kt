package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.*

/**
 * Dynamic classifier rule that can add/remove folder-name match tokens
 * from the built-in discovery template defaults.
 */
@Entity
@Table(
    name = "discovery_classification_rule",
    indexes = [
        Index(name = "idx_discovery_rule_enabled", columnList = "enabled"),
        Index(name = "idx_discovery_rule_group", columnList = "rule_group")
    ]
)
class DiscoveryClassificationRule {

    @Id
    @SequenceGenerator(
        name = "discovery_classification_rule_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "discovery_classification_rule_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_group", nullable = false, length = 50)
    var ruleGroup: DiscoveryRuleGroup = DiscoveryRuleGroup.OFFICE_HINT

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var operation: DiscoveryRuleOperation = DiscoveryRuleOperation.ADD

    @Column(name = "match_value", nullable = false, length = 255)
    var matchValue: String = ""

    @Column(nullable = false)
    var enabled: Boolean = true

    @Column(length = 255)
    var note: String? = null
}

enum class DiscoveryRuleOperation {
    ADD,
    REMOVE
}

enum class DiscoveryRuleGroup {
    OFFICE_HINT,
    MEDIA_HINT,
    MANUAL_HELP_HINT,
    CONFIG_OR_LOG_HINT,

    PROGRAMMER_HINT,
    MANAGER_HINT,
    POWER_USER_HINT,

    PROGRAMMER_ARTIFACT,
    PROGRAMMER_ANALYZE,
    PROGRAMMER_LOCATE,

    MANAGER_ANALYZE,
    MANAGER_SKIP,
    MANAGER_LOCATE,

    POWER_USER_ANALYZE,
    POWER_USER_SKIP,
    POWER_USER_LOCATE,

    WINDOWS_SKIP,
    MACOS_SKIP,
    LINUX_SKIP,

    WINDOWS_SYSTEM_ROOT,
    MACOS_SYSTEM_ROOT,
    LINUX_SYSTEM_ROOT
}

