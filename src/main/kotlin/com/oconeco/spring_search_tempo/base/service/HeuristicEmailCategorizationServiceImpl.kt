package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.CategorizationResult
import com.oconeco.spring_search_tempo.base.EmailCategorizationService
import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Heuristic-based email categorization service.
 *
 * Uses rule-based classification based on sender domain, subject patterns,
 * and content indicators. This approach is transparent, tunable, and doesn't
 * require ML infrastructure.
 *
 * Rules are evaluated in priority order. First matching rule wins.
 */
@Service
class HeuristicEmailCategorizationServiceImpl(
    @Value("\${app.email.categorization.work-domains:}") workDomainsConfig: String
) : EmailCategorizationService {

    companion object {
        private val log = LoggerFactory.getLogger(HeuristicEmailCategorizationServiceImpl::class.java)

        // Social network domains
        private val SOCIAL_DOMAINS = setOf(
            "facebook.com", "facebookmail.com",
            "linkedin.com", "e.linkedin.com",
            "twitter.com", "x.com",
            "instagram.com",
            "pinterest.com",
            "reddit.com", "redditmail.com",
            "tiktok.com",
            "snapchat.com",
            "discord.com", "discordapp.com",
            "slack.com", "slack-msgs.com",  // Could be work, but default to social
            "meetup.com",
            "nextdoor.com",
            "quora.com"
        )

        // Promotion/marketing domains and patterns
        private val PROMO_DOMAINS = setOf(
            "mailchimp.com", "mailchi.mp",
            "sendgrid.net", "sendgrid.com",
            "constantcontact.com",
            "hubspot.com", "hs-mail.com",
            "salesforce.com",
            "marketing.com",
            "promo.com",
            "newsletter.com",
            "campaign.com"
        )

        // Update/transactional domains
        private val UPDATE_DOMAINS = setOf(
            "github.com", "notifications.github.com",
            "gitlab.com",
            "bitbucket.org",
            "amazon.com", "amazon.co.uk", "email.amazon.com",
            "paypal.com",
            "ebay.com",
            "uber.com",
            "lyft.com",
            "doordash.com",
            "grubhub.com",
            "postmates.com",
            "chase.com", "email.chase.com",
            "bankofamerica.com", "ealerts.bankofamerica.com",
            "wellsfargo.com",
            "capitalone.com",
            "americanexpress.com",
            "fedex.com",
            "ups.com",
            "usps.com",
            "dhl.com",
            "dropbox.com",
            "google.com",  // Account alerts, etc.
            "apple.com",
            "microsoft.com",
            "netflix.com",
            "spotify.com",
            "steam.com", "steampowered.com"
        )

        // Spam indicators in subject
        private val SPAM_SUBJECT_PATTERNS = listOf(
            Regex("(?i)you'?ve? won", RegexOption.IGNORE_CASE),
            Regex("(?i)claim your prize", RegexOption.IGNORE_CASE),
            Regex("(?i)congratulations!? winner", RegexOption.IGNORE_CASE),
            Regex("(?i)act now", RegexOption.IGNORE_CASE),
            Regex("(?i)limited time offer", RegexOption.IGNORE_CASE),
            Regex("(?i)\\$\\d+[,\\d]* free", RegexOption.IGNORE_CASE),
            Regex("(?i)make money (fast|quick)", RegexOption.IGNORE_CASE)
        )

        // Work-related subject patterns
        private val WORK_SUBJECT_PATTERNS = listOf(
            Regex("(?i)\\b(standup|stand-up|scrum|sprint|retro)\\b"),
            Regex("(?i)\\b(meeting|sync|1:1|one-on-one)\\b"),
            Regex("(?i)\\b(review|approval|approved|rejected)\\b"),
            Regex("(?i)\\b(deadline|milestone|deliverable)\\b"),
            Regex("(?i)\\bJIRA\\b"),
            Regex("(?i)\\bPR\\s*#?\\d+"),
            Regex("(?i)\\bmerge request\\b"),
            Regex("(?i)\\bcode review\\b"),
            Regex("(?i)\\binvitation:?\\s"),  // Calendar invite
            Regex("(?i)\\bupdated invitation\\b")
        )

        // Promotion subject patterns
        private val PROMO_SUBJECT_PATTERNS = listOf(
            Regex("(?i)\\b(sale|discount|off|% off|deal)\\b"),
            Regex("(?i)\\bunsubscribe\\b"),  // Usually indicates marketing
            Regex("(?i)\\b(free shipping|free delivery)\\b"),
            Regex("(?i)\\b(newsletter|weekly digest|monthly update)\\b"),
            Regex("(?i)\\b(don't miss|last chance|ends? (today|soon))\\b")
        )

        // Update subject patterns
        private val UPDATE_SUBJECT_PATTERNS = listOf(
            Regex("(?i)\\border\\s*(confirmation|shipped|delivered)\\b"),
            Regex("(?i)\\bshipping (update|notification)\\b"),
            Regex("(?i)\\breceipt for\\b"),
            Regex("(?i)\\bpayment (received|processed|confirmed)\\b"),
            Regex("(?i)\\b(security alert|account alert|password)\\b"),
            Regex("(?i)\\b\\[.*\\]\\s*(issue|PR|commit|build)\\b"),  // GitHub style
            Regex("(?i)\\btwo-factor|2fa|verification code\\b")
        )
    }

    // Work domains configured via application.yml
    private val workDomains: Set<String> = workDomainsConfig
        .split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    override fun categorize(message: EmailMessageDTO): CategorizationResult {
        val from = message.fromAddress?.lowercase() ?: ""
        val subject = message.subject ?: ""
        val body = message.bodyText ?: ""
        val domain = extractDomain(from)

        log.trace("Categorizing message from {} with subject: {}", from, subject)

        // Rule 1: Check for spam indicators (highest priority)
        if (SPAM_SUBJECT_PATTERNS.any { it.containsMatchIn(subject) }) {
            return CategorizationResult(EmailCategory.SPAM, 0.85, "Spam pattern in subject")
        }

        // Rule 2: Check for social network domains
        if (domain != null && SOCIAL_DOMAINS.any { domain.endsWith(it) }) {
            return CategorizationResult(EmailCategory.SOCIAL, 0.95, "Social network domain: $domain")
        }

        // Rule 3: Check for configured work domains
        if (domain != null && workDomains.any { domain.endsWith(it) }) {
            return CategorizationResult(EmailCategory.WORK, 0.95, "Configured work domain: $domain")
        }

        // Rule 4: Check for work subject patterns
        if (WORK_SUBJECT_PATTERNS.any { it.containsMatchIn(subject) }) {
            return CategorizationResult(EmailCategory.WORK, 0.80, "Work pattern in subject")
        }

        // Rule 5: Check for update domains
        if (domain != null && UPDATE_DOMAINS.any { domain.endsWith(it) }) {
            return CategorizationResult(EmailCategory.UPDATES, 0.90, "Update domain: $domain")
        }

        // Rule 6: Check for update subject patterns
        if (UPDATE_SUBJECT_PATTERNS.any { it.containsMatchIn(subject) }) {
            return CategorizationResult(EmailCategory.UPDATES, 0.80, "Update pattern in subject")
        }

        // Rule 7: Check for promo domains
        if (domain != null && PROMO_DOMAINS.any { domain.endsWith(it) }) {
            return CategorizationResult(EmailCategory.PROMOTION, 0.90, "Marketing platform domain: $domain")
        }

        // Rule 8: Check for promo subject patterns
        if (PROMO_SUBJECT_PATTERNS.any { it.containsMatchIn(subject) }) {
            return CategorizationResult(EmailCategory.PROMOTION, 0.75, "Promotion pattern in subject")
        }

        // Rule 9: Check for "unsubscribe" in body (common in marketing emails)
        if (body.contains("unsubscribe", ignoreCase = true)) {
            return CategorizationResult(EmailCategory.PROMOTION, 0.60, "Contains unsubscribe link")
        }

        // Rule 10: If no rules matched, default to personal (could be refined further)
        // Low confidence since this is a fallback
        return CategorizationResult(EmailCategory.PERSONAL, 0.40, "No specific rules matched")
    }

    override fun categorizeBatch(messages: List<EmailMessageDTO>): Map<Long, CategorizationResult> {
        return messages.associate { message ->
            val id = message.id ?: throw IllegalArgumentException("Message must have an ID")
            id to categorize(message)
        }
    }

    /**
     * Extract the domain from an email address.
     * e.g., "John Doe <john@example.com>" -> "example.com"
     */
    private fun extractDomain(emailAddress: String): String? {
        // Handle "Name <email@domain.com>" format
        val emailPart = if (emailAddress.contains("<")) {
            emailAddress.substringAfter("<").substringBefore(">")
        } else {
            emailAddress
        }

        return if (emailPart.contains("@")) {
            emailPart.substringAfter("@").trim().lowercase()
        } else {
            null
        }
    }
}
