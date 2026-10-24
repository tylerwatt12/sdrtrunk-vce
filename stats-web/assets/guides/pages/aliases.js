import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

function targets(documentValue, ...names) {
  return () => names.flatMap(name => [...(documentValue?.querySelectorAll(
    `[data-guide-target="${name}"]`) || [])]);
}

export function aliasesSteps(documentValue, preparePlayer, preparePage) {
  const focus = (...names) => targets(documentValue, ...names);
  const contexts = {
    views: '.alias-editor-view-switcher', 'alias-button': 'tr',
    'alias-editor': '.read-only-modal', 'alias-close': '.read-only-modal',
    'defaults-button': '.alias-list-summary', 'defaults-unmatched': '.read-only-modal',
    'defaults-new-button': '.read-only-modal', 'defaults-new': '.read-only-modal', 'defaults-close': '.read-only-modal',
    bulk: 'tr', 'scan-lists-button': '.alias-bulk-bar', 'scan-lists': '.read-only-modal',
    'clear-selection': '.alias-bulk-bar', 'activity-button': '.alias-editor-view-switcher',
    'custom-button': '.alias-editor-view-switcher', 'columns-button': '.section-title',
    'discover-button': '.alias-editor-view-switcher', 'configure-button': '.alias-editor-view-switcher',
    transfer: '.alias-list-summary'
  };
  const steps = [
    { id: 'lists', title: 'Your Alias Lists', targets: focus('aliases-lists'),
      paragraphs: ['Alias Lists connect recognizable names and call settings to your channels. Select a list here to explore its aliases.'] },
    { id: 'views', title: 'Four views of your aliases',
      targets: focus('aliases-configure', 'aliases-activity', 'aliases-custom', 'aliases-discover'), paragraphs: [],
      items: [
        { label: 'Configure', text: 'Manage alias names, identifiers, and call settings.' },
        { label: 'Activity', text: 'See which aliases are being used.' },
        { label: 'Custom', text: 'Choose the columns for your own view.' },
        { label: 'Discover', text: 'Find heard talkgroups that have no defined alias.' }
      ] },
    { id: 'alias-button', title: 'Review an alias', targets: focus('aliases-open-alias'),
      paragraphs: ['Select an alias name to review its identifiers and call handling.'] },
    { id: 'alias-editor', title: 'Inside an alias', targets: focus('aliases-editor-tabs'), paragraphs: [],
      items: [
        { label: 'Basics', text: 'Holds the name and appearance.' },
        { label: 'Identifier', text: 'Defines which radio activity (like talkgroup ID or radio ID) matches.' },
        { label: 'Call Handling', text: 'Controls recording, Scan List membership, and streaming.' },
        { label: 'Activity', text: 'Shows what has been observed for this alias.' }
      ] },
    { id: 'alias-close', title: 'Return to your Alias List', targets: focus('aliases-editor-close'),
      paragraphs: ['Close this editor to return to your Alias List.'] },
    { id: 'defaults-button', title: 'Choose the list’s starting behavior', targets: focus('aliases-defaults'),
      paragraphs: ['Open Call Handling Defaults to choose how this list handles unknown talkgroups and newly added aliases.'] },
    { id: 'defaults-unmatched', title: 'Call Handling Defaults: Unmatched Calls',
      targets: focus('aliases-defaults-content'),
      paragraphs: ['This is where you configure what to do with unknown talkgroups.'] },
    { id: 'defaults-new-button', title: 'Review the starting settings for new aliases', targets: focus('aliases-defaults-new-tab'),
      paragraphs: ['Select New Aliases to review the starting settings for aliases you add or import.'] },
    { id: 'defaults-new', title: 'Call Handling Defaults: New Aliases',
      targets: focus('aliases-defaults-content'),
      paragraphs: ['This is where you configure what settings newly added or RadioReference-imported aliases use.'] },
    { id: 'defaults-close', title: 'Return to your Alias List', targets: focus('aliases-defaults-close'),
      paragraphs: ['Close Call Handling Defaults to return to your Alias List.'] },
    { id: 'bulk', title: 'Work with several aliases', targets: focus('aliases-selection'),
      paragraphs: ['Select the aliases you want to change together.'] },
    { id: 'scan-lists-button', title: 'Change their listening groups', targets: focus('aliases-bulk-scan-lists'),
      paragraphs: ['Open Scan Lists to change membership for the selected aliases.'] },
    { id: 'scan-lists', title: 'Choose Scan List membership', targets: focus('aliases-scan-list-dialog'),
      paragraphs: ['Choose a Scan List and add or remove these aliases. Apply Change returns you to the Alias List.'] },
    { id: 'clear-selection', title: 'Return to browsing your aliases', targets: focus('aliases-clear-selection'),
      paragraphs: ['Clear selection to return to browsing your Alias List.'] },
    { id: 'activity-button', title: 'Open the activity view', targets: focus('aliases-activity'),
      paragraphs: ['Open Activity to see which aliases are being used.'] },
    { id: 'activity', title: 'Activity: see what’s being used', targets: focus('aliases-activity-columns'),
      paragraphs: ["This page helps you determine which aliases are used. VCE records statistics on what's heard and displays them here."] },
    { id: 'custom-button', title: 'Open your Custom view', targets: focus('aliases-custom'),
      paragraphs: ['Open Custom to arrange the information you use most.'] },
    { id: 'custom', title: 'Custom: choose your view', targets: focus('aliases-table'),
      paragraphs: ['Choose the columns that matter the most, to create a view tailored to your workflow.'] },
    { id: 'columns-button', title: 'Choose your columns', targets: focus('aliases-columns'),
      paragraphs: ['Choose the information shown in your Custom view.'] },
    { id: 'discover-button', title: 'Open Discover', targets: focus('aliases-discover'),
      paragraphs: ['Open Discover to review heard talkgroups that need names.'] },
    { id: 'discover', title: 'Discover: find identities worth naming', targets: focus('aliases-discover-identities'),
      paragraphs: ['Explore talkgroups heard on your channels that have no defined alias.',
        'This helps you identify and name new and unknown radio traffic.'] },
    { id: 'configure-button', title: 'Return to Configure', targets: focus('aliases-configure'),
      paragraphs: ['Return to Configure to manage, import, and export your aliases.'] },
    { id: 'transfer', title: 'Create, import, and export', targets: focus('aliases-add', 'aliases-import', 'aliases-export'),
      paragraphs: ['Add, import, or export aliases here.',
        'Add an alias from scratch, or import from RadioReference or a file.',
        'Export a report for analysis or a configuration file you can import again later.'], nextLabel: 'Finish' }
  ];
  return steps.map(step => ({ ...step,
    contextTargets: contextualTargets(step.targets, contexts[step.id]),
    ...pageGuidePresentation('aliases', step.id, preparePlayer, preparePage)
  }));
}
