/**********************************************************************
Copyright (c) 2003 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
	TJDO - original version
	Andy Jefferson - equality operator
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.key;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.store.rdbms.table.Column;
import org.datanucleus.store.rdbms.table.Table;

/**
 * Abstract representation of a Key to a table.
 */
abstract class Key
{
    /** Name of the key. */
    protected String name;

    /** Table that the key applies to. */
    protected Table table;

    /** Columns that the key relates to. */
    protected List<Column> columns = new ArrayList<>();

    /**
     * Constructor.
     * @param table The table
     */
    protected Key(Table table)
    {
        this.table = table;
    }

    /**
     * Mutator for the key name.
     * @param name The key name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Accessor for the key name.
     * @return Key name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Accessor for the table
     * @return table
     */
    public Table getTable()
    {
        return table;
    }

    /**
     * Accessor for the columns that the key relates to.
     * @return the List of columns.
     */
    public List<Column> getColumns()
    {
        return Collections.unmodifiableList(columns);
    }

    /**
     * Accessor for the column list
     * @return The column list
     */
    public String getColumnList()
    {
        return getColumnList(columns);
    }

    /**
     * Accessor for the number of columns.
     * @return The number of columns.
     */
    public int getNumberOfColumns()
    {
        return columns.size();
    }

    /**
     * Class to add a column to the key
     * @param col The column to add
     */
    public void addColumn(Column col)
    {
        assertSameDatastoreObject(col);

        columns.add(col);
    }

    /**
     * Sets a column in a specified position <code>seq</code> for this index.
     * @param seq the specified position for the <code>col</code>
     * @param col the Column
     */
    public void setColumn(int seq, Column col)
    {
        assertSameDatastoreObject(col);

        setListMinimumSize(columns, seq + 1);

        if (columns.get(seq) != null)
        {
            throw new NucleusException("Key position #" + seq + " for " + table + " already set").setFatal();
        }

        columns.set(seq, col);
    }

    /**
     * Utility to assert if the column is for a different table.
     * @param col The column to compare with
     */
    protected void assertSameDatastoreObject(Column col)
    {
        if (!table.equals(col.getTable()))
        {
            throw new NucleusException("Cannot add " + col + " as key column for " + table).setFatal();
        }
    }
    
    /**
     * Hashcode operator.
     * @return The hashcode
     */
    public int hashCode()
    {
        return columns.hashCode();
    }

    /**
     * Equality operator.
     * @param obj Object to compare against
     * @return Whether they are equal.
     */
    public boolean equals(Object obj)
    {
        if (obj == this)
        {
            return true;
        }

        if (!(obj instanceof Key))
        {
            return false;
        }

        Key key = (Key)obj;

        // Check if all columns are present regardless of order
        return columns.containsAll(key.columns) && columns.size() == key.columns.size();
        // This will check on the same columns and the ordering
//        return columns.equals(key.columns);
    }

    // ------------------------------- Static Utilities -----------------------------

    protected static void setListMinimumSize(List<?> list, int size)
    {
        while (list.size() < size)
        {
            list.add(null);
        }
    }

    /**
     * Method to return the list of columns which the key applies to.
     * @param cols The columns.
     * @return The column list.
     */
    public static String getColumnList(List<Column> cols)
    {
        StringBuilder s = new StringBuilder("(");
        Iterator<Column> i = cols.iterator();
        while (i.hasNext())
        {
            Column col = i.next();

            if (col == null)
            {
                s.append('?');
            }
            else
            {
                s.append(col.getIdentifier());
            }

            if (i.hasNext())
            {
                s.append(',');
            }
        }

        s.append(')');

        return s.toString();
    }
}