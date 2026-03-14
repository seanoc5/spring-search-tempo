-- 012-seed-crawl-configs.sql
-- Seed crawl configurations for Linux systems
-- Only runs if the crawl_config table is empty

-- Check if table is empty before inserting
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM crawl_config) = 0 THEN
        -- ===== PHASE 1: USER CONTENT (Specific Directories) =====

        INSERT INTO crawl_config (
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_DOCUMENTS',
            'USER_DOCUMENTS',
            'User Documents',
            'Crawl Config: User Documents',
            'User document files (Documents folder)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_PICTURES',
            'USER_PICTURES',
            'User Pictures',
            'Crawl Config: User Pictures',
            'User image files (Pictures folder)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_VIDEOS',
            'USER_VIDEOS',
            'User Videos',
            'Crawl Config: User Videos',
            'User video files (Videos folder)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_MUSIC',
            'USER_MUSIC',
            'User Music',
            'Crawl Config: User Music',
            'User audio files (Music folder)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_HOME_GENERAL',
            'USER_HOME_GENERAL',
            'User Home (General)',
            'Crawl Config: User Home (General)',
            'General user home directory (excluding specific folders)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_DOWNLOADS',
            'USER_DOWNLOADS',
            'User Downloads',
            'Crawl Config: User Downloads',
            'User downloads folder',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:USER_DESKTOP',
            'USER_DESKTOP',
            'User Desktop',
            'Crawl Config: User Desktop',
            'User desktop folder',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:WORK',
            'WORK',
            'Work Projects',
            'Crawl Config: Work Projects',
            'Work projects directory (/opt/work)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_LOGS',
            'SYSTEM_LOGS',
            'System Logs',
            'Crawl Config: System Logs',
            'System log files (/var/log)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_CONFIG',
            'SYSTEM_CONFIG',
            'System Configuration (/etc)',
            'Crawl Config: System Configuration (/etc)',
            'System configuration files (/etc)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_USR',
            'SYSTEM_USR',
            'System /usr',
            'Crawl Config: System /usr',
            'System /usr directory',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_OPT',
            'SYSTEM_OPT',
            'System /opt',
            'Crawl Config: System /opt',
            'System /opt directory (excluding /opt/work)',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_VAR',
            'SYSTEM_VAR',
            'System /var',
            'Crawl Config: System /var',
            'System /var directory',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_BOOT',
            'SYSTEM_BOOT',
            'System /boot',
            'Crawl Config: System /boot',
            'System boot directory',
            true, 'NEW', 'LOCATE',
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
            uri, name, display_label, label, description, enabled, status, analysis_status,
            start_paths, max_depth, follow_links, parallel, version, archived,
            folder_patterns_skip, folder_patterns_locate, folder_patterns_index, folder_patterns_analyze,
            file_patterns_skip, file_patterns_locate, file_patterns_index, file_patterns_analyze
        ) VALUES (
            'crawl-config:SYSTEM_ROOT',
            'SYSTEM_ROOT',
            'Root Filesystem',
            'Crawl Config: Root Filesystem',
            'Root filesystem (catches anything missed by other configs)',
            true, 'NEW', 'LOCATE',
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
