import { definitions as routes } from '../core/routes.js?v=11';

// Every page starts here. Register its step builder when the guide is ready;
// placeholders are available through Help but never open automatically.
const authoredPages = Object.freeze({ 'channel-setup': channelSetupSteps, aliases: aliasesSteps });
// The authored Aliases tour supersedes the dismissible version-one placeholder.
const guideVersions = Object.freeze({ aliases: 2 });
export const pageGuideTemplates = Object.freeze(Object.fromEntries(routes.map(({ id, title }) => [id,
  Object.freeze({ id, title, version: guideVersions[id] || 1,
    authored: Boolean(authoredPages[id]), steps: Object.freeze([]) })])));

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
      prepare: preparePlayer,
      targets: targets(documentValue, '[data-guide-target="player-lists"]'),
      paragraphs: ['VCE separates starting channels from listening to them. Start a channel, create aliases, and add those aliases to Scan Lists. Then use Lists in the player to choose the Scan Lists you want to hear.',
        'VCE records each call before playing it. You’ll hear the conversation after the transmission finishes.'],
      nextLabel: 'Finish' }
  ];
}

function aliasesSteps(documentValue, preparePlayer) {
  const focus = (...names) => targets(documentValue,
    ...names.map((name) => `[data-guide-target="${name}"]`));
  const sample = (section) => [{ id: 'aliases-workspace', value: { section } }];
  return [
    { id: 'lists', title: 'Your Alias Lists', samples: sample('lists'),
      targets: focus('aliases-lists', 'aliases-new-list', 'aliases-usage'),
      paragraphs: ['Alias Lists connect recognizable names and call settings to your channels. Select a list here to explore its aliases and see which channels use it.',
        'Channels with a matching protocol can share the same list. This tour uses example data; nothing is saved.'] },
    { id: 'configure', title: 'Configure: names and behavior', samples: sample('configure'),
      targets: focus('aliases-configure', 'aliases-filters', 'aliases-defaults'),
      paragraphs: ['See what each alias matches and how its calls are handled. Search by name, description, group, or identifier, and use filters to narrow the list.',
        'Open an alias to inspect or change its settings. Call Handling Defaults above the views controls unmatched traffic and how future aliases begin.'] },
    { id: 'alias-editor', title: 'Inside an alias', samples: sample('alias-editor'),
      targets: focus('aliases-editor-tabs'),
      paragraphs: ['Basics holds the name and appearance. Identifier defines which radio activity matches. Call Handling controls recording, Scan List membership, and streaming. Activity shows what has been observed for this alias.',
        'Add Alias opens this same editor for a new entry.'] },
    { id: 'defaults-unmatched', title: 'Call Handling Defaults: Unmatched Calls', samples: sample('defaults-unmatched'),
      targets: focus('aliases-defaults-tabs', 'aliases-default-record', 'aliases-default-scan', 'aliases-default-stream'),
      paragraphs: ['This is the list’s catch-all for received talkgroups without an exact alias or covering talkgroup range.',
        'Choose whether to record those calls, route them to Scan Lists for listening, or send them to streaming destinations. These settings handle the traffic without creating aliases.'] },
    { id: 'defaults-new', title: 'Call Handling Defaults: New Aliases', samples: sample('defaults-new'),
      targets: focus('aliases-defaults-tabs', 'aliases-default-record', 'aliases-default-scan', 'aliases-default-stream'),
      paragraphs: ['Choose the starting recording, Scan List, and streaming settings for aliases you create or add from RadioReference.',
        'Changing these defaults does not update existing aliases. Use individual editing or bulk operations to change aliases already in the list.'] },
    { id: 'bulk', title: 'Work with several aliases', samples: sample('bulk'),
      targets: focus('aliases-selection', 'aliases-bulk', 'aliases-select-all'),
      paragraphs: ['Select aliases to move or group them, change their Scan Lists, recording, streaming, or appearance, or delete them together.',
        'The header checkbox selects this page. Select All Matching extends the selection across your filtered results.'] },
    { id: 'activity', title: 'Activity: see what’s being used', samples: sample('activity'),
      targets: focus('aliases-activity', 'aliases-activity-columns'),
      paragraphs: ['Review completed calls, recognized system signaling, and when activity was last observed.',
        'Calls and signaling describe different aspects of radio activity and can overlap, so their counts should not be added together.'] },
    { id: 'custom', title: 'Custom: choose your view', samples: sample('custom'),
      targets: focus('aliases-custom', 'aliases-columns'),
      paragraphs: ['Bring configuration and activity details together in the same table. Use Columns to choose and arrange the information you want.',
        'Configure, Activity, and Custom are different views of the same aliases.'] },
    { id: 'discover', title: 'Discover: find identities worth naming', samples: sample('discover'),
      targets: focus('aliases-discover', 'aliases-discover-table'),
      paragraphs: ['Explore talkgroups and patch groups heard on your assigned channels that have no exact alias. Groups already covered by a range can appear here too.',
        'Where enough information is available, Create Alias opens an editor with the identifier filled in. Analog lists use Unmatched assignments for this view.'] },
    { id: 'transfer', title: 'Create, import, and export', samples: sample('transfer'),
      targets: focus('aliases-add', 'aliases-import', 'aliases-export'),
      paragraphs: ['Add individual aliases here, or import from RadioReference or a supported CSV file into the current list. RadioReference imports require Premium access.',
        'Export aliases provides a reusable configuration file or an activity report for analysis.'] },
    { id: 'listening', title: 'How aliases become listening choices', samples: sample('listening'),
      prepare: preparePlayer,
      targets: focus('aliases-memberships', 'player-lists'),
      paragraphs: ['In VCE, Scan Lists replace the old Alias List listening checkbox. Add the aliases you want to hear to Scan Lists, make those lists Available to listeners, then choose them through Lists in the player.',
        'An alias can belong to several Scan Lists.'],
      nextLabel: 'Finish' }
  ].map((step) => ({ ...step,
    prepare: step.prepare || (() => preparePlayer?.({ minimized: true })) }));
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
