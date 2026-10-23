import { openSetupGuide } from '../core/setup-guide.js?v=9';
import { createGuideDefinition, pageGuideTemplates } from './catalog.js?v=15';
import { createGuideMemory } from './persistence.js?v=1';

const sessionKey = (context) => JSON.stringify([context.account || '', context.dismissalScope || '',
  context.authenticated === true]);
const visitKey = (context) => `${sessionKey(context)}:${context.view}`;

// The application calls pageReady after a successful route render and close when
// rendering/signing out starts. Tour state stays independent of page builders.
export function createGuideController({ ui, getContext, navigate, consumeGuide, preparePlayer, preparePage,
  documentValue = globalThis.document, storage, openGuide = openSetupGuide }) {
  const memory = createGuideMemory(storage);
  let active = null;
  let generation = 0;
  let destroyed = false;
  let currentVisit = null;
  let suppressAutomatic = false;
  let replayOnPage = null;
  let consumedRequest = null;

  const definitionFor = (id, context) => createGuideDefinition(id, { documentValue, preparePlayer, preparePage,
    canSetup: context.canSetup !== false, canView: context.canView, canReport: context.canReport, canAccess: context.canAccess });
  const contextMatches = (context) => !destroyed && visitKey(getContext()) === visitKey(context);
  const eligible = (context, manual) => !destroyed && context.isDesktop === true &&
    (manual || context.authenticated === true) && context.canView?.(context.view) !== false;
  const clearActive = () => {
    generation++;
    const previous = active;
    active = null;
    previous?.presentation?.close();
  };
  const updateVisit = (context) => {
    const key = visitKey(context);
    if (key === currentVisit) return;
    clearActive();
    currentVisit = key;
    suppressAutomatic = false;
    consumedRequest = null;
    if (replayOnPage && (replayOnPage.session !== sessionKey(context) || replayOnPage.id !== context.view)) {
      replayOnPage = null;
    }
  };

  function complete(record) {
    if (active !== record || !contextMatches(record.context)) return;
    memory.remember(record.context, record.definition);
    active = null;
    if (!record.definition.authored) {
      void present('welcome', true);
      return;
    }
    if (record.definition.id !== 'welcome' || record.context.canSetup === false ||
        record.context.canView?.('channel-setup') === false) return;
    const context = getContext();
    if (context.view === 'channel-setup') {
      const page = definitionFor(context.view, context);
      if (record.manual || !memory.has(context, page)) void present(context.view, record.manual);
    } else if (navigate) {
      if (record.manual) replayOnPage = { id: 'channel-setup', session: sessionKey(context) };
      navigate('channel-setup');
    }
  }

  function present(id, manual) {
    const context = { ...getContext() };
    updateVisit(context);
    const ownsPresentation = active && (active.pending || active.presentation?.isOpen());
    if (!eligible(context, manual) || (!ownsPresentation && context.canPresent === false)) return Promise.resolve(false);
    const definition = definitionFor(id, context);
    if (!definition) return Promise.resolve(false);
    clearActive();
    const record = { context, definition, manual, generation, presentation: null, pending: true };
    active = record;
    try {
      record.presentation = openGuide(ui, { ...definition,
        onDismiss: () => {
          if (active !== record || !contextMatches(context)) return;
          memory.remember(context, definition);
          suppressAutomatic = true;
          active = null;
        },
        onComplete: () => complete(record)
      });
    } catch (error) {
      if (active === record) active = null;
      throw error;
    }
    if (!record.presentation) { active = null; return Promise.resolve(false); }
    return Promise.resolve(record.presentation.ready).then((shown) => {
      record.pending = false;
      if (!shown || active !== record || record.generation !== generation || !contextMatches(context)) {
        record.presentation.close();
        if (active === record) active = null;
        return false;
      }
      return true;
    }, () => {
      record.pending = false;
      record.presentation.close();
      if (active === record) active = null;
      return false;
    });
  }

  function pageReady() {
    if (destroyed) return Promise.resolve(false);
    const context = { ...getContext() };
    updateVisit(context);
    if (active) {
      if (active.pending || active.presentation?.isOpen()) return Promise.resolve(true);
      active = null;
    }
    // Consume only when a guide can actually be shown. A dirty editor can defer
    // this request until it closes without losing the explicit replay request.
    if (!eligible(context, true) || context.canPresent === false) return Promise.resolve(false);
    const requested = String(context.guide || '').trim();
    const requestKey = `${visitKey(context)}:${requested}`;
    if (!requested) consumedRequest = null;
    if (requested && requestKey !== consumedRequest) {
      consumedRequest = requestKey;
      consumeGuide?.();
      const id = ['page', 'current'].includes(requested) ? context.view : requested;
      if (id === 'welcome') return present(id, true);
      if (pageGuideTemplates[id]) {
        if (context.canView?.(id) === false) return Promise.resolve(false);
        if (id !== context.view && navigate) {
          replayOnPage = { id, session: sessionKey(context) };
          navigate(id);
          return Promise.resolve(false);
        }
        return present(id, true);
      }
    }
    if (replayOnPage?.id === context.view && replayOnPage.session === sessionKey(context)) {
      replayOnPage = null;
      return present(context.view, true);
    }
    if (!eligible(context, false) || suppressAutomatic) return Promise.resolve(false);
    const welcome = definitionFor('welcome', context);
    if (!memory.has(context, welcome)) return present('welcome', false);
    const page = definitionFor(context.view, context);
    if (page?.authored && !memory.has(context, page)) return present(context.view, false);
    return Promise.resolve(false);
  }

  return {
    pageReady,
    openPage: () => present(getContext().view, true),
    openWelcome: () => present('welcome', true),
    close: clearActive,
    destroy() { destroyed = true; replayOnPage = null; clearActive(); }
  };
}
