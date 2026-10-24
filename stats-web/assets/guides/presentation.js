// Page tours share sample activation metadata and temporary page/player ownership.
// The guide engine owns cancellation and releases late preparation results.
export function pageGuidePresentation(view, section, preparePlayer, preparePage, { restoreScroll = false } = {}) {
  return {
    samples: [{ id: `${view}-workspace`, value: { section } }],
    prepare: async context => {
      // Minimizing the player can shorten the page and clamp its scroll position.
      const windowValue = restoreScroll ? globalThis.window : null;
      const scroll = windowValue ? { left: windowValue.scrollX, top: windowValue.scrollY,
        href: windowValue.location.href } : null;
      const releaseScroll = () => {
        if (scroll && windowValue.location.href === scroll.href)
          windowValue.scrollTo({ left: scroll.left, top: scroll.top, behavior: 'instant' });
      };
      const releasePlayer = await preparePlayer?.({ minimized: true });
      try {
        const releasePage = await preparePage?.(view, section, context);
        return () => { try { releasePage?.(); } finally {
          try { releasePlayer?.(); } finally { releaseScroll(); }
        } };
      } catch (error) {
        try { releasePlayer?.(); } finally { releaseScroll(); }
        throw error;
      }
    }
  };
}
