package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.service.mirror.MirrorRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MirrorConfiguration {

    /**
     * Process-wide rate limiter shared by every `ImapMirrorService` call.
     * One bucket per `mirrorConfigId` keeps per-destination throttles
     * isolated (Gmail-rate-limited config doesn't slow down a Fastmail one).
     */
    @Bean
    fun mirrorRateLimiter(): MirrorRateLimiter = MirrorRateLimiter()
}
