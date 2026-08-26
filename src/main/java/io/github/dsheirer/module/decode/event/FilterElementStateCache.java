/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import io.github.dsheirer.filter.Filter;
import io.github.dsheirer.filter.FilterElement;
import io.github.dsheirer.filter.FilterSet;
import io.github.dsheirer.filter.IFilter;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers filter-element choices by stable hierarchical class/name path so compatible choices survive rebuilding a
 * decoder-specific filter catalog.  Elements absent from the cache retain their enabled-by-default catalog state.
 */
final class FilterElementStateCache
{
    private final Map<String,Boolean> mEnabledStates = new HashMap<>();

    void capture(FilterSet<?> filterSet)
    {
        if(filterSet != null)
        {
            visit(filterSet, "", true);
        }
    }

    void restore(FilterSet<?> filterSet)
    {
        if(filterSet != null)
        {
            visit(filterSet, "", false);
        }
    }

    private void visit(IFilter<?> filter, String parentPath, boolean capture)
    {
        String filterPath = parentPath + '\u001f' + filter.getClass().getName() + '\u001e' + filter.getName();

        if(filter instanceof FilterSet<?> filterSet)
        {
            for(IFilter<?> child: filterSet.getFilters())
            {
                visit(child, filterPath, capture);
            }
        }
        else if(filter instanceof Filter<?,?> leaf)
        {
            for(FilterElement<?> element: leaf.getFilterElements())
            {
                String elementPath = filterPath + '\u001d' + element.getClass().getName() + '\u001c' +
                    element.getName();

                if(capture)
                {
                    mEnabledStates.put(elementPath, element.isEnabled());
                }
                else
                {
                    Boolean enabled = mEnabledStates.get(elementPath);

                    if(enabled != null)
                    {
                        element.setEnabled(enabled);
                    }
                }
            }
        }
    }
}
