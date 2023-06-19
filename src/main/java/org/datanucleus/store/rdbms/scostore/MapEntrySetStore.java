/**********************************************************************
Copyright (c) 2007 Andy Jefferson and others. All rights reserved.
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
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.scostore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlan;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.mapping.java.EmbeddedKeyPCMapping;
import org.datanucleus.store.rdbms.mapping.java.EmbeddedValuePCMapping;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedPCMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedReferenceMapping;
import org.datanucleus.store.rdbms.JDBCUtils;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.rdbms.query.StatementMappingIndex;
import org.datanucleus.store.rdbms.query.StatementParameterMapping;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLStatementHelper;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.SQLJoin.JoinType;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.rdbms.table.MapTable;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.store.types.scostore.MapStore;
import org.datanucleus.store.types.scostore.SetStore;
import org.datanucleus.util.Localiser;

/**
 * RDBMS-specific implementation of a SetStore for map entries.
 */
class MapEntrySetStore<K, V> extends BaseContainerStore implements SetStore<Map.Entry<K, V>>
{
    /** Table containing the key and value forming the entry. This may be a join table, or key table (with value "FK"), or value table (with key "FK"). */
    protected Table mapTable;

    /** The backing store for the Map. */
    protected MapStore<K, V> mapStore;

    /** Mapping for the key. */
    protected JavaTypeMapping keyMapping;

    /** Mapping for the value. */
    protected JavaTypeMapping valueMapping;

    private String sizeStmt;

    /** SQL statement to use for retrieving data from the map (normal). */
    private String iteratorSelectStmtSql = null;

    /** SQL statement to use for retrieving data from the map (locking). */
    private String iteratorSelectStmtLockedSql = null;

    private int[] iteratorKeyResultCols = null;
    private int[] iteratorValueResultCols = null;

    private StatementParameterMapping iteratorMappingParams = null;

    /**
     * Constructor for a store of the entries in a map when represented in a join table.
     * @param mapTable Table for the map
     * @param mapStore Backing store for the Map using join table
     * @param clr ClassLoader resolver
     */
    MapEntrySetStore(MapTable mapTable, JoinMapStore<K, V> mapStore, ClassLoaderResolver clr)
    {
        super(mapTable.getStoreManager(), clr);

        this.mapTable = mapTable;
        this.mapStore = mapStore;
        this.ownerMapping = mapTable.getOwnerMapping();
        this.keyMapping = mapTable.getKeyMapping();
        this.valueMapping = mapTable.getValueMapping();
        this.ownerMemberMetaData = mapTable.getOwnerMemberMetaData();
    }

    /**
     * Constructor for a store of the entries in a map when represented by either the key table or value table.
     * @param mapTable The table storing the map relation (key table or value table)
     * @param mapStore The backing store for the FK map itself
     * @param clr ClassLoader resolver
     */
    MapEntrySetStore(DatastoreClass mapTable, FKMapStore<K, V> mapStore, ClassLoaderResolver clr)
    {
        super(mapTable.getStoreManager(), clr);

        this.mapTable = mapTable;
        this.mapStore = mapStore;
        this.ownerMapping = mapStore.getOwnerMapping();
        this.keyMapping = mapStore.getKeyMapping();
        this.valueMapping = mapStore.getValueMapping();
        this.ownerMemberMetaData = mapStore.getOwnerMemberMetaData();
    }

    public boolean hasOrderMapping()
    {
        return false;
    }

    public MapStore<K, V> getMapStore()
    {
        return mapStore;
    }

    public JavaTypeMapping getOwnerMapping()
    {
        return ownerMapping;
    }

    public JavaTypeMapping getKeyMapping()
    {
        return keyMapping;
    }

    public JavaTypeMapping getValueMapping()
    {
        return valueMapping;
    }

    /**
     * Method to update an embedded element.
     * @param sm StateManager of the owner
     * @param element The element to update
     * @param fieldNumber The number of the field to update
     * @param value The value
     * @return Whether the element was modified
     */
    public boolean updateEmbeddedElement(DNStateManager sm, Map.Entry<K, V> element, int fieldNumber, Object value)
    {
        // Do nothing since of no use here
        return false;
    }

    protected boolean validateElementType(Object element)
    {
        return element instanceof Entry;
    }

    /**
     * Method to update the collection to be the supplied collection of elements.
     * @param sm StateManager of the object
     * @param coll The collection to use
     */
    public void update(DNStateManager sm, Collection coll)
    {
        // Crude update - remove existing and add new!
        // TODO Update this to just remove what is not needed, and add what is really new
        clear(sm);
        addAll(sm, coll, 0);
    }

    public boolean contains(DNStateManager sm, Object element)
    {
        if (!validateElementType(element))
        {
            return false;
        }
        Entry entry = (Entry)element;

        return mapStore.containsKey(sm, entry.getKey());
    }

    public boolean add(DNStateManager sm, Map.Entry<K, V> entry, int size)
    {
        throw new UnsupportedOperationException("Cannot add to a map through its entry set");
    }

    public boolean addAll(DNStateManager sm, Collection entries, int size)
    {
        throw new UnsupportedOperationException("Cannot add to a map through its entry set");
    }

    /**
     * Method to remove an entry from the Map.
     * @param sm StateManager for the owner
     * @param element Entry to remove
     * @return Whether it was removed
     */
    public boolean remove(DNStateManager sm, Object element, int size, boolean allowDependentField)
    {
        if (!validateElementType(element))
        {
            return false;
        }

        Entry entry = (Entry)element;
        Object removed = mapStore.remove(sm, entry.getKey());

        // NOTE: this may not return an accurate result if a null value is being removed
        return removed == null ? entry.getValue() == null : removed.equals(entry.getValue());
    }

    /**
     * Method to remove entries from the Map.
     * @param sm StateManager for the owner
     * @param elements Entries to remove
     * @return Whether they were removed
     */
    public boolean removeAll(DNStateManager sm, Collection elements, int size)
    {
        if (elements == null || elements.size() == 0)
        {
            return false;
        }

        Iterator iter = elements.iterator();
        boolean modified = false;
        while (iter.hasNext())
        {
            Object element = iter.next();
            Entry entry = (Entry)element;

            Object removed = mapStore.remove(sm, entry.getKey());

            // NOTE: this may not return an accurate result if a null value is being removed.
            modified = removed == null ? entry.getValue() == null : removed.equals(entry.getValue());
        }

        return modified;
    }

    /**
     * Method to clear the Map.
     * @param sm StateManager for the owner.
     */
    public void clear(DNStateManager sm)
    {
        mapStore.clear(sm);
    }

    public int size(DNStateManager sm)
    {
        int numRows;

        String stmt = getSizeStmt();
        try
        {
            ExecutionContext ec = sm.getExecutionContext();

            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForQuery(mconn, stmt);
                try
                {
                    int jdbcPosition = 1;
                    BackingStoreHelper.populateOwnerInStatement(sm, ec, ps, jdbcPosition, this);
                    ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, stmt, ps);
                    try
                    {
                        if (!rs.next())
                        {
                            throw new NucleusDataStoreException("Size request returned no result row: " + stmt);
                        }
                        numRows = rs.getInt(1);
                        JDBCUtils.logWarnings(rs);
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    sqlControl.closeStatement(mconn, ps);
                }
            }
            finally
            {
                mconn.release();
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException("Size request failed: " + stmt, e);
        }

        return numRows;
    }

    /**
     * Method to return a size statement.
     * <PRE>
     * SELECT COUNT(*) FROM MAP_TABLE WHERE OWNER=? AND KEY IS NOT NULL
     * </PRE>
     * @return The size statement
     */
    private String getSizeStmt()
    {
        if (sizeStmt == null)
        {
            StringBuilder stmt = new StringBuilder("SELECT COUNT(*) FROM ");
            stmt.append(mapTable.toString());
            stmt.append(" WHERE ");
            BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
            if (keyMapping != null)
            {
                // We don't accept null keys
                for (int i=0; i<keyMapping.getNumberOfColumnMappings(); i++)
                {
                    stmt.append(" AND ");
                    stmt.append(keyMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                    stmt.append(" IS NOT NULL");
                }
            }
            sizeStmt = stmt.toString();
        }
        return sizeStmt;
    }

    /**
     * Method returning an iterator across the entries in the map for this owner object.
     * @param ownerSM StateManager of the owning object
     * @return The iterator for the entries (<pre>map.entrySet().iterator()</pre>).
     */
    public Iterator<Map.Entry<K, V>> iterator(DNStateManager ownerSM)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();

        Boolean serializeRead = ec.getTransaction().getSerializeRead();
        String stmtSql = serializeRead ? iteratorSelectStmtLockedSql : iteratorSelectStmtSql;

        if (stmtSql == null)
        {
            synchronized (this) // Make sure this completes in case another thread needs the same info
            {
                // Generate the statement
                SelectStatement selectStmt = getSQLStatementForIterator(ownerSM, ec.getFetchPlan(), true);

                // Input parameter(s) - the owner
                int inputParamNum = 1;
                StatementMappingIndex ownerIdx = new StatementMappingIndex(ownerMapping);
                if (selectStmt.getNumberOfUnions() > 0)
                {
                    // Add parameter occurrence for each union of statement
                    for (int j=0;j<selectStmt.getNumberOfUnions()+1;j++)
                    {
                        int[] paramPositions = new int[ownerMapping.getNumberOfColumnMappings()];
                        for (int k=0;k<ownerMapping.getNumberOfColumnMappings();k++)
                        {
                            paramPositions[k] = inputParamNum++;
                        }
                        ownerIdx.addParameterOccurrence(paramPositions);
                    }
                }
                else
                {
                    int[] paramPositions = new int[ownerMapping.getNumberOfColumnMappings()];
                    for (int k=0;k<ownerMapping.getNumberOfColumnMappings();k++)
                    {
                        paramPositions[k] = inputParamNum++;
                    }
                    ownerIdx.addParameterOccurrence(paramPositions);
                }
                iteratorMappingParams = new StatementParameterMapping();
                iteratorMappingParams.addMappingForParameter("owner", ownerIdx);

                // Save the two possible select statements (normal, locked)
                iteratorSelectStmtSql = selectStmt.getSQLText().toSQL();

                selectStmt.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE, true);
                iteratorSelectStmtLockedSql = selectStmt.getSQLText().toSQL();
            }

            stmtSql = serializeRead ? iteratorSelectStmtLockedSql : iteratorSelectStmtSql;
        }

        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                // Create the statement and set the owner
                PreparedStatement ps = sqlControl.getStatementForQuery(mconn, stmtSql);
                StatementMappingIndex ownerIdx = iteratorMappingParams.getMappingForParameter("owner");
                int numParams = ownerIdx.getNumberOfParameterOccurrences();
                for (int paramInstance=0; paramInstance<numParams; paramInstance++)
                {
                    ownerIdx.getMapping().setObject(ec, ps, ownerIdx.getParameterPositionsForOccurrence(paramInstance), ownerSM.getObject());
                }

                try
                {
                    ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, stmtSql, ps);
                    try
                    {
                        return new SetIterator(ownerSM, this, ownerMemberMetaData, rs, iteratorKeyResultCols, iteratorValueResultCols)
                        {
                            protected boolean next(Object rs)
                            {
                                try
                                {
                                    return ((ResultSet) rs).next();
                                }
                                catch (SQLException e)
                                {
                                    throw new NucleusDataStoreException("SQLException", e);
                                }
                            }
                        };
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    sqlControl.closeStatement(mconn, ps);
                }
            }
            finally
            {
                mconn.release();
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056012", stmtSql), e);
        }
    }

    /**
     * Method to generate a SelectStatement for iterating through entries of the map.
     * Creates a statement that selects the table holding the map definition (key/value mappings).
     * Adds a restriction on the ownerMapping of the containerTable so we can restrict to the owner object.
     * Adds a restriction on the keyMapping not being null.
     * <pre>
     * SELECT KEY, VALUE FROM MAP_TABLE WHERE OWNER_ID=? AND KEY IS NOT NULL
     * </pre>
     * @param ownerSM StateManager for the owner object
     * @param fp Fetch Plan to observe when selecting key/value
     * @param addRestrictionOnOwner Whether to add a restriction on the owner object for this map
     * @return The SelectStatement
     * TODO Change this to return KeyValueIteratorStatement
     */
    protected SelectStatement getSQLStatementForIterator(DNStateManager ownerSM, FetchPlan fp, boolean addRestrictionOnOwner)
    {
        SQLExpressionFactory exprFactory = storeMgr.getSQLExpressionFactory();

        SelectStatement sqlStmt = new SelectStatement(storeMgr, mapTable, null, null);
        sqlStmt.setClassLoaderResolver(clr);
      //  MapMetaData mapmd = ownerMemberMetaData.getMap();

        // Select key and value
        /*if (mapmd.getMapType() == MapType.MAP_TYPE_JOIN)
        {
            JoinMapStore joinMapStore = (JoinMapStore)mapStore;
            if (mapmd.isEmbeddedKey() || mapmd.isSerializedKey() || !mapmd.keyIsPersistent())
            {
                // Key stored wholely in join table
            }
            else if (joinMapStore.keyMapping instanceof ReferenceMapping)
            {
                // Key = Reference type (interface/Object)
                // Just select the key here since we're going to return the implementation id columns only
            }
            else
            {
                // Key stored in own table, so join to table and select requisite fields
            }

            if (mapmd.isEmbeddedValue() || mapmd.isSerializedValue() || !mapmd.valueIsPersistent())
            {
                // Value stored wholely in join table
            }
            else if (joinMapStore.valueMapping instanceof ReferenceMapping)
            {
                // Value = Reference type (interface/Object)
                // Just select the value here since we're going to return the implementation id columns only
            }
            else
            {
                // Value stored in own table, so join to table and select requisite fields
            }
        }
        else if (mapmd.getMapType() == MapType.MAP_TYPE_KEY_IN_VALUE)
        {
            FKMapStore fkMapStore = (FKMapStore)mapStore;

            // Select key
            if (mapmd.isEmbeddedKey() || mapmd.isSerializedKey() || !mapmd.keyIsPersistent())
            {
                // Key stored wholely in value table
            }
            else if (fkMapStore.keyMapping instanceof ReferenceMapping)
            {
                // Key = Reference type (interface/Object)
                // Just select the key here since we're going to return the implementation id columns only
            }
            else
            {
                // Key stored in own table, so join to table and select requisite fields
            }

            // Select the requisite fields of the value from this table
        }
        else
        {
            FKMapStore fkMapStore = (FKMapStore)mapStore;

            // Select the requisite fields of the key from this table

            // Select value
            if (mapmd.isEmbeddedValue() || mapmd.isSerializedValue() || !mapmd.valueIsPersistent())
            {
                // Value stored wholely in value table
            }
            else if (fkMapStore.valueMapping instanceof ReferenceMapping)
            {
                // Value = Reference type (interface/Object)
                // Just select the value here since we're going to return the implementation id columns only
            }
            else
            {
                // Value stored in own table, so join to table and select requisite fields
            }
        }*/

        // TODO If key is persistable and has inheritance also select a discriminator to get the type
        SQLTable entrySqlTblForKey = sqlStmt.getPrimaryTable();
        if (keyMapping.getTable() != mapTable) // TODO This will not join since these tables are the same
        {
            entrySqlTblForKey = sqlStmt.getTableForDatastoreContainer(keyMapping.getTable());
            if (entrySqlTblForKey == null)
            {
                // Add join to key table
                entrySqlTblForKey = sqlStmt.join(JoinType.INNER_JOIN, sqlStmt.getPrimaryTable(), sqlStmt.getPrimaryTable().getTable().getIdMapping(), 
                    keyMapping.getTable(), null, keyMapping.getTable().getIdMapping(), null, null, true);
            }
            // TODO Select FetchPlan of the key?
        }
        iteratorKeyResultCols = sqlStmt.select(entrySqlTblForKey, keyMapping, null);

        // TODO If value is persistable and has inheritance also select a discriminator to get the type
        SQLTable entrySqlTblForVal = sqlStmt.getPrimaryTable();
        if (valueMapping.getTable() != mapTable) // TODO This will not join since these tables are the same
        {
            entrySqlTblForVal = sqlStmt.getTableForDatastoreContainer(valueMapping.getTable());
            if (entrySqlTblForVal == null)
            {
                // Add join to value table
                // TODO If this map allows null values, we should do left outer join
                entrySqlTblForVal = sqlStmt.join(JoinType.INNER_JOIN, sqlStmt.getPrimaryTable(), sqlStmt.getPrimaryTable().getTable().getIdMapping(), 
                    valueMapping.getTable(), null, valueMapping.getTable().getIdMapping(), null, null, true);
            }
            // TODO Select FetchPlan of the value?
        }
        iteratorValueResultCols = sqlStmt.select(entrySqlTblForVal, valueMapping, null);

        if (addRestrictionOnOwner)
        {
            // Apply condition on owner field to filter by owner
            SQLTable ownerSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), ownerMapping);
            SQLExpression ownerExpr = exprFactory.newExpression(sqlStmt, ownerSqlTbl, ownerMapping);
            SQLExpression ownerVal = exprFactory.newLiteralParameter(sqlStmt, ownerMapping, null, "OWNER");
            sqlStmt.whereAnd(ownerExpr.eq(ownerVal), true);
        }

        // Apply condition that key is not null
        SQLExpression keyExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), keyMapping);
        SQLExpression nullExpr = exprFactory.newLiteral(sqlStmt, null, null);
        sqlStmt.whereAnd(keyExpr.ne(nullExpr), true);

        return sqlStmt;
    }

    /**
     * Inner class representing an iterator for the Set.
     * TODO Provide an option where a PersistentClassROF is provided for key and/or value so we can load fetch plan fields rather than just id.
     */
    public static abstract class SetIterator implements Iterator
    {
        private final DNStateManager sm;
        private final Iterator delegate;
        private Entry lastElement = null;
        private final MapEntrySetStore setStore;

        /**
         * Constructor for iterating the Set of entries.
         * @param sm StateManager
         * @param setStore the set store
         * @param ownerMmd the owner member meta data - can be null (for non-joinTable cases)
         * @param rs the ResultSet
         * @param keyResultCols Column(s) for the key id
         * @param valueResultCols Column(s) for the value id
         */
        protected SetIterator(DNStateManager sm, MapEntrySetStore setStore, AbstractMemberMetaData ownerMmd, ResultSet rs, int[] keyResultCols, int[] valueResultCols)
        {
            this.sm = sm;
            this.setStore = setStore;

            ExecutionContext ec = sm.getExecutionContext();
            List<EntryImpl> results = new ArrayList<>();
            while (next(rs))
            {
                Object key = null;
                Object value = null;
                int ownerFieldNum = (ownerMmd != null) ? ownerMmd.getAbsoluteFieldNumber() : -1;

                // TODO If key is persistable and has inheritance, use discriminator to determine type
                JavaTypeMapping keyMapping = setStore.getKeyMapping();
                if (keyMapping instanceof EmbeddedKeyPCMapping || keyMapping instanceof SerialisedPCMapping || keyMapping instanceof SerialisedReferenceMapping)
                {
                    key = keyMapping.getObject(ec, rs, keyResultCols, sm, ownerFieldNum);
                }
                else
                {
                    key = keyMapping.getObject(ec, rs, keyResultCols);
                }
                // TODO Where we pass in rof then key = keyrof.getObject(ec, rs);

                // TODO If value is persistable and has inheritance, use discriminator to determine type
                JavaTypeMapping valueMapping = setStore.getValueMapping();
                if (valueMapping instanceof EmbeddedValuePCMapping || valueMapping instanceof SerialisedPCMapping || valueMapping instanceof SerialisedReferenceMapping)
                {
                    value = valueMapping.getObject(ec, rs, valueResultCols, sm, ownerFieldNum);
                }
                else
                {
                    value = valueMapping.getObject(ec, rs, valueResultCols);
                }
                // TODO Where we pass in rof then value = valrof.getObject(ec, rs);

                results.add(new EntryImpl(sm, key, value, setStore.getMapStore()));
            }

            delegate = results.iterator();
        }

        public boolean hasNext()
        {
            return delegate.hasNext();
        }

        public Object next()
        {
            lastElement = (Entry)delegate.next();

            return lastElement;
        }

        public synchronized void remove()
        {
            if (lastElement == null)
            {
                throw new IllegalStateException("No entry to remove");
            }

            setStore.getMapStore().remove(sm, lastElement.getKey());
            delegate.remove();

            lastElement = null;
        }

        protected abstract boolean next(Object rs);
    }

    /**
     * Inner class representing the entry in the map.
     */
    private static class EntryImpl<K, V> implements Entry<K, V>
    {
        private final DNStateManager ownerSM;
        private final K key;
        private final V value;
        private final MapStore<K, V> mapStore;

        public EntryImpl(DNStateManager sm, K key, V value, MapStore<K, V> mapStore)
        {
            this.ownerSM = sm;
            this.key = key;
            this.value = value;
            this.mapStore = mapStore;
        }

        public int hashCode()
        {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        public boolean equals(Object o)
        {
            if (o == this)
            {
                return true;
            }
            if (!(o instanceof Entry))
            {
                return false;
            }

            Entry e = (Entry)o;
            return (key == null ? e.getKey() == null : key.equals(e.getKey())) &&
                   (value == null ? e.getValue() == null : value.equals(e.getValue()));
        }

        public K getKey()
        {
            return key;
        }
        public V getValue()
        {
            return value;
        }
        public V setValue(V value)
        {
            return mapStore.put(ownerSM, key, value);
        }
    }
}