package com.oconeco.spring_search_tempo.base.domain

/**
 * Email categories for automatic classification.
 *
 * Categories are assigned by heuristic rules based on sender domain,
 * subject patterns, and content analysis.
 */
enum class EmailCategory {
    /**
     * Personal emails from friends, family, and personal contacts.
     */
    PERSONAL,

    /**
     * Social network notifications (Facebook, LinkedIn, Twitter, Instagram, etc.)
     */
    SOCIAL,

    /**
     * Marketing emails, newsletters, and promotional content.
     */
    PROMOTION,

    /**
     * Work-related emails including internal company communications,
     * calendar invites, and work tool notifications (Jira, Slack, Teams).
     */
    WORK,

    /**
     * Transactional updates: order confirmations, shipping notifications,
     * bank statements, GitHub notifications, etc.
     */
    UPDATES,

    /**
     * Suspected spam or unwanted email.
     */
    SPAM,

    /**
     * Default category when no classification rules match.
     */
    UNCATEGORIZED
}
