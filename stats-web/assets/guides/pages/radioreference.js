import { pageGuidePresentation } from '../presentation.js?v=1';
import { contextualTargets } from '../context.js?v=1';

function targets(documentValue, ...names) {
  return () => names.flatMap(name => [...(documentValue?.querySelectorAll(
    `[data-guide-target="${name}"]`) || [])]);
}

export function radioReferenceSteps(documentValue, preparePlayer, preparePage) {
  const focus = (...names) => targets(documentValue, ...names);
  const contexts = {
    bookmark: '.radioreference-directory-item', 'bookmarks-button': '.ui-segmented',
    'saved-system': '.radioreference-directory-item', 'system-sections': '.radioreference-system-workspace',
    'site-button': 'tr', 'site-import': '.read-only-modal', 'channel-review-button': '.read-only-modal',
    'channel-preview': '.read-only-modal', 'channel-complete-close': '.read-only-modal',
    'talkgroups-button': '.radioreference-system-tabs', 'alias-list': '.radioreference-talkgroup-toolbar',
    comparison: 'tr', 'select-update': 'tr', 'import-selected': '.radioreference-talkgroup-actions',
    'alias-preview': '.read-only-modal', 'alias-complete-close': '.read-only-modal'
  };
  const steps = [
    { id: 'area-groups', title: 'Results grouped by area',
      targets: focus('rr-area-county', 'rr-area-state', 'rr-area-national'),
      paragraphs: ['Your county’s results appear alongside statewide and national services. Open a group to browse its systems and agencies.'] },
    { id: 'sort', title: 'Find recent changes', targets: focus('rr-sort'),
      paragraphs: ['Last updated brings recently revised RadioReference entries to the top of each group. Choose Alphabetical to browse by name.'] },
    { id: 'bookmark', title: 'Save a useful system', targets: focus('rr-bookmark'),
      paragraphs: ['Bookmark systems and agencies you return to regularly.'] },
    { id: 'bookmarks-button', title: 'Open your bookmarks', targets: focus('rr-bookmarks-button'),
      paragraphs: ['Open Bookmarks to return to your saved radio information.'] },
    { id: 'saved-system', title: 'Open a saved system', targets: focus('rr-saved-system'),
      paragraphs: ['Select County P25 to review its sites and talkgroups.'] },
    { id: 'system-sections', title: 'Receiving and naming', targets: focus('rr-sites', 'rr-talkgroups-button'),
      paragraphs: [], items: [
        { label: 'Sites & Channels', text: 'Bring in the frequencies VCE receives.' },
        { label: 'Talkgroups & Aliases', text: 'Bring in familiar names and connect received talkgroups with your alias settings.' }
      ] },
    { id: 'site-button', title: 'Choose a site', targets: focus('rr-site-button'),
      paragraphs: ['Select a site within receiving range to review its frequencies for import.'] },
    { id: 'site-import', title: 'Prepare the channel', targets: focus('rr-site-import'),
      paragraphs: ['VCE combines this site’s selected frequencies into one receiving channel. Choose the Alias List that will name the talkgroups heard on it.'] },
    { id: 'channel-review-button', title: 'Review before saving', targets: focus('rr-channel-review-button'),
      paragraphs: ['Open a summary of the channel you’re about to import.'] },
    { id: 'channel-preview', title: 'Check the receiving details',
      targets: focus('rr-channel-preview', 'rr-channel-apply'),
      paragraphs: ['Check the protocol and frequencies. Apply Channel saves this receiving configuration in Channel Setup.'] },
    { id: 'channel-complete-close', title: 'Return to the system', targets: focus('rr-channel-complete-close'),
      paragraphs: ['The channel is ready in Channel Setup. Close this window to continue with the system’s talkgroups.'] },
    { id: 'talkgroups-button', title: 'Bring in talkgroup names', targets: focus('rr-talkgroups-button'),
      paragraphs: ['Open Talkgroups & Aliases to add names or check your existing aliases for updates.'] },
    { id: 'alias-list', title: 'Choose the list to update', targets: focus('rr-alias-list'),
      paragraphs: ['Choose the Alias List you already use for this system. VCE compares RadioReference’s talkgroups with the aliases in that list.'] },
    { id: 'comparison', title: 'Read the comparison', targets: focus('rr-comparison'),
      paragraphs: [], items: [
        { label: 'Not present', text: 'Ready to add to this list.' },
        { label: 'Different', text: 'RadioReference’s name, description, or group differs from your saved alias.' },
        { label: 'Identical', text: 'The imported details already match.' }
      ] },
    { id: 'select-update', title: 'Choose an update', targets: focus('rr-select-update'),
      paragraphs: ['Select the talkgroups you want to add or update.'] },
    { id: 'import-selected', title: 'Review your selection', targets: focus('rr-import-selected'),
      paragraphs: ['Review the selected changes before applying them to your Alias List.'] },
    { id: 'alias-preview', title: 'Check exactly what changes', targets: focus('rr-alias-preview', 'rr-alias-apply'),
      paragraphs: ['Review the old and new details. Apply Import refreshes names, descriptions, and groups while keeping your recording, Scan List, and streaming choices.'] },
    { id: 'alias-complete-close', title: 'Return to the talkgroups', targets: focus('rr-alias-complete-close'),
      paragraphs: ['Close this window to continue reviewing the system.'], nextLabel: 'Finish' }
  ];
  return steps.map(step => ({ ...step,
    contextTargets: contextualTargets(step.targets, contexts[step.id]),
    ...pageGuidePresentation('radioreference', step.id, preparePlayer, preparePage)
  }));
}
