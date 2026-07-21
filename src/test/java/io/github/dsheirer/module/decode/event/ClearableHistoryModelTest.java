/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JTable;
import javax.swing.event.TableModelEvent;
import org.junit.jupiter.api.Test;

class ClearableHistoryModelTest
{
    @Test
    void reportsTheFormerTailRowWhenHistoryOverflows()
    {
        TestHistoryModel model = new TestHistoryModel();
        model.setHistorySize(3);
        List<TableModelEvent> events = new ArrayList<>();
        model.addTableModelListener(events::add);
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);

        model.add("one");
        model.add("two");
        model.add("three");
        model.add("four");

        TableModelEvent deletion = events.stream().filter(event -> event.getType() == TableModelEvent.DELETE)
            .findFirst().orElseThrow();
        assertEquals(3, deletion.getFirstRow());
        assertEquals(3, deletion.getLastRow());
        assertEquals(3, model.getRowCount());
        assertEquals(3, table.getRowCount());
        assertEquals("four", model.getItem(0));
    }

    private static class TestHistoryModel extends ClearableHistoryModel<String>
    {
        @Override
        public int getColumnCount()
        {
            return 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex)
        {
            return getItem(rowIndex);
        }
    }
}
