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
