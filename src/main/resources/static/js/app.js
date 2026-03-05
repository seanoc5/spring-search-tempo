/**
 * Register an event at the document for the specified selector,
 * so events are still catched after DOM changes.
 */
function handleEvent(eventType, selector, handler) {
  document.addEventListener(eventType, function(event) {
    if (event.target.matches(selector + ', ' + selector + ' *')) {
      handler.apply(event.target.closest(selector), arguments);
    }
  });
}

handleEvent('change', '.js-selectlinks', function(event) {
  htmx.ajax('get', this.value, document.body);
  history.pushState({ htmx: true }, '', this.value);
});

/* ==========================================================================
   Toast Notification System (Growler)
   ========================================================================== */

const ToastManager = {
  STORAGE_KEY: 'toast_history',
  MAX_HISTORY: 50,
  DEFAULT_DURATION: 5000,

  /**
   * Initialize toast system
   */
  init() {
    this.updateBadge();
    this.renderHistory();

    // Listen for HTMX events that contain toast data
    document.body.addEventListener('htmx:afterRequest', (evt) => {
      const xhr = evt.detail.xhr;
      const toastHeader = xhr.getResponseHeader('X-Toast-Message');
      const toastType = xhr.getResponseHeader('X-Toast-Type') || 'info';
      if (toastHeader) {
        this.show(decodeURIComponent(toastHeader), toastType);
      }
    });

    // Listen for custom toast events
    document.body.addEventListener('toast:show', (evt) => {
      const { message, type, duration } = evt.detail;
      this.show(message, type, duration);
    });
  },

  /**
   * Show a toast notification
   * @param {string} message - The message to display
   * @param {string} type - success, error, warning, info
   * @param {number} duration - Auto-hide duration in ms (0 = no auto-hide)
   */
  show(message, type = 'info', duration = this.DEFAULT_DURATION) {
    const container = document.getElementById('toastContainer');
    if (!container) return;

    const toastId = 'toast-' + Date.now();
    const bgClass = this.getBackgroundClass(type);
    const icon = this.getIcon(type);

    const toastHtml = `
      <div id="${toastId}" class="toast ${bgClass} text-white" role="alert" aria-live="assertive" aria-atomic="true">
        <div class="toast-header ${bgClass} text-white border-0">
          <i class="bi ${icon} me-2"></i>
          <strong class="me-auto">${this.getTitle(type)}</strong>
          <small class="text-white-50">just now</small>
          <button type="button" class="btn-close btn-close-white" data-bs-dismiss="toast" aria-label="Close"></button>
        </div>
        <div class="toast-body">
          ${this.escapeHtml(message)}
        </div>
      </div>
    `;

    container.insertAdjacentHTML('beforeend', toastHtml);
    const toastEl = document.getElementById(toastId);
    const toast = new bootstrap.Toast(toastEl, {
      autohide: duration > 0,
      delay: duration
    });

    // Remove from DOM after hidden
    toastEl.addEventListener('hidden.bs.toast', () => {
      toastEl.remove();
    });

    toast.show();

    // Save to history
    this.addToHistory(message, type);
  },

  /**
   * Add message to history (localStorage)
   */
  addToHistory(message, type) {
    const history = this.getHistory();
    history.unshift({
      id: Date.now(),
      message: message,
      type: type,
      timestamp: new Date().toISOString()
    });

    // Limit history size
    if (history.length > this.MAX_HISTORY) {
      history.splice(this.MAX_HISTORY);
    }

    localStorage.setItem(this.STORAGE_KEY, JSON.stringify(history));
    this.updateBadge();
    this.renderHistory();
  },

  /**
   * Get history from localStorage
   */
  getHistory() {
    try {
      return JSON.parse(localStorage.getItem(this.STORAGE_KEY)) || [];
    } catch (e) {
      return [];
    }
  },

  /**
   * Clear all history
   */
  clearHistory() {
    localStorage.removeItem(this.STORAGE_KEY);
    this.updateBadge();
    this.renderHistory();
  },

  /**
   * Update the badge count
   */
  updateBadge() {
    const badge = document.getElementById('toastHistoryBadge');
    if (!badge) return;

    const count = this.getHistory().length;
    if (count > 0) {
      badge.textContent = count > 99 ? '99+' : count;
      badge.style.display = 'block';
    } else {
      badge.style.display = 'none';
    }
  },

  /**
   * Render history in panel
   */
  renderHistory() {
    const content = document.getElementById('toastHistoryContent');
    if (!content) return;

    const history = this.getHistory();
    if (history.length === 0) {
      content.innerHTML = `
        <div class="toast-history-empty">
          <i class="bi bi-bell-slash fs-1 d-block mb-2"></i>
          <p class="mb-0">No notifications yet</p>
        </div>
      `;
      return;
    }

    content.innerHTML = history.map(item => `
      <div class="toast-history-item type-${item.type}">
        <div class="d-flex justify-content-between align-items-start">
          <div class="timestamp">${this.formatTimestamp(item.timestamp)}</div>
          <button type="button" class="btn btn-sm btn-link text-muted p-0" onclick="ToastManager.removeFromHistory(${item.id})">
            <i class="bi bi-x"></i>
          </button>
        </div>
        <div class="message">${this.escapeHtml(item.message)}</div>
      </div>
    `).join('');
  },

  /**
   * Remove single item from history
   */
  removeFromHistory(id) {
    const history = this.getHistory().filter(item => item.id !== id);
    localStorage.setItem(this.STORAGE_KEY, JSON.stringify(history));
    this.updateBadge();
    this.renderHistory();
  },

  /**
   * Toggle history panel visibility
   */
  toggleHistoryPanel() {
    const panel = document.getElementById('toastHistoryPanel');
    if (panel) {
      panel.classList.toggle('show');
    }
  },

  /**
   * Format timestamp for display
   */
  formatTimestamp(isoString) {
    const date = new Date(isoString);
    const now = new Date();
    const diffMs = now - date;
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);

    if (diffMins < 1) return 'just now';
    if (diffMins < 60) return `${diffMins} min ago`;
    if (diffHours < 24) return `${diffHours} hr ago`;
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  },

  /**
   * Get Bootstrap background class for type
   */
  getBackgroundClass(type) {
    const classes = {
      success: 'bg-success',
      error: 'bg-danger',
      warning: 'bg-warning text-dark',
      info: 'bg-info'
    };
    return classes[type] || classes.info;
  },

  /**
   * Get icon for type
   */
  getIcon(type) {
    const icons = {
      success: 'bi-check-circle-fill',
      error: 'bi-exclamation-triangle-fill',
      warning: 'bi-exclamation-circle-fill',
      info: 'bi-info-circle-fill'
    };
    return icons[type] || icons.info;
  },

  /**
   * Get title for type
   */
  getTitle(type) {
    const titles = {
      success: 'Success',
      error: 'Error',
      warning: 'Warning',
      info: 'Info'
    };
    return titles[type] || titles.info;
  },

  /**
   * Escape HTML to prevent XSS
   */
  escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }
};

/* ==========================================================================
   Global Loading Overlay
   ========================================================================== */

const GlobalLoadingOverlay = {
  SHOW_DELAY_MS: 0,
  MIN_VISIBLE_MS: 250,
  fetchInFlightCount: 0,
  htmxInFlight: new Set(),
  showTimer: null,
  shownAt: 0,
  fetchPatched: false,

  init() {
    this.bindHtmx();
    this.patchFetch();
  },

  bindHtmx() {
    if (!window.htmx || !document.body) return;

    document.body.addEventListener('htmx:beforeRequest', (evt) => this.htmxRequestStarted(evt));
    document.body.addEventListener('htmx:afterRequest', (evt) => this.htmxRequestFinished(evt));
    document.body.addEventListener('htmx:sendError', (evt) => this.htmxRequestFinished(evt));
    document.body.addEventListener('htmx:timeout', (evt) => this.htmxRequestFinished(evt));
    document.body.addEventListener('htmx:responseError', (evt) => this.htmxRequestFinished(evt));
  },

  patchFetch() {
    if (this.fetchPatched || typeof window.fetch !== 'function') return;

    const originalFetch = window.fetch.bind(window);
    window.fetch = (...args) => {
      this.fetchInFlightCount += 1;
      this.updateVisibility();
      try {
        return originalFetch(...args).finally(() => {
          this.fetchInFlightCount = Math.max(0, this.fetchInFlightCount - 1);
          this.updateVisibility();
        });
      } catch (err) {
        this.fetchInFlightCount = Math.max(0, this.fetchInFlightCount - 1);
        this.updateVisibility();
        throw err;
      }
    };

    this.fetchPatched = true;
  },

  htmxRequestStarted(evt) {
    const key = evt?.detail?.xhr;
    if (!key) return;
    this.htmxInFlight.add(key);
    this.updateVisibility();
  },

  htmxRequestFinished(evt) {
    const key = evt?.detail?.xhr;
    if (!key) return;
    this.htmxInFlight.delete(key);
    this.updateVisibility();
  },

  totalInFlightCount() {
    return this.fetchInFlightCount + this.htmxInFlight.size;
  },

  updateVisibility() {
    const inFlightCount = this.totalInFlightCount();

    if (inFlightCount > 0) {
      if (!this.showTimer && !this.getOverlay()?.classList.contains('is-visible')) {
        this.showTimer = setTimeout(() => {
          this.showTimer = null;
          if (this.totalInFlightCount() > 0) {
            this.showNow();
          }
        }, this.SHOW_DELAY_MS);
      }
      return;
    }

    if (this.showTimer) {
      clearTimeout(this.showTimer);
      this.showTimer = null;
    }

    const overlay = this.getOverlay();
    if (!overlay || !overlay.classList.contains('is-visible')) return;

    const visibleForMs = Date.now() - this.shownAt;
    const remainingMs = Math.max(0, this.MIN_VISIBLE_MS - visibleForMs);
    window.setTimeout(() => this.hideNow(), remainingMs);
  },

  showNow() {
    const overlay = this.getOverlay();
    if (!overlay) return;
    overlay.classList.add('is-visible');
    overlay.setAttribute('aria-hidden', 'false');
    this.shownAt = Date.now();
  },

  hideNow() {
    const overlay = this.getOverlay();
    if (!overlay || this.totalInFlightCount() > 0) return;
    overlay.classList.remove('is-visible');
    overlay.setAttribute('aria-hidden', 'true');
  },

  getOverlay() {
    return document.getElementById('globalLoadingOverlay');
  }
};

// Initialize toast manager when DOM is ready
document.addEventListener('DOMContentLoaded', function() {
  ToastManager.init();
  GlobalLoadingOverlay.init();
});
