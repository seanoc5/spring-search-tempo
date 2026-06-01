package com.oconeco.spring_search_tempo.base.service.mirror

import org.springframework.modulith.NamedInterface
import org.springframework.modulith.PackageInfo

/**
 * Expose `base.service.mirror` (ImapMirrorService, MirrorResult, MirrorRateLimiter)
 * as part of the `service` named interface so cross-module dependents
 * (batch/mirror, web/rest) can wire the mirror service the same way they
 * wire any other public service in `base.service`.
 */
@PackageInfo
@NamedInterface("service")
class ModuleMetadata
