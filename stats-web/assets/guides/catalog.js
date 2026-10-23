import { definitions as routes } from '../core/routes.js?v=11';

// Every page starts here. Register its step builder when the guide is ready;
// placeholders are available through Help but never open automatically.
const authoredPages = Object.freeze({ 'channel-setup': channelSetupSteps });
export const pageGuideTemplates = Object.freeze(Object.fromEntries(routes.map(({ id, title }) => [id,
  Object.freeze({ id, title, version: 1, authored: Boolean(authoredPages[id]), steps: Object.freeze([]) })])));

function targets(documentValue, ...selectors) {
  return () => selectors.flatMap((selector) => [...(documentValue?.querySelectorAll(selector) || [])]);
}

function menu(documentValue, groupName) {
  return () => {
    const groups = [...(documentValue?.querySelectorAll('.primary-nav [data-nav-group]') || [])];
    const saved = groups.map((group) => [group, group.open]);
    groups.forEach((group) => { group.open = group.dataset.navGroup === groupName; });
    return () => saved.forEach(([group, open]) => { group.open = open; });
  };
}

function combine(...preparations) {
  return () => {
    const cleanups = preparations.map((prepare) => prepare?.()).filter((cleanup) => typeof cleanup === 'function');
    return () => cleanups.reverse().forEach((cleanup) => cleanup());
  };
}

function navigationTargets(documentValue, group, views) {
  return targets(documentValue, `.primary-nav [data-nav-group="${group}"] > summary`,
    ...views.map((view) => `.primary-nav [data-view="${view}"]`));
}

function visibleControl(documentValue, selector) {
  const control = documentValue?.querySelector?.(selector);
  // Closed menus have no client rects. Only explicit access hiding is relevant.
  return !control || (!control.hidden && !control.closest?.('[hidden]'));
}

function welcomeSteps(documentValue, preparePlayer, canSetup, canView, canReport) {
  const allowed = (view) => typeof canView === 'function' ? canView(view) !== false :
    visibleControl(documentValue, `.primary-nav [data-view="${view}"]`);
  const canUsePlayer = allowed('scanner') || allowed('recordings');
  const navigation = {
    hardware: ['hardware', ['tuners', 'tuner-spectrum']],
    manage: ['configuration', ['channel-setup', 'aliases', 'radioreference', 'streaming']],
    'scan-lists': ['configuration', ['scan-lists', 'alerts']],
    live: ['listen', ['live']],
    listening: ['listen', ['dashboard', 'scanner', 'recordings']],
    settings: ['settings', ['admin', 'settings']]
  };
  const itemViews = { Main: 'dashboard', Scanner: 'scanner', Recordings: 'recordings',
    Administration: 'admin', 'My Settings': 'settings' };
  const steps = [
    { id: 'welcome', title: 'Welcome to sdrtrunk VCE', illustration: true,
      paragraphs: ['Turn signals from your software-defined radio into conversations you can follow.',
        'Listen to radio traffic, see who’s transmitting, and explore activity across your radio systems.'] },
    { id: 'hardware', title: 'Explore the airwaves',
      prepare: menu(documentValue, 'hardware'),
      targets: navigationTargets(documentValue, 'hardware', ['tuner-spectrum', 'tuners']),
      paragraphs: ['In Tuners, adjust gain, correct frequency error with PPM calibration, and set your tuner’s center frequency.',
        'Use Browser Spectrum to spot active signals visually and inspect frequencies worth listening to.'] },
    { id: 'manage', title: 'Your familiar setup tools',
      prepare: menu(documentValue, 'configuration'),
      targets: navigationTargets(documentValue, 'configuration', ['channel-setup', 'aliases', 'radioreference', 'streaming']),
      paragraphs: ['Coming from an earlier version of sdrtrunk? Your channel setup, Alias Lists, RadioReference imports, and streaming configuration now live here.'] },
    { id: 'scan-lists', title: 'Choose what you hear',
      prepare: menu(documentValue, 'configuration'),
      targets: navigationTargets(documentValue, 'configuration', ['scan-lists', 'alerts']),
      paragraphs: ['In VCE, Scan Lists replace the listening checkbox on Alias Lists. Add the aliases you want to hear to a Scan List, make it available to listeners, then select it in the player.',
        'Alerts can notify you about radio activity or receiver problems.'] },
    { id: 'live', title: 'A familiar view of radio traffic',
      prepare: menu(documentValue, 'listen'),
      targets: navigationTargets(documentValue, 'listen', ['live']),
      paragraphs: ['If you’ve used sdrtrunk before, Live is the new home for monitoring your channels and watching decoded radio activity as it happens.'] },
    { id: 'listening', title: 'See more. Settle in and listen.',
      prepare: menu(documentValue, 'listen'),
      targets: navigationTargets(documentValue, 'listen', ['dashboard', 'scanner', 'recordings']),
      paragraphs: ['VCE records each call before playing it, so you hear it after the transmission finishes.'],
      items: [
        { label: 'Main', text: 'Follow your radio systems over time, explore call statistics, and check reception.' },
        { label: 'Scanner', text: 'Focus on listening, with details about the current call and a queue of upcoming calls.' },
        { label: 'Recordings', text: 'With Managed Recordings enabled, return to saved calls in a library you can search and replay.' }
      ] },
    { id: 'player', title: 'Keep listening as you browse',
      prepare: preparePlayer,
      targets: targets(documentValue, '[data-guide-target="player"]'),
      paragraphs: ['The player stays with you as you move between pages.'],
      items: [
        { label: 'Live or Recordings', text: 'Follow incoming calls or play saved calls.' },
        { label: 'Make room', text: 'Click the handle to change the player’s size, or drag it to adjust the height.' },
        { label: 'Lists', text: 'Choose which Scan Lists you hear. Select several to listen to them together.' },
        { label: 'Keep it close', text: 'X minimizes the player to the header while audio continues. Select Player there to bring it back.' }
      ] },
    { id: 'page-help', title: 'A guide for every page',
      targets: targets(documentValue, '[data-guide-target="page-help"]'),
      paragraphs: ['Whenever you want a hand, select ? to open the guide for your current page. It highlights the main controls and walks you through using them.'] },
    { id: 'report-issue', title: 'Help us track down problems',
      targets: targets(documentValue, '[data-guide-target="report-issue"]'),
      paragraphs: ['Something behaving unexpectedly? Select the bug icon and describe what you expected, what happened, and how to repeat it.',
        'You can include diagnostic information to help investigate, then prepare and submit your report.'] },
    { id: 'settings', title: 'Shared settings and your preferences',
      prepare: menu(documentValue, 'settings'),
      targets: navigationTargets(documentValue, 'settings', ['admin', 'settings']),
      paragraphs: [],
      items: [
        { label: 'Administration', text: 'Control the receiver and shared settings that can affect everyone using it.' },
        { label: 'My Settings', text: 'Personalize the user settings for your sign-in only' }
      ] },
    { id: 'theme', title: 'Make yourself comfortable',
      targets: targets(documentValue, '[data-guide-target="theme"]'),
      paragraphs: ['Switch between light and dark themes whenever you like.', canSetup ?
        'You’re ready to start receiving. Next, we’ll walk through adding your first channel.' :
        'You’re ready to explore. Open a page’s guide whenever you need a hand.'],
      nextLabel: canSetup ? 'Set up channels' : 'Finish' }
  ];
  return steps.filter((step) => {
    if (step.id === 'player') return canUsePlayer;
    if (step.id === 'report-issue') return typeof canReport === 'boolean' ? canReport :
      visibleControl(documentValue, '[data-guide-target="report-issue"]');
    return !navigation[step.id] || navigation[step.id][1].some(allowed);
  }).map((step) => {
    const nav = navigation[step.id];
    if (nav) step.targets = navigationTargets(documentValue, nav[0], nav[1].filter(allowed));
    if (step.items && ['listening', 'settings'].includes(step.id)) {
      step.items = step.items.filter(({ label }) => allowed(itemViews[label]));
    }
    if (step.id === 'hardware') {
      step.targets = targets(documentValue, '.primary-nav [data-nav-group="hardware"] > summary',
        '.primary-nav [data-nav-group="hardware"] > .nav-group-links');
      step.paragraphs = step.paragraphs.filter((_, index) => allowed(navigation.hardware[1][index]));
    }
    if (step.id === 'manage' && navigation.manage[1].some((view) => !allowed(view))) {
      const names = { 'channel-setup': 'channel setup', aliases: 'Alias Lists',
        radioreference: 'RadioReference imports', streaming: 'streaming configuration' };
      const familiar = navigation.manage[1].filter(allowed).map((view) => names[view]);
      step.paragraphs = [`Coming from an earlier version of sdrtrunk? Find your familiar tools here: ${familiar.join(', ')}.`];
    }
    if (step.id === 'scan-lists') {
      step.paragraphs = step.paragraphs.filter((_, index) => allowed(navigation['scan-lists'][1][index]));
      if (!allowed('scan-lists')) step.title = 'Stay informed';
    }
    if (step.id === 'listening' && !canUsePlayer) step.paragraphs = [];
    if (step.id === 'player') {
      if (!allowed('scanner')) step.items = step.items.filter(({ label }) => label !== 'Lists');
      if (!allowed('scanner') || !allowed('recordings')) {
        step.items[0] = allowed('scanner') ? { label: 'Live', text: 'Follow incoming calls from your selected Scan Lists.' } :
          { label: 'Recordings', text: 'Play saved calls as you move between pages.' };
      }
    }
    if (step.id === 'settings' && !allowed('admin')) step.title = 'Make it yours';
    return step;
  });
}

function channelSetupSteps(documentValue, preparePlayer) {
  return [
    { id: 'new-channel', title: 'Add your first channel',
      targets: targets(documentValue, '[data-guide-target="channel-new"]'),
      paragraphs: ['Start with a frequency you know, or import a channel from RadioReference. Give it a name you’ll recognize and choose the matching radio protocol.'] },
    { id: 'discover', title: 'Search for trunked systems',
      targets: targets(documentValue, '[data-guide-target="channel-discover"]'),
      paragraphs: ['Not sure where to start? Find Trunked Systems searches for radio systems your tuner can receive.',
        'Scan supported bands for trunked radio systems, then automatically name and add the systems you choose to your channels. Running channels may need to stop temporarily during the search.'] },
    { id: 'filters', title: 'Find the channel you need',
      targets: targets(documentValue, '[data-guide-target="channel-filters"]'),
      paragraphs: ['Search by channel, system, frequency, protocol, or Alias List. Use the status and protocol filters to narrow a busy channel list.'] },
    { id: 'receiving', title: 'Start receiving',
      samples: [{ id: 'channel-catalog', value: {
        channels: [{ configuration_id: 'guide-sample-channel', name: 'Sample channel', system: 'Example system',
          protocol_id: 'p25-phase1', protocol_label: 'P25 Phase 1', processing_state: 'STOPPED',
          frequencies_hz: [851012500], editable: true, alias_list_name: 'Sample aliases',
          alias_list_id: null, auto_start_order: null }],
        selected_channel_ids: ['guide-sample-channel']
      } }],
      targets: targets(documentValue, '[data-guide-sample] .channel-name-summary',
        '[data-guide-sample] td[data-label="State"]', '[data-guide-target="channel-sample-start"]'),
      paragraphs: ['Once you’ve saved a channel, use its Start action to begin receiving. Running channels show their current state here.',
        'Select a channel to review its configuration. For trunked systems, the channel receives the control signal and follows the system’s voice traffic.'] },
    { id: 'startup', title: 'Ready when you are',
      targets: targets(documentValue, '[data-guide-target="channel-startup"]'),
      paragraphs: ['Use Startup order to choose which channels start automatically when VCE launches, and the order in which they start.'] },
    { id: 'scan-lists', title: 'Choose what you hear',
      prepare: combine(menu(documentValue, 'configuration'), preparePlayer),
      targets: targets(documentValue, '.primary-nav [data-view="scan-lists"]', '[data-guide-target="player-lists"]'),
      paragraphs: ['VCE separates starting channels from listening to them. Start a channel, create aliases, and add those aliases to Scan Lists. Then use Lists in the player to choose the Scan Lists you want to hear.',
        'VCE records each call before playing it. You’ll hear the conversation after the transmission finishes.'],
      nextLabel: 'Finish' }
  ];
}

export function createGuideDefinition(id, { documentValue = globalThis.document, preparePlayer,
  canSetup = true, canView, canReport } = {}) {
  if (id === 'welcome') return { id, version: 1, authored: true, title: 'Welcome to sdrtrunk VCE',
    eyebrow: 'WELCOME GUIDE', steps: welcomeSteps(documentValue, preparePlayer,
      canSetup && (typeof canView !== 'function' || canView('channel-setup') !== false), canView, canReport) };
  const template = pageGuideTemplates[id];
  if (!template) return null;
  if (authoredPages[id]) return { ...template, eyebrow: template.title,
    steps: authoredPages[id](documentValue, preparePlayer) };
  return { ...template, eyebrow: template.title, steps: [
    { id: 'coming-soon', title: 'Guide coming soon', paragraphs: [
      `A walkthrough for ${template.title} is being prepared. Take the welcome tour for an introduction to the menus and player.`
    ], nextLabel: 'Take the welcome tour' }
  ] };
}
