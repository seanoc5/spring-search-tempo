package com.oconeco.spring_search_tempo.base.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Configuration for per-entry archive enumeration (issue #118).
 *
 * Bound to the `app.archive.*` namespace. Defaults are conservative enough that
 * enumeration can stay on by default without surprising large-archive cases:
 * deeply-nested archives get a SKIP with a logged reason at depth `maxRecursionDepth + 1`,
 * and oversized archives fall back to LOCATE-only treatment of the archive itself.
 */
@Configuration
@ConfigurationProperties(prefix = "app.archive")
data class ArchiveConfiguration(
    /**
     * Master switch. When false, archives are treated as opaque blobs by Tika and no
     * per-entry FSFile rows are created. Disable per-host if you need to suppress
     * archive expansion (e.g. for a slow USB drive).
     */
    var enabled: Boolean = true,

    /**
     * Maximum depth at which an archive-inside-an-archive will be enumerated.
     * Outer archive = depth 1. An archive entry that is itself an archive
     * triggers a SKIP-with-reason once it would push past this depth.
     * Issue #118 default: 2.
     */
    var maxRecursionDepth: Int = 2,

    /**
     * Maximum number of entries an archive may contain before we fall back to
     * LOCATE-only treatment. Archives over this threshold get a warning and
     * have entries elided to keep DB growth bounded. Issue #118 default: 1000.
     */
    var maxExtractedEntries: Int = 1000
)
