-- 012-seed-crawl-configs.sql
-- Seed crawl configurations for Linux systems
-- Only runs if the crawl_config table is empty

-- Ensure NOT NULL columns have DB-level defaults so seed INSERTs can omit them.
-- (Hibernate does not create DEFAULT constraints from Kotlin field initializers.)
ALTER TABLE crawl_config ALTER COLUMN id SET DEFAULT nextval('primary_sequence');
ALTER TABLE crawl_config ALTER COLUMN date_created SET DEFAULT NOW();
ALTER TABLE crawl_config ALTER COLUMN last_updated SET DEFAULT NOW();
ALTER TABLE crawl_config ALTER COLUMN crawl_mode SET DEFAULT 'ENFORCE';
ALTER TABLE crawl_config ALTER COLUMN discovery_keeper_max_depth SET DEFAULT 20;
ALTER TABLE crawl_config ALTER COLUMN discovery_skip_max_depth SET DEFAULT 10;
ALTER TABLE crawl_config ALTER COLUMN discovery_file_sample_cap SET DEFAULT 50;
ALTER TABLE crawl_config ALTER COLUMN discovery_auto_suggest_enabled SET DEFAULT true;
ALTER TABLE crawl_config ALTER COLUMN folder_priority_skip SET DEFAULT 500;
ALTER TABLE crawl_config ALTER COLUMN folder_priority_semantic SET DEFAULT 400;
ALTER TABLE crawl_config ALTER COLUMN folder_priority_analyze SET DEFAULT 300;
ALTER TABLE crawl_config ALTER COLUMN folder_priority_index SET DEFAULT 200;
ALTER TABLE crawl_config ALTER COLUMN folder_priority_locate SET DEFAULT 100;
ALTER TABLE crawl_config ALTER COLUMN file_priority_skip SET DEFAULT 500;
ALTER TABLE crawl_config ALTER COLUMN file_priority_semantic SET DEFAULT 400;
ALTER TABLE crawl_config ALTER COLUMN file_priority_analyze SET DEFAULT 300;
ALTER TABLE crawl_config ALTER COLUMN file_priority_index SET DEFAULT 200;
ALTER TABLE crawl_config ALTER COLUMN file_priority_locate SET DEFAULT 100;

-- Check if table is empty before inserting
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM crawl_config) = 0 THEN
        -- ===== PHASE 1: USER CONTENT (Specific Directories) =====

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_DOCUMENTS',
            'USER_DOCUMENTS',
            'Crawl Config: User Documents',
            'User document files (Documents folder)',
            'NEW', 'LOCATE',
            ARRAY['/home/sean/Documents'],
            20, false, true, 1, false,
            NULL,
            NULL,
            '[".*"]',
            NULL,
            NULL,
            NULL,
            NULL,
            '[".*\\.(md|txt|org|rst|adoc)$",".*/README.*$",".*/CHANGELOG.*$",".*\\.(pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$"]'
        );

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_PICTURES',
            'USER_PICTURES',
            'Crawl Config: User Pictures',
            'User image files (Pictures folder)',
            'NEW', 'LOCATE',
            ARRAY['/home/sean/Pictures'],
            15, false, false, 1, false,
            '[".*/\\.thumbnails/.*",".*/cache/.*",".*/\\.cache/.*"]',
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*\\.(jpg|jpeg|png|gif|bmp|svg|webp|heic|heif|tiff|tif|raw|cr2|nef|arw|dng)$",".*\\.(ico)$"]',
            '[".*\\.(xmp|thm|nfo)$"]',
            NULL
        );

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_VIDEOS',
            'USER_VIDEOS',
            'Crawl Config: User Videos',
            'User video files (Videos folder)',
            'NEW', 'LOCATE',
            ARRAY['/home/sean/Videos'],
            10, false, false, 1, false,
            '[".*/\\.thumbnails/.*",".*/cache/.*"]',
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*\\.(mp4|avi|mov|mkv|flv|wmv|webm|m4v|mpeg|mpg|3gp|ogv)$"]',
            '[".*\\.(srt|sub|vtt|ass|ssa|nfo)$"]',
            NULL
        );

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_MUSIC',
            'USER_MUSIC',
            'Crawl Config: User Music',
            'User audio files (Music folder)',
            'NEW', 'LOCATE',
            ARRAY['/home/sean/Music'],
            10, false, false, 1, false,
            NULL,
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*\\.(mp3|flac|wav|aac|ogg|m4a|wma|opus|ape)$"]',
            '[".*\\.(m3u|m3u8|pls|cue|nfo)$"]',
            NULL
        );

        -- ===== PHASE 2: USER HOME (General) =====

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_HOME_GENERAL',
            'USER_HOME_GENERAL',
            'Crawl Config: User Home (General)',
            'General user home directory (excluding specific folders)',
            'NEW', 'LOCATE',
            ARRAY['/home/sean'],
            20, false, true, 1, false,
            '["/home/sean/Documents/.*","/home/sean/Pictures/.*","/home/sean/Videos/.*","/home/sean/Music/.*","/home/sean/Downloads/.*","/home/sean/Desktop/.*",".*/\\.cache/.*",".*/\\.local/share/Trash/.*",".*/\\.thumbnails/.*",".*/snap/.*/.*"]',
            '[".*"]',
            NULL,
            NULL,
            '[".*\\.(iso|dmg|exe|msi|app|deb|rpm|pkg|flatpak|snap)$"]',
            '[".*\\.(zip|tar|gz|bz2|7z|rar|xz)$",".*\\.(so|a|o)$"]',
            '[".*\\.(kt|java|py|js|ts|go|rs|c|cpp|h|hpp|sh|bash|fish|zsh)$",".*\\.(xml|json|ya?ml|toml|properties|conf|config|ini)$",".*/\\.bashrc$",".*/\\.zshrc$",".*/\\.profile$",".*/\\.gitconfig$"]',
            '[".*\\.(md|txt|org|rst|adoc)$",".*\\.(pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$"]'
        );

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_DOWNLOADS',
            'USER_DOWNLOADS',
            'Crawl Config: User Downloads',
            'User downloads folder',
            'NEW', 'LOCATE',
            ARRAY['/opt/Downloads'],
            5, false, true, 1, false,
            NULL,
            NULL,
            '[".*"]',
            NULL,
            '[".*\\.(iso|dmg|exe|msi|app|deb|rpm|pkg)$"]',
            NULL,
            NULL,
            '[".*\\.(pdf|txt|md|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$"]'
        );

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_DESKTOP',
            'USER_DESKTOP',
            'Crawl Config: User Desktop',
            'User desktop folder',
            'NEW', 'LOCATE',
            ARRAY['/home/sean/Desktop'],
            5, false, false, 1, false,
            NULL,
            NULL,
            '[".*"]',
            NULL,
            NULL,
            NULL,
            NULL,
            '[".*\\.(txt|md|pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$"]'
        );

        -- ===== PHASE 3: WORK =====

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:WORK',
            'WORK',
            'Crawl Config: Work Projects',
            'Work projects directory (/opt/work)',
            'NEW', 'LOCATE',
            ARRAY['/opt/work'],
            12, false, true, 1, false,
            NULL,
            NULL,
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*\\.(kt|kts|java|scala)$",".*\\.(py|pyi)$",".*\\.(js|ts|jsx|tsx|mjs|cjs|vue|svelte)$",".*\\.(go)$",".*\\.(rs)$",".*\\.(c|h|cpp|hpp|cc|cxx|hxx)$",".*\\.(rb|rake)$",".*\\.(php)$",".*\\.(sh|bash|zsh|fish)$",".*\\.(cs|fs|vb)$",".*\\.(swift|m|mm)$",".*\\.(lua|r|R|sql|pl|perl)$",".*\\.(xml|json|ya?ml|toml|properties|conf|config|ini)$",".*\\.env\\.example$",".*/Dockerfile.*$",".*/docker-compose.*\\.ya?ml$",".*/(Makefile|Rakefile|Gemfile|CMakeLists\\.txt)$",".*/(package\\.json|composer\\.json|requirements\\.txt|Pipfile)$",".*/(build\\.gradle.*|settings\\.gradle.*|pom\\.xml)$",".*/(Cargo\\.toml|Cargo\\.lock|go\\.mod|go\\.sum)$",".*\\.(md|rst|adoc|txt)$"]',
            '[".*\\.(pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$",".*\\.(epub|mobi)$",".*\\.html?$",".*/(README|CONTRIBUTING|CHANGELOG|LICENSE|AUTHORS|NOTICE|TODO).*$",".*\\.(md|txt|adoc|rst)$"]'
        );

        -- ===== PHASE 4: LOGGING =====

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_LOGS',
            'SYSTEM_LOGS',
            'Crawl Config: System Logs',
            'System log files (/var/log)',
            'NEW', 'LOCATE',
            ARRAY['/var/log'],
            8, false, false, 1, false,
            '[".*/journal/.*"]',
            NULL,
            '[".*"]',
            NULL,
            '[".*\\.(gz|bz2|xz)$"]',
            NULL,
            '[".*\\.log$",".*\\.log\\.[0-9]+$",".*/syslog.*",".*/kern\\.log.*",".*/auth\\.log.*",".*/daemon\\.log.*",".*/messages.*"]',
            NULL
        );

        -- ===== PHASE 5: OS CONFIGS =====

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_CONFIG',
            'SYSTEM_CONFIG',
            'Crawl Config: System Configuration (/etc)',
            'System configuration files (/etc)',
            'NEW', 'LOCATE',
            ARRAY['/etc'],
            5, false, false, 1, false,
            '[".*/ssl/.*",".*/pki/.*",".*/ssh/.*",".*/openvpn/.*"]',
            '[".*"]',
            NULL,
            NULL,
            '[".*/shadow$",".*/shadow-$",".*/passwd-$",".*/gshadow.*$",".*\\.key$",".*\\.pem$",".*\\.crt$",".*\\.pub$"]',
            NULL,
            '[".*\\.conf$",".*\\.cfg$",".*\\.config$",".*\\.ini$",".*\\.service$",".*\\.socket$",".*\\.timer$",".*\\.target$",".*\\.mount$",".*/.*\\.d/.*$",".*/hosts$",".*/hostname$",".*/fstab$",".*/crontab.*",".*/profile.*",".*/bashrc.*",".*/resolv\\.conf$",".*/environment$",".*/apt/sources\\.list.*",".*/network/interfaces.*"]',
            NULL
        );

        -- ===== PHASE 6: SYSTEM DIRECTORIES =====

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_USR',
            'SYSTEM_USR',
            'Crawl Config: System /usr',
            'System /usr directory',
            'NEW', 'LOCATE',
            ARRAY['/usr'],
            6, false, false, 1, false,
            '["/usr/lib/.*","/usr/lib64/.*","/usr/libexec/.*"]',
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*"]',
            '["/usr/share/man/.*","/usr/share/doc/.*\\.(txt|md|html?)$"]',
            NULL
        );

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_OPT',
            'SYSTEM_OPT',
            'Crawl Config: System /opt',
            'System /opt directory (excluding /opt/work)',
            'NEW', 'LOCATE',
            ARRAY['/opt'],
            8, false, false, 1, false,
            '["/opt/work/.*"]',
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*"]',
            NULL,
            NULL
        );

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_VAR',
            'SYSTEM_VAR',
            'Crawl Config: System /var',
            'System /var directory',
            'NEW', 'LOCATE',
            ARRAY['/var'],
            6, false, false, 1, false,
            '["/var/cache/.*","/var/tmp/.*","/var/lib/docker/.*","/var/lib/flatpak/.*","/var/snap/.*"]',
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*"]',
            NULL,
            NULL
        );

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_BOOT',
            'SYSTEM_BOOT',
            'Crawl Config: System /boot',
            'System boot directory',
            'NEW', 'LOCATE',
            ARRAY['/boot'],
            3, false, false, 1, false,
            NULL,
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*"]',
            '[".*/grub\\.cfg$",".*/grub\\.conf$",".*\\.conf$"]',
            NULL
        );

        -- ===== PHASE 7: EVERYTHING ELSE (Root Filesystem) =====

        INSERT INTO crawl_config (
            uri, name, label, description, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_ROOT',
            'SYSTEM_ROOT',
            'Crawl Config: Root Filesystem',
            'Root filesystem (catches anything missed by other configs)',
            'NEW', 'LOCATE',
            ARRAY['/'],
            20, false, false, 1, false,
            '["/home/sean/.*","/opt/work/.*","/etc/.*","/var/log/.*","/usr/.*","/boot/.*","/proc/.*","/sys/.*","/dev/.*","/tmp/.*","/run/.*","/mnt/.*","/media/.*","/lost\\+found/.*","/snap/.*","/home/(?!sean).*","/root/.*"]',
            '[".*"]',
            NULL,
            NULL,
            NULL,
            '[".*"]',
            NULL,
            NULL
        );

        -- Ensure seeded configs are visibly attributable in host-grouped UIs.
        UPDATE crawl_config
        SET source_host = 'seed'
        WHERE source_host IS NULL;

        RAISE NOTICE 'Seeded 15 crawl configurations for Linux';
    ELSE
        RAISE NOTICE 'Crawl config table already contains data. Skipping seed.';
    END IF;
END $$;
