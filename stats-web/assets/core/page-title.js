'use strict';

  const PRODUCT = 'VCE';

  function safeText(value, fallback = '') {
    const clean = String(value ?? '').replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, ' ')
      .replace(/\s+/g, ' ').trim().slice(0, 96);
    return clean || fallback;
  }

  function callTitle(playerState, retainCompleted = false) {
    const target = safeText(playerState?.playing === true ? playerState.targetLabel :
      retainCompleted ? playerState?.retainedTargetLabel : '');
    if (!target) return '';
    const queued = Math.max(0, Math.trunc(Number(playerState.queuedCount) || 0));
    return queued ? `${target} (${queued})` : target;
  }

  function baseTitle(value) {
    const label = safeText(value, 'Not Found');
    return label === PRODUCT ? PRODUCT : `${PRODUCT} - ${label}`;
  }

  function derive(options = {}) {
    const base = baseTitle(options.pageTitle);
    const playing = callTitle(options.playerState, options.prependPlaying === true);
    if (!playing) return base;
    if (options.routeId === 'scanner') return playing;
    return options.prependPlaying === true ? `${playing} - ${base}` : base;
  }

  class Controller {
    constructor(documentValue) {
      this.document = documentValue;
      this.state = { routeId: 'dashboard', pageTitle: 'Dashboard', playerState: null, prependPlaying: false };
      this.render();
    }

    update(values = {}) {
      this.state = { ...this.state, ...values };
      return this.render();
    }

    render() {
      const title = derive(this.state);
      if (this.document) this.document.title = title;
      return title;
    }
  }

export { PRODUCT, safeText, callTitle, baseTitle, derive, Controller };
