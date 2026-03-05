-- Migration 035: Add dynamic discovery classification rule table
-- Allows DB-driven ADD/REMOVE rule tokens used by DiscoveryTemplateClassifier.

CREATE TABLE IF NOT EXISTS discovery_classification_rule (
    id BIGINT PRIMARY KEY,
    rule_group VARCHAR(50) NOT NULL,
    operation VARCHAR(10) NOT NULL DEFAULT 'ADD',
    match_value VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    note VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_discovery_rule_enabled
    ON discovery_classification_rule(enabled);

CREATE INDEX IF NOT EXISTS idx_discovery_rule_group
    ON discovery_classification_rule(rule_group);

COMMENT ON TABLE discovery_classification_rule IS
    'Dynamic discovery classifier rules; ADD/REMOVE tokens from built-in template defaults';

COMMENT ON COLUMN discovery_classification_rule.rule_group IS
    'DiscoveryRuleGroup enum name (e.g., OFFICE_HINT, PROGRAMMER_ARTIFACT, WINDOWS_SKIP)';

COMMENT ON COLUMN discovery_classification_rule.operation IS
    'ADD or REMOVE token operation against built-in defaults';

COMMENT ON COLUMN discovery_classification_rule.match_value IS
    'Lower-cased folder-name token used for exact folder-name matching';

