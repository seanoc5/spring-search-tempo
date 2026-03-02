-- Promote user-content file types to ANALYZE in seeded crawl configs.
-- This enables full pipeline processing (extract/chunk/NLP/embedding) for
-- office docs, HTML, PDF, ebooks, and other user-authored text content.

-- USER_DOCUMENTS
UPDATE crawl_config
SET
    folder_patterns_index = '[".*"]',
    folder_patterns_analyze = NULL,
    file_patterns_index = NULL,
    file_patterns_analyze = '[".*\\.(md|txt|org|rst|adoc)$",".*/README.*$",".*/CHANGELOG.*$",".*\\.(pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$"]'
WHERE name = 'USER_DOCUMENTS';

-- USER_HOME_GENERAL
UPDATE crawl_config
SET
    file_patterns_index = '[".*\\.(kt|java|py|js|ts|go|rs|c|cpp|h|hpp|sh|bash|fish|zsh)$",".*\\.(xml|json|ya?ml|toml|properties|conf|config|ini)$",".*/\\.bashrc$",".*/\\.zshrc$",".*/\\.profile$",".*/\\.gitconfig$"]',
    file_patterns_analyze = '[".*\\.(md|txt|org|rst|adoc)$",".*\\.(pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$"]'
WHERE name = 'USER_HOME_GENERAL';

-- USER_DOWNLOADS
UPDATE crawl_config
SET
    file_patterns_index = NULL,
    file_patterns_analyze = '[".*\\.(pdf|txt|md|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$"]'
WHERE name = 'USER_DOWNLOADS';

-- USER_DESKTOP
UPDATE crawl_config
SET
    file_patterns_index = NULL,
    file_patterns_analyze = '[".*\\.(txt|md|pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$"]'
WHERE name = 'USER_DESKTOP';

-- WORK
UPDATE crawl_config
SET
    file_patterns_analyze = '[".*\\.(pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$",".*/(README|CONTRIBUTING|CHANGELOG|LICENSE|AUTHORS|NOTICE|TODO).*$",".*\\.(md|txt|adoc|rst)$"]'
WHERE name = 'WORK';
