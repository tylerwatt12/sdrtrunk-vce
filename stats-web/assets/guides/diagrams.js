// Central vector illustrations for guide concepts. The diagram uses fictional examples;
// page actions and dialogs continue to come from their normal production builders.
const svgNamespace = 'http://www.w3.org/2000/svg';

// Coordinates preserve the approved County/City relationship chart. Each route names
// both endpoints so its listening membership can be checked independently of color.
const scanListsDiagram = [
  ["defs",{},[["marker",{"id": "law-arrow","markerWidth": "24","markerHeight": "24","refX": "12","refY": "6","orient": "auto","markerUnits": "userSpaceOnUse","viewBox": "0 0 12 12"},[["path",{"d": "M1 1 L12 6 L1 11 Z","fill": "var(--guide-diagram-law)"},""]]],["marker",{"id": "fire-arrow","markerWidth": "24","markerHeight": "24","refX": "12","refY": "6","orient": "auto","markerUnits": "userSpaceOnUse","viewBox": "0 0 12 12"},[["path",{"d": "M1 1 L12 6 L1 11 Z","fill": "var(--guide-diagram-fire)"},""]]],["marker",{"id": "uses-arrow","markerWidth": "24","markerHeight": "24","refX": "12","refY": "6","orient": "auto","markerUnits": "userSpaceOnUse","viewBox": "0 0 12 12"},[["path",{"d": "M1 1 L12 6 L1 11 Z","fill": "var(--guide-diagram-uses)"},""]]],["symbol",{"id": "radio","viewBox": "0 0 32 32"},[["g",{"fill": "none","stroke": "currentColor","stroke-width": "2.2","stroke-linecap": "round","stroke-linejoin": "round"},[["path",{"d": "M16 10 L6 28 M16 10 L26 28 M9 23 H23 M12 18 H20 M16 10 V28"},""],["circle",{"cx": "16","cy": "8","r": "2","fill": "currentColor"},""],["path",{"d": "M9 4 A9 9 0 0 0 9 13 M23 4 A9 9 0 0 1 23 13 M5 1 A14 14 0 0 0 5 17 M27 1 A14 14 0 0 1 27 17"},""]]]]],["symbol",{"id": "folder","viewBox": "0 0 32 32"},[["path",{"d": "M3 7 Q3 4 6 4 H13 L17 8 H27 Q30 8 30 11 V26 Q30 29 27 29 H6 Q3 29 3 26 Z","fill": "currentColor"},""],["path",{"d": "M4 11 H29","stroke": "var(--guide-diagram-node)","opacity": ".5","stroke-width": "2"},""]]],["symbol",{"id": "shield","viewBox": "0 0 32 32"},[["path",{"d": "M16 3 L27 8 V16 Q27 24 16 30 Q5 24 5 16 V8 Z","fill": "none","stroke": "currentColor","stroke-width": "2.5"},""],["path",{"d": "M16 9 V22 M11 15 H21","fill": "none","stroke": "currentColor","stroke-width": "2.2","stroke-linecap": "round"},""]]],["symbol",{"id": "flame","viewBox": "0 0 32 32"},[["path",{"d": "M17 2 C20 10 27 12 27 20 C27 27 22 31 16 31 C9 31 5 27 5 21 C5 16 8 13 11 10 C11 15 14 17 15 15 C17 12 13 8 17 2 Z","fill": "currentColor"},""],["path",{"d": "M16 18 C20 22 21 24 19 27 C17 29 13 28 12 25 C11 22 14 21 16 18 Z","fill": "var(--guide-diagram-node)","opacity": ".8"},""]]],["symbol",{"id": "tag","viewBox": "0 0 32 32"},[["path",{"d": "M3 18 L17 4 H28 V15 L14 29 Z","fill": "currentColor"},""],["circle",{"cx": "23","cy": "9","r": "2.5","fill": "var(--guide-diagram-node)"},""]]],["symbol",{"id": "speaker","viewBox": "0 0 48 48"},[["path",{"d": "M5 18 H14 L26 8 V40 L14 30 H5 Z","fill": "currentColor"},""],["path",{"d": "M32 15 Q41 24 32 33 M38 8 Q53 24 38 40","fill": "none","stroke": "currentColor","stroke-width": "3","stroke-linecap": "round"},""]]]]],
  ["text",{"x": "60","y": "229","font-size": "30","font-weight": "700","fill": "var(--ink)","text-anchor": "start"},"Channels"],
  ["text",{"x": "60","y": "263","font-size": "24","font-weight": "400","fill": "var(--muted)","text-anchor": "start"},"Receive radio traffic"],
  ["text",{"x": "410","y": "229","font-size": "30","font-weight": "700","fill": "var(--ink)","text-anchor": "start"},"Alias Lists & aliases"],
  ["text",{"x": "410","y": "263","font-size": "24","font-weight": "400","fill": "var(--muted)","text-anchor": "start"},"Give calls familiar names"],
  ["text",{"x": "1100","y": "229","font-size": "30","font-weight": "700","fill": "var(--ink)","text-anchor": "start"},"Scan Lists"],
  ["text",{"x": "1100","y": "263","font-size": "24","font-weight": "400","fill": "var(--muted)","text-anchor": "start"},"Group related calls"],
  ["text",{"x": "1515","y": "229","font-size": "30","font-weight": "700","fill": "var(--ink)","text-anchor": "start"},"Player"],
  ["text",{"x": "1515","y": "263","font-size": "24","font-weight": "400","fill": "var(--muted)","text-anchor": "start"},"Choose what to hear"],
  ["rect",{"x": "40","y": "292","width": "300","height": "660","rx": "22","fill": "var(--guide-diagram-lane)"},""],
  ["rect",{"x": "390","y": "292","width": "410","height": "660","rx": "22","fill": "var(--guide-diagram-lane)"},""],
  ["rect",{"x": "1080","y": "292","width": "365","height": "660","rx": "22","fill": "var(--guide-diagram-lane)"},""],
  ["rect",{"x": "1495","y": "292","width": "285","height": "660","rx": "22","fill": "var(--guide-diagram-lane)"},""],
  ["rect",{"id": "channel-county-north-site","x": "60","y": "350","width": "260","height": "80","rx": "14","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#radio","x": "78","y": "374","width": "32","height": "32","color": "var(--guide-diagram-fire)"},""],
  ["text",{"x": "124","font-size": "24","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "channel-county-north-site"},[["tspan",{"x": "124","y": "386"},"County North"],["tspan",{"x": "124","y": "415"},"Site"]]],
  ["rect",{"id": "channel-county-south-site","x": "60","y": "470","width": "260","height": "80","rx": "14","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#radio","x": "78","y": "494","width": "32","height": "32","color": "var(--guide-diagram-fire)"},""],
  ["text",{"x": "124","font-size": "24","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "channel-county-south-site"},[["tspan",{"x": "124","y": "506"},"County South"],["tspan",{"x": "124","y": "535"},"Site"]]],
  ["rect",{"id": "channel-city-site","x": "60","y": "744","width": "260","height": "80","rx": "14","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#radio","x": "78","y": "768","width": "32","height": "32","color": "var(--guide-diagram-fire)"},""],
  ["text",{"x": "124","y": "792","font-size": "24","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "channel-city-site"},"City Site"],
  ["rect",{"x": "410","y": "310","width": "370","height": "280","rx": "20","fill": "var(--guide-diagram-group)"},""],
  ["use",{"href": "#folder","x": "432","y": "332","width": "32","height": "32","color": "var(--muted)"},""],
  ["text",{"x": "480","y": "359","font-size": "30","font-weight": "700","fill": "var(--ink)","text-anchor": "start"},"County P25"],
  ["rect",{"id": "alias-sheriff","x": "430","y": "382","width": "330","height": "72","rx": "12","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#tag","x": "448","y": "405","width": "26","height": "26","color": "var(--guide-diagram-law)"},""],
  ["text",{"x": "491","y": "427","font-size": "26","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "alias-sheriff"},"Sheriff"],
  ["rect",{"id": "alias-regional-fire","x": "430","y": "482","width": "330","height": "72","rx": "12","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#tag","x": "448","y": "505","width": "26","height": "26","color": "var(--guide-diagram-fire)"},""],
  ["text",{"x": "491","y": "527","font-size": "26","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "alias-regional-fire"},"Regional Fire"],
  ["rect",{"x": "410","y": "634","width": "370","height": "280","rx": "20","fill": "var(--guide-diagram-group)"},""],
  ["use",{"href": "#folder","x": "432","y": "656","width": "32","height": "32","color": "var(--muted)"},""],
  ["text",{"x": "480","y": "683","font-size": "30","font-weight": "700","fill": "var(--ink)","text-anchor": "start"},"City P25"],
  ["rect",{"id": "alias-police-department","x": "430","y": "706","width": "330","height": "72","rx": "12","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#tag","x": "448","y": "729","width": "26","height": "26","color": "var(--guide-diagram-law)"},""],
  ["text",{"x": "491","y": "751","font-size": "26","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "alias-police-department"},"Police Department"],
  ["rect",{"id": "alias-city-fire","x": "430","y": "806","width": "330","height": "72","rx": "12","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#tag","x": "448","y": "829","width": "26","height": "26","color": "var(--guide-diagram-fire)"},""],
  ["text",{"x": "491","y": "851","font-size": "26","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "alias-city-fire"},"City Fire"],
  ["rect",{"x": "1100","y": "310","width": "325","height": "280","rx": "20","fill": "var(--guide-diagram-law-fill)"},""],
  ["use",{"href": "#shield","x": "1122","y": "332","width": "32","height": "32","color": "var(--guide-diagram-law)"},""],
  ["text",{"x": "1170","y": "357","font-size": "26","font-weight": "700","fill": "var(--guide-diagram-law)","text-anchor": "start"},"Law Enforcement"],
  ["rect",{"id": "member-law-sheriff","x": "1120","y": "382","width": "285","height": "72","rx": "12","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#tag","x": "1136","y": "406","width": "24","height": "24","color": "var(--guide-diagram-law)"},""],
  ["text",{"x": "1173","y": "427","font-size": "24","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "member-law-sheriff"},"Sheriff"],
  ["rect",{"id": "member-law-police-department","x": "1120","y": "482","width": "285","height": "72","rx": "12","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#tag","x": "1136","y": "506","width": "24","height": "24","color": "var(--guide-diagram-law)"},""],
  ["text",{"x": "1173","y": "527","font-size": "24","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "member-law-police-department"},"Police Department"],
  ["rect",{"x": "1100","y": "655","width": "325","height": "280","rx": "20","fill": "var(--guide-diagram-fire-fill)"},""],
  ["use",{"href": "#flame","x": "1122","y": "677","width": "32","height": "32","color": "var(--guide-diagram-fire)"},""],
  ["text",{"x": "1170","y": "702","font-size": "26","font-weight": "700","fill": "var(--guide-diagram-fire)","text-anchor": "start"},"Fire/EMS"],
  ["rect",{"id": "member-fire-regional-fire","x": "1120","y": "727","width": "285","height": "72","rx": "12","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#tag","x": "1136","y": "751","width": "24","height": "24","color": "var(--guide-diagram-fire)"},""],
  ["text",{"x": "1173","y": "772","font-size": "24","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "member-fire-regional-fire"},"Regional Fire"],
  ["rect",{"id": "member-fire-city-fire","x": "1120","y": "827","width": "285","height": "72","rx": "12","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#tag","x": "1136","y": "851","width": "24","height": "24","color": "var(--guide-diagram-fire)"},""],
  ["text",{"x": "1173","y": "872","font-size": "24","font-weight": "600","fill": "var(--ink)","text-anchor": "start","data-container": "member-fire-city-fire"},"City Fire"],
  ["path",{"d": "M320 390 H338 Q360 390 360 412 V450","fill": "none","stroke": "var(--guide-diagram-uses)","stroke-width": "7","stroke-linecap": "round","stroke-linejoin": "round"},""],
  ["path",{"d": "M320 510 H338 Q360 510 360 488 V450","fill": "none","stroke": "var(--guide-diagram-uses)","stroke-width": "7","stroke-linecap": "round","stroke-linejoin": "round"},""],
  ["path",{"d": "M360 450 H410","fill": "none","stroke": "var(--guide-diagram-uses)","stroke-width": "7","stroke-linecap": "round","stroke-linejoin": "round","marker-end": "url(#uses-arrow)"},""],
  ["circle",{"cx": "360","cy": "450","r": "5","fill": "var(--guide-diagram-uses)"},""],
  ["path",{"d": "M320 784 H410","fill": "none","stroke": "var(--guide-diagram-uses)","stroke-width": "7","stroke-linecap": "round","stroke-linejoin": "round","marker-end": "url(#uses-arrow)"},""],
  ["text",{"x": "360","y": "348","font-size": "24","font-weight": "600","fill": "var(--muted)","text-anchor": "middle"},"Uses"],
  ["text",{"x": "360","y": "760","font-size": "24","font-weight": "600","fill": "var(--muted)","text-anchor": "middle"},"Uses"],
  ["text",{"x": "940","y": "320","font-size": "24","font-weight": "700","fill": "var(--muted)","text-anchor": "middle","letter-spacing": "1.1"},"INCLUDED IN"],
  ["path",{"d": "M760 518 H835 Q865 518 865 548 V733 Q865 763 895 763 H1120","fill": "none","stroke": "var(--guide-diagram-fire)","stroke-width": "8","stroke-linecap": "round","stroke-linejoin": "round","marker-end": "url(#fire-arrow)","id": "route-regional-fire","data-source": "alias-regional-fire","data-target": "member-fire-regional-fire","data-start": "760,518","data-end": "1120,763","data-guide-diagram-route": "regional-fire"},""],
  ["path",{"d": "M760 418 H1120","fill": "none","stroke": "var(--guide-diagram-law)","stroke-width": "8","stroke-linecap": "round","stroke-linejoin": "round","marker-end": "url(#law-arrow)","id": "route-sheriff","data-source": "alias-sheriff","data-target": "member-law-sheriff","data-start": "760,418","data-end": "1120,418","data-guide-diagram-route": "sheriff"},""],
  ["path",{"d": "M760 842 H960 Q985 842 985 852 Q985 863 1010 863 H1120","fill": "none","stroke": "var(--guide-diagram-fire)","stroke-width": "8","stroke-linecap": "round","stroke-linejoin": "round","marker-end": "url(#fire-arrow)","id": "route-city-fire","data-source": "alias-city-fire","data-target": "member-fire-city-fire","data-start": "760,842","data-end": "1120,863","data-guide-diagram-route": "city-fire"},""],
  ["path",{"d": "M760 742 H850 Q865 714 880 742 H995 Q1025 742 1025 712 V548 Q1025 518 1055 518 H1120","fill": "none","stroke": "var(--guide-diagram-background)","stroke-width": "20","stroke-linecap": "round","stroke-linejoin": "round"},""],
  ["path",{"d": "M760 742 H850 Q865 714 880 742 H995 Q1025 742 1025 712 V548 Q1025 518 1055 518 H1120","fill": "none","stroke": "var(--guide-diagram-law)","stroke-width": "8","stroke-linecap": "round","stroke-linejoin": "round","marker-end": "url(#law-arrow)","id": "route-police-department","data-source": "alias-police-department","data-target": "member-law-police-department","data-start": "760,742","data-end": "1120,518","data-guide-diagram-route": "police-department"},""],
  ["rect",{"x": "1515","y": "390","width": "245","height": "425","rx": "20","fill": "var(--guide-diagram-node)"},""],
  ["use",{"href": "#speaker","x": "1604","y": "422","width": "60","height": "60","color": "var(--ink)"},""],
  ["text",{"x": "1637.5","y": "519","font-size": "24","font-weight": "400","fill": "var(--muted)","text-anchor": "middle"},"Listen to"],
  ["rect",{"id": "player-law","x": "1535","y": "542","width": "205","height": "76","rx": "12","fill": "var(--guide-diagram-law-fill)"},""],
  ["text",{"x": "1637.5","y": "588","font-size": "24","font-weight": "700","fill": "var(--guide-diagram-law)","text-anchor": "middle","data-container": "player-law"},"Law Enforcement"],
  ["rect",{"id": "player-fire","x": "1535","y": "648","width": "205","height": "76","rx": "12","fill": "var(--guide-diagram-fire-fill)"},""],
  ["text",{"x": "1637.5","y": "694","font-size": "24","font-weight": "700","fill": "var(--guide-diagram-fire)","text-anchor": "middle","data-container": "player-fire"},"Fire/EMS"],
  ["text",{"x": "1637.5","y": "777","font-size": "24","font-weight": "400","fill": "var(--muted)","text-anchor": "middle"},"Select one or both"],
  ["path",{"d": "M1425 450 H1445 Q1480 450 1480 485 V545 Q1480 580 1515 580 H1535","fill": "none","stroke": "var(--guide-diagram-law)","stroke-width": "8","stroke-linecap": "round","stroke-linejoin": "round","marker-end": "url(#law-arrow)"},""],
  ["path",{"d": "M1425 795 H1445 Q1480 795 1480 760 V721 Q1480 686 1515 686 H1535","fill": "none","stroke": "var(--guide-diagram-fire)","stroke-width": "8","stroke-linecap": "round","stroke-linejoin": "round","marker-end": "url(#fire-arrow)"},""]
];

const diagramDefinitions = {
  'scan-lists': {
    title: 'County and city aliases grouped into Scan Lists',
    description: 'County North Site and County South Site share the County P25 Alias List, containing Sheriff and Regional Fire. City Site uses the City P25 Alias List, containing Police Department and City Fire. Sheriff and Police Department belong to Law Enforcement. Regional Fire and City Fire belong to Fire/EMS. Select one or both Scan Lists in the player.',
    viewBox: '0 185 1800 790',
    content: scanListsDiagram
  }
};

export function createGuideDiagram(name, { document, idPrefix }) {
  const definition = diagramDefinitions[name];
  if (!definition) return null;
  const scopeId = value => `${idPrefix}-${value}`;
  const make = ([tag, attributes, content]) => {
    const element = document.createElementNS(svgNamespace, tag);
    Object.entries(attributes).forEach(([key, original]) => {
      let value = original;
      if (key === 'id') value = scopeId(value);
      else if (key === 'href' && value.startsWith('#')) value = `#${scopeId(value.slice(1))}`;
      else if (key === 'marker-end') value = value.replace(/#([^)]*)/, (_match, id) => `#${scopeId(id)}`);
      element.setAttribute(key, value);
    });
    if (Array.isArray(content)) content.forEach(child => element.append(make(child)));
    else element.textContent = content;
    return element;
  };
  const diagram = document.createElement('div');
  diagram.className = 'ui-setup-guide-diagram';
  diagram.dataset.guideDiagram = name;
  const svg = make(['svg', { viewBox: definition.viewBox, role: 'img',
    'aria-labelledby': scopeId('title'), 'aria-describedby': scopeId('description') }, [
    ['title', { id: 'title' }, definition.title],
    ['desc', { id: 'description' }, definition.description],
    ...definition.content
  ]]);
  diagram.append(svg);
  return diagram;
}
