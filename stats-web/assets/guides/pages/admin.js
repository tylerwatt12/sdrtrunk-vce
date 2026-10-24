import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

// Chapters follow the actual Administration sidebar. Their short walkthroughs
// share one guide and presentation lifecycle, with one Administration completion record.
export const administrationSections = Object.freeze([
  { id: 'computer', label: 'Computer & version', group: 'Status & support', capability: 'receiver-health' },
  { id: 'health', label: 'Receiver status', group: 'Status & support', capability: 'receiver-health' },
  { id: 'application-log', label: 'Application log', group: 'Status & support', capability: 'admin-settings' },
  { id: 'web-diagnostic-log', label: 'Web server log', group: 'Status & support', capability: 'admin-settings' },
  { id: 'support', label: 'Report a problem', group: 'Status & support', capability: 'admin-settings' },
  { id: 'remote-support', label: 'Remote Support', group: 'Status & support', capability: 'admin-settings' },
  { id: 'call-matching', label: 'Call matching', group: 'Decisions engine', capability: 'admin-settings' },
  { id: 'tuner-allocation', label: 'Tuner allocation', group: 'Decisions engine', capability: 'admin-settings' },
  { id: 'recordings', label: 'Recording settings', group: 'Audio & recordings', capability: 'admin-recordings' },
  { id: 'audio-quality', label: 'Audio quality', group: 'Audio & recordings', capability: 'admin-settings' },
  { id: 'transcription', label: 'Transcription', group: 'Audio & recordings', capability: 'admin-recordings' },
  { id: 'push-setup', label: 'Push setup', group: 'Alerts', capability: 'admin-settings' },
  { id: 'activity', label: 'Activity settings', group: 'Activity & storage', capability: 'admin-settings' },
  { id: 'retained-statistics', label: 'Saved data cleanup', group: 'Activity & storage', capability: 'admin-settings' },
  { id: 'remote-links', label: 'Remote Links', group: 'Receiver configuration', capability: 'admin-settings' },
  { id: 'protocol-p25', label: 'P25 band plans', group: 'Receiver configuration', capability: 'admin-settings' },
  { id: 'display', label: 'Display settings', group: 'Web interface', capability: 'admin-settings' },
  { id: 'users', label: 'Web accounts', group: 'Web interface', capability: 'admin-users' },
  { id: 'access', label: 'Page access', group: 'Web interface', capability: 'admin-access' }
].map(section => Object.freeze(section)));

export function availableAdministrationSections(canAccess) {
  return administrationSections.filter(section => typeof canAccess !== 'function' || canAccess(section.capability) !== false);
}

export function adminSectionForStep(id) {
  return administrationSections.find(section => ['open', 'purpose', 'retention', 'channel', 'back', 'catalog'].some(suffix => id === `${section.id}-${suffix}`))?.id || null;
}

const sectionCopy = Object.freeze({
  computer: ['Check the installed VCE build, Java version, and computer details when comparing versions or reporting a problem.',
    'Copy these details to include them with a support request.'],
  health: ['Start here when channels fail to start, reception becomes unreliable, or the receiver struggles to keep up.',
    'It brings current problems and suggested actions together. Issue history helps identify recurring problems.'],
  'application-log': ['Use the log to follow what happened before and after a problem.',
    'Receiver messages can connect an error with a channel starting, a tuner disconnecting, or another event. Relevant messages can be included in a support report.'],
  'web-diagnostic-log': ['Some problems need to be recorded while they happen.',
    'Enable this before reproducing a web interface problem so the saved log includes the surrounding web activity and receiver conditions.'],
  support: ['Send the developer a description of the problem together with relevant receiver information.',
    'Selecting an issue helps VCE include useful evidence. Describe what you were doing, what happened, and what you expected.'],
  'remote-support': ['Use this when working directly with VCE’s developer to investigate a problem.',
    'It allows access to this instance’s web interface, including changing VCE settings. You control how long access lasts and can stop it at any time.'],
  'call-matching': ['Receiving the same traffic from several sites can produce multiple copies of a transmission.',
    'VCE compares those copies and selects the best-quality audio. Come here to investigate why a particular copy was used.'],
  'tuner-allocation': ['Use this when investigating missed calls or deciding whether your tuners provide enough coverage.',
    'It shows channel frequencies against tuner receiving windows, along with the reasons for tuner movements and calls that could not start.'],
  recordings: ['Decide how you want to keep and revisit radio traffic.',
    'Managed provides a searchable library for browser playback. Classic produces individual files for external tools such as Trunking Recorder.',
    'Retention controls how much managed history remains available; alias recording settings determine which calls are saved.'],
  'audio-quality': ['Adjust how recorded and streamed calls sound and how much storage or bandwidth they use.',
    'Volume normalization can help when some calls are much quieter than others. Paging-tone detection adds information useful for identifying and finding supported tone-bearing calls.'],
  transcription: ['Transcripts let you find a conversation by words you remember hearing.',
    'VCE sends eligible managed recordings to your configured speech-to-text service, including eligible older recordings. The progress panel helps identify a backlog or service problem.'],
  'push-setup': ['Set up delivery so devices subscribed through Topics can receive alerts.',
    'Cloud delivery requires approval and works with VCE on your local network. Direct delivery uses your installation’s public HTTPS address.'],
  activity: ['Decide how much radio activity VCE remembers for later investigation.',
    'Summaries provide charts and usage totals. Individual events provide detailed activity history and information used by the P25 Visualizer. Retention determines how far back you can look.'],
  'retained-statistics': ['Remove selected saved activity when you want to discard test data, clear unwanted history, or start collecting fresh statistics.',
    'Choose the system, channel, or activity involved, then review the affected records before confirming deletion.'],
  'remote-links': ['Receive P25 traffic from another location, or send your local reception to another VCE installation.',
    'Received feeds become channels here and use this receiver’s Alias Lists for names, recording, and streaming choices.'],
  'protocol-p25': ['Use this when a P25 system needs a manually supplied band plan for correct tuning.',
    'Profiles can apply to a whole system or a particular site. The channel’s Decoder settings determine whether it uses an override.'],
  display: ['Adjust how long Live continues to show a traffic row as active after its latest channel grant.',
    'This can make activity easier to follow on systems with different signaling behavior.'],
  users: ['Give regular listeners their own sign-in and manage their accounts as people join or leave.',
    'Page access determines which features are available to signed-in users. The primary administrator account is managed in the desktop application.'],
  access: ['Decide what visitors can see and which features require a listener or administrator sign-in.',
    'You can set access separately for viewing activity, listening to calls, and searching recordings, so the interface can suit the people using your receiver.']
});

const retentionCopy = Object.freeze({
  recordings: ['Choose how long saved audio remains available.',
    'Older managed recordings are deleted automatically, including previously saved managed calls after switching to Classic.'],
  activity: ['Choose how far back you can investigate radio activity.',
    'Shortening the period deletes history beyond the new limit. Saved audio has a separate retention setting under Recording settings.']
});

export function administrationChoices(canAccess) {
  return availableAdministrationSections(canAccess).map(section => ({ id: section.id, label: section.label,
    description: sectionCopy[section.id][0], group: section.group }));
}

export function selectedAdministrationSection(adminTab, canAccess) {
  const available = availableAdministrationSections(canAccess);
  return available.find(section => section.id === adminTab) || available[0] || null;
}

export function adminSteps(documentValue, preparePlayer, preparePage, { adminTab = 'computer', canAccess } = {}) {
  const chapter = selectedAdministrationSection(adminTab, canAccess);
  if (!chapter) return [];
  const focus = name => () => [...(documentValue?.querySelectorAll(`[data-guide-target="${name}"]`) || [])];
  const opening = focus(`admin-nav-${chapter.id}`);
  const panel = focus(`admin-${chapter.id}-panel`);
  const panelContext = ['audio-quality', 'access'].includes(chapter.id) ? () =>
    [...new Set(panel().flatMap(element => [element,
      ...(element.closest?.('.admin-settings-content')?.querySelectorAll('.ui-settings-panel') || [])]))] : panel;
  const steps = [
    { id: `${chapter.id}-open`, title: `Open ${chapter.label}`,
      paragraphs: [`Open ${chapter.label} from the Administration sidebar.`],
      targets: opening, contextTargets: contextualTargets(opening, '.admin-settings-branch') },
    { id: `${chapter.id}-purpose`, title: chapter.label, paragraphs: sectionCopy[chapter.id], targets: panel,
      contextTargets: panelContext }
  ];
  if (chapter.id === 'tuner-allocation') {
    const channel = focus('admin-tuner-allocation-channel');
    const back = focus('admin-tuner-allocation-back');
    steps.splice(1, 0, { id: 'tuner-allocation-channel', title: 'Choose a channel',
      paragraphs: ['Select a channel to inspect its frequencies and the tuners covering them.'],
      targets: channel, contextTargets: contextualTargets(channel, '.tuner-allocation-catalog') });
    steps.push({ id: 'tuner-allocation-back', title: 'Return to channels',
      paragraphs: ['Return to the channel list to inspect another channel.'],
      targets: back, contextTargets: contextualTargets(back, '.tuner-allocation-workspace') },
    { id: 'tuner-allocation-catalog', title: 'Tuner allocation',
      paragraphs: ['Choose another channel here to inspect its coverage.'],
      targets: panel, contextTargets: panel });
  }
  if (retentionCopy[chapter.id]) {
    const retention = focus(`admin-${chapter.id}-retention`);
    steps.push({ id: `${chapter.id}-retention`, title: chapter.id === 'recordings' ? 'Managed call retention' : 'Activity retention',
      paragraphs: retentionCopy[chapter.id], targets: retention,
      contextTargets: contextualTargets(retention, '.ui-settings-panel') });
  }
  return steps.map((step, index) => ({ ...step, adminTab: chapter.id, compact: true,
    nextLabel: index === steps.length - 1 ? 'Finish' : undefined,
    ...pageGuidePresentation('admin', step.id, preparePlayer, preparePage, { restoreScroll: true }) }));
}
