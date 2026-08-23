/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import io.github.dsheirer.filter.AllPassFilter;
import io.github.dsheirer.filter.Filter;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.filter.FilterElement;
import io.github.dsheirer.filter.FilterSet;
import io.github.dsheirer.filter.IFilter;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.DecoderFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Builds one immutable Java-filter catalog and its matching observer-side message classifier. */
final class MessageFilterCatalog
{
    private static final String KEY_PREFIX = "message";
    private static final Classifier FALLBACK = fallbackClassifier();

    private MessageFilterCatalog()
    {
    }

    static Classifier fromModules(List<Module> modules, int[] timeslots)
    {
        return fromFilterSet(DecoderFactory.getMessageFilters(List.copyOf(modules)), timeslots);
    }

    static Classifier fromFilterSet(FilterSet<IMessage> filterSet, int[] timeslots)
    {
        Objects.requireNonNull(filterSet, "filterSet cannot be null");
        List<FilterCatalog.Node> groups = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        List<IFilter<IMessage>> filters = filterSet.getFilters();

        for(int index = 0; index < filters.size(); index++)
        {
            BuiltFilter built = build(filters.get(index), KEY_PREFIX + "/" + index);
            groups.add(built.node());
            rules.add(built.rule());
        }

        FilterCatalog catalog = FilterCatalog.create(groups, normalizedTimeslots(timeslots));
        return new Classifier(catalog, List.copyOf(rules));
    }

    static Classifier fallback()
    {
        return FALLBACK;
    }

    private static Classifier fallbackClassifier()
    {
        FilterSet<IMessage> filters = new FilterSet<>("Message Filters");
        filters.addFilter(new AllPassFilter<>("All Other Messages"));
        return fromFilterSet(filters, new int[0]);
    }

    private static BuiltFilter build(IFilter<IMessage> filter, String path)
    {
        if(filter instanceof FilterSet<?> filterSet)
        {
            @SuppressWarnings("unchecked")
            List<IFilter<IMessage>> children = ((FilterSet<IMessage>)filterSet).getFilters();

            if(children.isEmpty())
            {
                throw new IllegalStateException("Message filter set has no children: " + filter.getName());
            }

            List<FilterCatalog.Node> nodes = new ArrayList<>();
            List<Rule> rules = new ArrayList<>();

            for(int index = 0; index < children.size(); index++)
            {
                BuiltFilter child = build(children.get(index), path + "/" + index);
                nodes.add(child.node());
                rules.add(child.rule());
            }

            return new BuiltFilter(new FilterCatalog.Node(path, label(filter.getName(), "Messages"), nodes),
                new SetRule(filter, List.copyOf(rules)));
        }
        else if(filter instanceof Filter<?,?> leafFilter)
        {
            return buildLeaf(filter, leafFilter, path);
        }

        throw new IllegalArgumentException("Unsupported filter type: " + filter.getClass().getName());
    }

    private static BuiltFilter buildLeaf(IFilter<IMessage> typedFilter, Filter<?,?> filter, String path)
    {
        List<FilterElement<?>> elements = new ArrayList<>(filter.getFilterElements());

        if(elements.isEmpty())
        {
            throw new IllegalStateException("Message filter has no choices: " + typedFilter.getName());
        }

        elements.sort(Comparator.comparing(MessageFilterCatalog::elementLabel)
            .thenComparing(MessageFilterCatalog::elementIdentity));

        Map<String,Integer> labelCounts = new HashMap<>();

        for(FilterElement<?> element: elements)
        {
            labelCounts.merge(elementLabel(element), 1, Integer::sum);
        }

        List<FilterCatalog.Node> leaves = new ArrayList<>();
        Map<Object,Match> matches = new LinkedHashMap<>();

        for(int index = 0; index < elements.size(); index++)
        {
            FilterElement<?> element = elements.get(index);
            String baseLabel = elementLabel(element);
            String friendlyLabel = labelCounts.getOrDefault(baseLabel, 0) > 1 ?
                baseLabel + " (" + disambiguator(element.getElement()) + ")" : baseLabel;
            String key = path + "/" + index;
            Match match = new Match(key, friendlyLabel);
            leaves.add(new FilterCatalog.Node(key, friendlyLabel, List.of()));
            matches.put(element.getElement(), match);
        }

        @SuppressWarnings("unchecked")
        Function<IMessage,Object> extractor = (Function<IMessage,Object>)(Function<?,?>)filter.getKeyExtractor();
        return new BuiltFilter(new FilterCatalog.Node(path, label(typedFilter.getName(), "Messages"), leaves),
            new LeafRule(typedFilter, extractor, Map.copyOf(matches)));
    }

    private static List<Integer> normalizedTimeslots(int[] timeslots)
    {
        if(timeslots == null || timeslots.length == 0)
        {
            return List.of();
        }

        return java.util.Arrays.stream(timeslots).filter(timeslot -> timeslot > 0).distinct().sorted().boxed().toList();
    }

    private static String elementLabel(FilterElement<?> element)
    {
        return label(element != null ? element.getName() : null, "Unknown");
    }

    private static String elementIdentity(FilterElement<?> element)
    {
        Object value = element != null ? element.getElement() : null;

        if(value instanceof Enum<?> enumValue)
        {
            return enumValue.getDeclaringClass().getName() + "#" + enumValue.name();
        }

        return value != null ? value.getClass().getName() + "#" + value : "null";
    }

    private static String disambiguator(Object value)
    {
        if(value instanceof Enum<?> enumValue)
        {
            String name = enumValue.name();

            if(name.contains("INBOUND"))
            {
                return "Inbound";
            }
            else if(name.contains("OUTBOUND"))
            {
                return "Outbound";
            }

            return title(name);
        }

        return value != null ? value.getClass().getSimpleName() : "Unknown";
    }

    private static String title(String value)
    {
        String[] words = value.toLowerCase(Locale.ROOT).split("_+");
        StringBuilder result = new StringBuilder();

        for(String word: words)
        {
            if(!word.isBlank())
            {
                if(!result.isEmpty())
                {
                    result.append(' ');
                }

                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }

        return !result.isEmpty() ? result.toString() : "Unknown";
    }

    private static String label(String value, String fallback)
    {
        return value != null && !value.isBlank() ? value.strip() : fallback;
    }

    record Match(String filterKey, String filterLabel)
    {
    }

    static final class Classifier
    {
        private final FilterCatalog mCatalog;
        private final List<Rule> mRules;

        private Classifier(FilterCatalog catalog, List<Rule> rules)
        {
            mCatalog = catalog;
            mRules = rules;
        }

        FilterCatalog catalog()
        {
            return mCatalog;
        }

        Match classify(IMessage message)
        {
            if(message != null)
            {
                for(Rule rule: mRules)
                {
                    Match match = rule.match(message);

                    if(match != null)
                    {
                        return match;
                    }
                }
            }

            return null;
        }
    }

    private interface Rule
    {
        Match match(IMessage message);
    }

    private record SetRule(IFilter<IMessage> filter, List<Rule> children) implements Rule
    {
        @Override
        public Match match(IMessage message)
        {
            try
            {
                if(!filter.canProcess(message))
                {
                    return null;
                }

                for(Rule child: children)
                {
                    Match match = child.match(message);

                    if(match != null)
                    {
                        return match;
                    }
                }
            }
            catch(RuntimeException _)
            {
                //A malformed observer-only filter must not interrupt message delivery; a later catch-all may match.
            }

            return null;
        }
    }

    private record LeafRule(IFilter<IMessage> filter, Function<IMessage,Object> extractor,
                            Map<Object,Match> matches) implements Rule
    {
        @Override
        public Match match(IMessage message)
        {
            try
            {
                return filter.canProcess(message) ? matches.get(extractor.apply(message)) : null;
            }
            catch(RuntimeException _)
            {
                return null;
            }
        }
    }

    private record BuiltFilter(FilterCatalog.Node node, Rule rule)
    {
    }
}
