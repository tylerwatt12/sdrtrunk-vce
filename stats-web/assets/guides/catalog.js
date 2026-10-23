import { definitions as routes } from '../core/routes.js?v=11';
import { channelSetupSteps } from './pages/channel-setup.js?v=1';
import { aliasesSteps } from './pages/aliases.js?v=1';
import { radioReferenceSteps } from './pages/radioreference.js?v=1';
import { streamingSteps } from './pages/streaming.js?v=2';

// Every page starts here. Register its step builder when the guide is ready;
// placeholders are available through Help but never open automatically.
const authoredPages = Object.freeze({ 'channel-setup': channelSetupSteps, aliases: aliasesSteps,
  'scan-lists': scanListsSteps, radioreference: radioReferenceSteps, streaming: streamingSteps });
// Page concepts stay separate from the numbered controls that follow them.
// Keep a purpose and practical radio use here even while a walkthrough is pending.
const pageOverviewCopy = Object.freeze({
  dashboard: ['Follow your radio systems',
    'See activity across your radio systems and use the statistics to find busy talkgroups, active radios, and changes over time.'],
  live: ['Watch radio traffic as it happens',
    'Follow your running channels and decoded radio activity as it arrives. Use this view to see which talkgroups and radios are active.'],
  topics: ['Follow the activity that matters to you',
    'Subscribe to topics for alerts about selected radio activity or receiver conditions. Each topic brings related alerts together.'],
  recordings: ['Return to a conversation',
    'With Managed Recordings enabled, saved calls become a searchable listening library. Find traffic by its radio system, talkgroup, or time and replay the calls you need.'],
  map: ['Put radio activity on the map',
    'Follow reported locations and review recent location history to see where radio activity is taking place.'],
  'network-visualizer': ['See how a P25 system is connected',
    'Explore the sites and connections reported by a P25 system. Use the visualizer to understand its network and follow changes as they are heard.'],
  scanner: ['Settle in and listen',
    'Follow completed calls from your selected Scan Lists, with details about the call playing and the traffic waiting to be heard.'],
  'tuner-spectrum': ['Find signals your tuner can receive',
    'Explore the spectrum to spot active signals and inspect their frequencies. Use what you find to choose signals worth receiving.'],
  'radio-system': ['Get to know a radio system',
    'Explore a system’s talkgroups, radios, and activity history. Follow the names and identifiers to see who uses the system and when.'],
  'group-identity': ['Follow a talkgroup’s activity',
    'Review calls and radios associated with this talkgroup. Its activity history can help you recognize how the group is used.'],
  radio: ['Follow an individual radio',
    'Review this radio’s activity and the talkgroups it has used. Compare its identity and history as you work out who is operating it.'],
  'channel-setup': ['Choose the signals VCE receives',
    'A channel tells VCE which frequency or trunked system to receive. Start channels here, then use Scan Lists to choose which calls you hear.'],
  channel: ['Understand reception on this channel',
    'Review activity from this channel and follow its talkgroups and radios. Reception details help you connect what is heard with the channel receiving it.'],
  aliases: ['Give radio traffic meaning',
    'Aliases give familiar names to talkgroups and radios. Their call handling settings choose recording, Scan List membership, and streaming.'],
  'scan-lists': ['From radio traffic to what you hear',
    'Scan Lists bring together the calls you want to hear, across your radio systems.'],
  radioreference: ['Bring radio information into VCE',
    'Find receiving frequencies and talkgroup names in RadioReference, import them into VCE, and return here to keep that information current.'],
  alerts: ['Have important activity brought to your attention',
    'Choose radio activity or receiver conditions that should publish an alert. Group related alerts into topics that listeners can follow.'],
  streaming: ['Share your radio traffic',
    'Send received radio traffic to services such as Broadcastify, OpenMHz, and RadioResolve. Alias assignments choose which calls each destination receives.'],
  tuners: ['Prepare your receivers',
    'Your tuners supply the radio signals VCE decodes. Check their availability and adjust reception settings to suit your antennas and local signals.'],
  admin: ['Look after this VCE installation',
    'Control the receiver and the services shared by everyone using it. Check receiver health and manage access as your installation grows.'],
  settings: ['Make VCE comfortable for you',
    'Choose the display and listening preferences used with your sign-in. Your preferences follow you when you return.'],
  credits: ['The people and projects behind VCE',
    'Meet VCE’s developer and the projects that make it possible. Find project links and open-source license information here.']
});
export const pageGuideOverviews = Object.freeze(Object.fromEntries(Object.entries(pageOverviewCopy)
  .map(([id, [title, ...paragraphs]]) => [id,
    Object.freeze({ id: 'overview', overview: true, title, paragraphs: Object.freeze(paragraphs),
      ...(id === 'scan-lists' ? { diagram: 'scan-lists' } : {}) })])));
// A revised page structure receives a new dismissal version for its first visit.
const guideVersions = Object.freeze({ 'channel-setup': 3, aliases: 5, 'scan-lists': 6, radioreference: 3, streaming: 3 });
export const pageGuideTemplates = Object.freeze(Object.fromEntries(routes.map(({ id, title }) => [id,
  Object.freeze({ id, title, version: guideVersions[id] || 2,
    overview: pageGuideOverviews[id], authored: Boolean(authoredPages[id]), steps: Object.freeze([]) })])));

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

function scanListsSteps(documentValue, preparePlayer, preparePage, canView) {
  const focus = (...names) => targets(documentValue,
    ...names.map((name) => `[data-guide-target="${name}"]`));
  const canManageAliases = typeof canView !== 'function' || canView('aliases') !== false;
  return [
    { id: 'lists', title: canManageAliases ? 'Review your listening group' : 'Your listening groups',
      targets: canManageAliases ? focus('scan-lists-manage-aliases') : focus('scan-lists-selector'),
      paragraphs: [canManageAliases ? 'Open this Scan List’s members to see the aliases that feed your listening group.' :
        'Choose a listening group to see its assigned aliases.'] },
    { id: 'members', title: 'Follow related traffic across systems',
      targets: focus('scan-lists-member-alias', 'scan-lists-member-alias-list'),
      paragraphs: ['Bring related talkgroups from several radio systems together in one listening group. Fire Dispatch can belong to both a focused Fire & EMS list and a broader Public Safety list.'] },
    { id: 'add-list-button', title: 'Bring in an Alias List',
      targets: focus('scan-lists-add-list-button'),
      paragraphs: ['Use this to bring an Alias List’s current aliases into your listening group.',
        'To add individual aliases to a Scan List, go to Manage > Aliases.'] },
    { id: 'add-alias-list', title: 'Build a listening group quickly',
      targets: focus('scan-lists-add-list-dialog'),
      paragraphs: ['Choose an Alias List. Add All Aliases adds its current aliases and returns you to the members page.'] },
    { id: 'back-to-lists', title: 'Return to your listening groups',
      targets: focus('scan-lists-back-to-lists'),
      paragraphs: ['Return to Scan Lists to review your listening groups.'] },
    { id: 'listening-controls', title: 'Choose your listening mix',
      targets: focus('player-lists'),
      paragraphs: ['Each listener chooses a listening mix here. Your choices are saved for your sign-in.'] },
    { id: 'listening', title: 'Hear your selected traffic together',
      targets: focus('scan-lists-player-lists'),
      paragraphs: ['VCE queues completed calls from your selected lists and plays them one at a time. A call shared by several selected lists plays once.'],
      nextLabel: 'Finish' }
  ].filter((step) => {
    if (typeof canView !== 'function') return true;
    if (['listening-controls', 'listening'].includes(step.id)) return canView('scanner') !== false;
    if (['members', 'add-list-button', 'add-alias-list', 'back-to-lists'].includes(step.id)) {
      return canView('aliases') !== false;
    }
    return true;
  }).map((step, index, steps) => ({ ...step,
    nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
    samples: [{ id: 'scan-lists-workspace', value: { section: step.id } }],
    prepare: async (context) => {
      const releasePlayer = await preparePlayer?.({ minimized: true });
      try {
        const releasePage = await preparePage?.('scan-lists', step.id, context);
        return () => { releasePage?.(); releasePlayer?.(); };
      } catch (error) {
        releasePlayer?.();
        throw error;
      }
    }
  }));
}

export function createGuideDefinition(id, { documentValue = globalThis.document, preparePlayer,
  preparePage, canSetup = true, canView, canAccess, canReport } = {}) {
  if (id === 'welcome') return { id, version: 1, authored: true, title: 'Welcome to sdrtrunk VCE',
    eyebrow: 'WELCOME GUIDE', steps: welcomeSteps(documentValue, preparePlayer,
      canSetup && (typeof canView !== 'function' || canView('channel-setup') !== false), canView, canReport) };
  const template = pageGuideTemplates[id];
  if (!template) return null;
  if (authoredPages[id]) return { ...template, eyebrow: template.title,
    steps: [template.overview, ...authoredPages[id](documentValue, preparePlayer, preparePage, canView, canAccess)] };
  return { ...template, eyebrow: template.title, steps: [
    { ...template.overview, nextLabel: 'Continue' },
    { id: 'coming-soon', title: 'Guide coming soon', paragraphs: [
      `A walkthrough for ${template.title} is being prepared. Take the welcome tour for an introduction to the menus and player.`
    ], nextLabel: 'Take the welcome tour' }
  ] };
}
