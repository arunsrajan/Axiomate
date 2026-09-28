package com.github.axiomate.agentic.ide.ui.dialogs;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Table model whose first column is a selection checkbox and whose other columns are derived from row objects.
 */
public class CheckTableModel<T> extends AbstractTableModel {

    private final String[] columns;
    private final BiFunction<T, Integer, Object> valueFn;
    private final List<T> rows = new ArrayList<>();
    private final List<Boolean> checked = new ArrayList<>();
    private Predicate<T> checkable = t -> true;

    /**
     * @param columns column names after the checkbox column
     * @param valueFn (row, columnIndexWithoutCheckbox) -> value
     */
    public CheckTableModel(String[] columns, BiFunction<T, Integer, Object> valueFn) {
        this.columns = columns;
        this.valueFn = valueFn;
    }

    public void setCheckable(Predicate<T> checkable) {
        this.checkable = checkable;
    }

    public void setRows(List<T> newRows, Predicate<T> initiallyChecked) {
        rows.clear();
        checked.clear();
        for (T r : newRows) {
            rows.add(r);
            checked.add(checkable.test(r) && initiallyChecked.test(r));
        }
        fireTableDataChanged();
    }

    public T getRow(int index) {
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    public List<T> getChecked() {
        List<T> list = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (checked.get(i)) list.add(rows.get(i));
        }
        return list;
    }

    public void setAllChecked(boolean value) {
        for (int i = 0; i < rows.size(); i++) {
            checked.set(i, value && checkable.test(rows.get(i)));
        }
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length + 1;
    }

    @Override
    public String getColumnName(int column) {
        return column == 0 ? "" : columns[column - 1];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 0 ? Boolean.class : Object.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == 0 && checkable.test(rows.get(rowIndex));
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        return columnIndex == 0 ? checked.get(rowIndex) : valueFn.apply(rows.get(rowIndex), columnIndex - 1);
    }

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        if (columnIndex == 0) {
            checked.set(rowIndex, Boolean.TRUE.equals(aValue));
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
