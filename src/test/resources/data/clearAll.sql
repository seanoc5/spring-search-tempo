DELETE FROM host_crawl_session_folder;

DELETE FROM host_crawl_session;

DELETE FROM remote_crawl_task;

DELETE FROM crawl_discovery_file_sample;

DELETE FROM crawl_discovery_folder_obs;

DELETE FROM crawl_discovery_run;

DELETE FROM discovered_folder;

DELETE FROM discovery_session;

-- NOTE: mirror-related tables (mirror_config, mirrored_message,
-- mirror_checkpoint, mirror_error) are intentionally NOT cleared here.
-- The shared Testcontainers PostgreSQL plus ddl-auto: create-drop leaves
-- brief windows where those tables don't exist (e.g., between a
-- @DirtiesContext close and the next context's Hibernate init); a plain
-- DELETE would fail the @Sql script for every unrelated IT in the suite.
-- Mirror-touching tests (MirrorJobIT, MirrorProgressViewIT,
-- MirrorConfigServiceTest, ImapMirrorServiceTest) explicitly call the
-- appropriate `*Repository.deleteAll()` in @BeforeEach/@AfterEach.

-- JobRun has FK -> crawl_config; must be cleared before crawl_config.
-- It also has FK -> mirror_config (issue #26), but mirror_config isn't
-- cleared in this script (see note above), so the order vs mirror_config
-- doesn't matter here — only the mirror tests delete it.
DELETE FROM job_run;

DELETE FROM crawl_config;

DELETE FROM user_source_host;

DELETE FROM concept_node;

DELETE FROM concept_hierarchy;

DELETE FROM fsfile;

DELETE FROM content_chunks;

DELETE FROM fsfolder;

DELETE FROM source_host;

DELETE FROM annotation;

DELETE FROM browser_bookmark_tags;

DELETE FROM bookmark_tag;

DELETE FROM browser_bookmark;

DELETE FROM browser_profile;

-- spring_role must be deleted before spring_user (FK constraint)
DELETE FROM spring_role;

DELETE FROM spring_user;
