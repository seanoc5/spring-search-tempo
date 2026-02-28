DELETE FROM fsfile;

DELETE FROM content_chunks;

DELETE FROM fsfolder;

DELETE FROM annotation;

-- spring_role must be deleted before spring_user (FK constraint)
DELETE FROM spring_role;

DELETE FROM spring_user;
