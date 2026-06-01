DELETE FROM host_crawl_session_folder;

DELETE FROM host_crawl_session;

DELETE FROM remote_crawl_task;

DELETE FROM crawl_discovery_file_sample;

DELETE FROM crawl_discovery_folder_obs;

DELETE FROM crawl_discovery_run;

DELETE FROM discovered_folder;

DELETE FROM discovery_session;

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

-- spring_role must be deleted before spring_user (FK constraint)
DELETE FROM spring_role;

DELETE FROM spring_user;
