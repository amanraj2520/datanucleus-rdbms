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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.api.ApiAdapter;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.metadata.MapMetaData;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.query.expression.Expression;
import org.datanucleus.store.rdbms.mapping.MappingHelper;
import org.datanucleus.store.rdbms.mapping.java.EmbeddedKeyPCMapping;
import org.datanucleus.store.rdbms.mapping.java.EmbeddedValuePCMapping;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.mapping.java.ReferenceMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedPCMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedReferenceMapping;
import org.datanucleus.store.rdbms.JDBCUtils;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.rdbms.query.PersistentClassROF;
import org.datanucleus.store.rdbms.query.ResultObjectFactory;
import org.datanucleus.store.rdbms.query.StatementClassMapping;
import org.datanucleus.store.rdbms.query.StatementMappingIndex;
import org.datanucleus.store.rdbms.query.StatementParameterMapping;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLStatementHelper;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.SelectStatementGenerator;
import org.datanucleus.store.rdbms.sql.UnionStatementGenerator;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.rdbms.table.MapTable;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.store.types.scostore.CollectionStore;
import org.datanucleus.store.types.scostore.MapStore;
import org.datanucleus.store.types.scostore.SetStore;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * RDBMS-specific implementation of a {@link MapStore} using join table.
 */
public class JoinMapStore<K, V> extends AbstractMapStore<K, V>
{
    /** Join table storing the map relation between key and value. */
    protected MapTable mapTable;

    /** Table storing the values. */
    protected DatastoreClass valueTable;

    private String putStmt;
    private String updateStmt;
    private String removeStmt;
    private String clearStmt;
    private String maxAdapterColumnIdStmt;

    /** JDBC statement to use for retrieving keys of the map (locking). */
    private volatile String getStmtLocked = null;

    /** JDBC statement to use for retrieving keys of the map (not locking). */
    private String getStmtUnlocked = null;

    private StatementClassMapping getMappingDef = null;
    private StatementParameterMapping getMappingParams = null;

    private SetStore<K> keySetStore = null;
    private CollectionStore<V> valueSetStore = null;
    private SetStore entrySetStore = null;

    /** Mapping for when the element mappings columns can't be part of the primary key due to datastore limitations (e.g BLOB types). */
    protected final JavaTypeMapping adapterMapping;

    /**
     * Constructor for the backing store of a join map for RDBMS.
     * @param mapTable Join table for the Map
     * @param clr The ClassLoaderResolver
     */
    public JoinMapStore(MapTable mapTable, ClassLoaderResolver clr)
    {
        super(mapTable.getStoreManager(), clr);
        setOwner(mapTable.getOwnerMemberMetaData());

        this.mapTable = mapTable;

        this.ownerMapping = mapTable.getOwnerMapping();
        this.keyMapping = mapTable.getKeyMapping();
        this.valueMapping = mapTable.getValueMapping();
        this.adapterMapping = mapTable.getOrderMapping();

        this.keyType = mapTable.getKeyType();
        this.keysAreEmbedded = mapTable.isEmbeddedKey();
        this.keysAreSerialised = mapTable.isSerialisedKey();
        this.valueType = mapTable.getValueType();
        this.valuesAreEmbedded = mapTable.isEmbeddedValue();
        this.valuesAreSerialised = mapTable.isSerialisedValue();

        this.keyCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(clr.classForName(keyType), clr);

        Class valueClass = clr.classForName(valueType);
        if (ClassUtils.isReferenceType(valueClass))
        {
            // Map of reference value types (interfaces/Objects)
            NucleusLogger.PERSISTENCE.warn(Localiser.msg("056066", ownerMemberMetaData.getFullFieldName(), valueType));
            valueCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForImplementationOfReference(valueClass, null, clr);
            if (valueCmd != null)
            {
                // TODO This currently just grabs the cmd of the first implementation.
                // It needs to get the cmds for all implementations, so we can have a handle to all possible elements.
                // This would mean changing the SCO classes to have multiple valueTable/valueMapping etc.
                valueTable = storeMgr.getDatastoreClass(valueCmd.getFullClassName(), clr);
            }
        }
        else
        {
            valueCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(valueClass, clr);
            if (valueCmd != null)
            {
                valueTable = valuesAreEmbedded ? null : storeMgr.getDatastoreClass(valueType, clr);
            }
        }

        // Create any statements that can be cached
        initialise();
    }

    protected void initialise()
    {
        containsValueStmt = getContainsValueStmt(getOwnerMapping(), getValueMapping(), mapTable);
        putStmt = getPutStmt();
        updateStmt = getUpdateStmt();
        removeStmt = getRemoveStmt();
        clearStmt = getClearStmt();
    }

    @Override
    public void putAll(DNStateManager<?> sm, Map<? extends K, ? extends V> m, Map<K, V> currentMap)
    {
        if (m == null || m.isEmpty())
        {
            return;
        }

        // Make sure the related objects are persisted (persistence-by-reachability)
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet())
        {
            validateKeyForWriting(sm, e.getKey());
            validateValueForWriting(sm, e.getValue());
        }

        Set<Map.Entry> puts = new HashSet<>();
        Set<Map.Entry> updates = new HashSet<>();

        for (Map.Entry entry : m.entrySet())
        {
            Object key = entry.getKey();
            Object value = entry.getValue();

            // Check if this is a new entry, or an update
            if (currentMap.containsKey(key))
            {
                if (currentMap.get(key) != value)
                {
                    updates.add(entry);
                }
            }
            else
            {
                puts.add(entry);
            }
        }

        processPutsAndUpdates(sm, puts, updates);
    }

    @Override
    public void putAll(DNStateManager sm, Map<? extends K, ? extends V> m)
    {
        if (m == null || m.isEmpty())
        {
            return;
        }

        // Make sure the related objects are persisted (persistence-by-reachability)
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet())
        {
            validateKeyForWriting(sm, e.getKey());
            validateValueForWriting(sm, e.getValue());
        }

        // Extract the current map entries to compare with (single SQL call)
        Map<Object, Object> currentMap = new HashMap<>(); // TODO If this is large then we should avoid pulling all in, so just pull in the keys that are being put here
        SetStore<Map.Entry<K, V>> entrySet = this.entrySetStore();
        Iterator<Map.Entry<K,V>> entrySetIter = entrySet.iterator(sm);
        while (entrySetIter.hasNext())
        {
            Map.Entry<K,V> entry = entrySetIter.next();
            currentMap.put(entry.getKey(), entry.getValue());
        }

        // Separate the changes into puts and updates
        Set<Map.Entry> puts = new HashSet<>();
        Set<Map.Entry> updates = new HashSet<>();
        for (Map.Entry entry : m.entrySet())
        {
            Object key = entry.getKey();

            // Check if this is a new entry, or an update
            if (currentMap.containsKey(key))
            {
                updates.add(entry);
            }
            else
            {
                puts.add(entry);
            }
        }

        processPutsAndUpdates(sm, puts, updates);
    }

    protected void processPutsAndUpdates(DNStateManager sm, Set<Map.Entry> puts, Set<Map.Entry> updates)
    {
        boolean batched = allowsBatching();

        // Put any new entries
        if (!puts.isEmpty())
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(sm.getExecutionContext());
            try
            {
                // Loop through all entries
                Iterator<Map.Entry> iter = puts.iterator();
                while (iter.hasNext())
                {
                    // Add the row to the join table
                    Map.Entry entry = iter.next();
                    internalPut(sm, mconn, batched, entry.getKey(), entry.getValue(), (!iter.hasNext()));
                }
            }
            finally
            {
                mconn.release();
            }
        }

        // Update any changed entries
        if (!updates.isEmpty())
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(sm.getExecutionContext());
            try
            {
                // Loop through all entries
                Iterator<Map.Entry> iter = updates.iterator();
                while (iter.hasNext())
                {
                    // Update the row in the join table
                    Map.Entry entry = iter.next();
                    internalUpdate(sm, mconn, batched, entry.getKey(), entry.getValue(), !iter.hasNext());
                }
            }
            finally
            {
                mconn.release();
            }
        }
    }

    @Override
    public void put(DNStateManager sm, K key, V value, V previousValue, boolean present)
    {
        validateKeyForWriting(sm, key);
        validateValueForWriting(sm, value);

        if (present && value == previousValue)
        {
            // Nothing to do
            return;
        }

        ExecutionContext ec = sm.getExecutionContext();
        ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
        try
        {
            if (present)
            {
                internalUpdate(sm, mconn, false, key, value, true);
            }
            else
            {
                internalPut(sm, mconn, false, key, value, true);
            }
        }
        finally
        {
            mconn.release();
        }

        MapMetaData mapmd = ownerMemberMetaData.getMap();
        if (mapmd.isDependentValue() && !mapmd.isEmbeddedValue() && previousValue != null)
        {
            // Delete the old value if it is no longer contained and is dependent
            if (!containsValue(sm, previousValue))
            {
                sm.getExecutionContext().deleteObjectInternal(previousValue);
            }
        }
    }

    @Override
    public V put(DNStateManager sm, K key, V value)
    {
        validateKeyForWriting(sm, key);
        validateValueForWriting(sm, value);

        boolean exists = false;
        V oldValue;
        try
        {
            oldValue = getValue(sm, key);
            exists = true;
        }
        catch (NoSuchElementException e)
        {
            oldValue = null;
            exists = false;
        }

        if (oldValue != value)
        {
            // Value changed so update the map
            ExecutionContext ec = sm.getExecutionContext();
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            try
            {
                if (exists)
                {
                    internalUpdate(sm, mconn, false, key, value, true);
                }
                else
                {
                    internalPut(sm, mconn, false, key, value, true);
                }
            }
            finally
            {
                mconn.release();
            }
        }

        MapMetaData mapmd = ownerMemberMetaData.getMap();
        if (mapmd.isDependentValue() && !mapmd.isEmbeddedValue() && oldValue != null)
        {
            // Delete the old value if it is no longer contained and is dependent
            if (!containsValue(sm, oldValue))
            {
                sm.getExecutionContext().deleteObjectInternal(oldValue);
            }
        }

        return oldValue;
    }

    // TODO Enable this when tested
//    @Override
//    public void update(DNStateManager sm, Map<K, V> map)
//    {
//        if (map == null || map.isEmpty())
//        {
//            clear(sm);
//            return;
//        }
//
//        // Make sure the related objects are persisted (persistence-by-reachability)
//        for (Map.Entry<? extends K, ? extends V> e : map.entrySet())
//        {
//            validateKeyForWriting(sm, e.getKey());
//            validateValueForWriting(sm, e.getValue());
//        }
//
//        // Extract the current map entries to compare with (single SQL call)
//        Map currentMap = new HashMap<>(); // TODO If this is large then we should avoid pulling all in, so just pull in the keys that are being put here
//        SetStore<Map.Entry<K, V>> entrySet = this.entrySetStore();
//        Iterator<Map.Entry<K,V>> entrySetIter = entrySet.iterator(sm);
//        while (entrySetIter.hasNext())
//        {
//            Map.Entry<K,V> entry = entrySetIter.next();
//            currentMap.put(entry.getKey(), entry.getValue());
//        }
//
//        // Separate the changes into puts, updates and removes
//        Set<Map.Entry> puts = new HashSet<>();
//        Set<Map.Entry> updates = new HashSet<>();
//        Set<Map.Entry> removes = new HashSet<>();
//
//        Set<Map.Entry<K, V>> currentEntries = currentMap.entrySet();
//        for (Map.Entry entry : currentEntries)
//        {
//            if (!map.containsKey(entry.getKey()))
//            {
//                removes.add(entry);
//            }
//        }
//        for (Map.Entry entry : map.entrySet())
//        {
//            Object key = entry.getKey();
//
//            // Check if this is a new entry, or an update
//            if (currentMap.containsKey(key))
//            {
//                updates.add(entry);
//            }
//            else
//            {
//                puts.add(entry);
//            }
//        }
//
//        if (!removes.isEmpty())
//        {
//            // TODO Add a removeAll option rather than N statements batched
//            for (Map.Entry entry : removes)
//            {
//                remove(sm, entry.getKey(), entry.getValue());
//            }
//        }
//
//        processPutsAndUpdates(sm, puts, updates);
//    }

    @Override
    public V remove(DNStateManager sm, Object key)
    {
        if (!validateKeyForReading(sm, key))
        {
            return null;
        }

        V oldValue;
        boolean exists;
        try
        {
            oldValue = getValue(sm, key);
            exists = true;
        }
        catch (NoSuchElementException e)
        {
            oldValue = null;
            exists = false;
        }

        ExecutionContext ec = sm.getExecutionContext();
        if (exists)
        {
            removeInternal(sm, key);
        }

        MapMetaData mapmd = ownerMemberMetaData.getMap();
        ApiAdapter api = ec.getApiAdapter();
        if (mapmd.isDependentKey() && !mapmd.isEmbeddedKey() && api.isPersistable(key))
        {
            // Delete the key if it is dependent
            ec.deleteObjectInternal(key);
        }

        if (mapmd.isDependentValue() && !mapmd.isEmbeddedValue() && api.isPersistable(oldValue))
        {
            if (!containsValue(sm, oldValue))
            {
                // Delete the value if it is dependent and is not keyed by another key
                ec.deleteObjectInternal(oldValue);
            }
        }

        return oldValue;
    }

    @Override
    public void remove(DNStateManager sm, Object key, Object oldValue)
    {
        if (!validateKeyForReading(sm, key))
        {
            return;
        }

        removeInternal(sm, key);

        MapMetaData mapmd = ownerMemberMetaData.getMap();
        ExecutionContext ec = sm.getExecutionContext();
        ApiAdapter api = ec.getApiAdapter();
        if (mapmd.isDependentKey() && !mapmd.isEmbeddedKey() && api.isPersistable(key))
        {
            // Delete the key if it is dependent
            ec.deleteObjectInternal(key);
        }

        if (mapmd.isDependentValue() && !mapmd.isEmbeddedValue() && api.isPersistable(oldValue))
        {
            if (!containsValue(sm, oldValue))
            {
                // Delete the value if it is dependent and is not keyed by another key
                ec.deleteObjectInternal(oldValue);
            }
        }
    }

    @Override
    public void clear(DNStateManager ownerSM)
    {
        Collection<Object> dependentElements = null;
        if (ownerMemberMetaData.getMap().isDependentKey() || ownerMemberMetaData.getMap().isDependentValue())
        {
            // Retain the PC dependent keys/values that need deleting after clearing
            dependentElements = new HashSet<>();
            ApiAdapter api = ownerSM.getExecutionContext().getApiAdapter();
            Iterator iter = entrySetStore().iterator(ownerSM);
            while (iter.hasNext())
            {
                Map.Entry entry = (Map.Entry)iter.next();
                MapMetaData mapmd = ownerMemberMetaData.getMap();
                if (api.isPersistable(entry.getKey()) && mapmd.isDependentKey() && !mapmd.isEmbeddedKey())
                {
                    dependentElements.add(entry.getKey());
                }
                if (api.isPersistable(entry.getValue()) && mapmd.isDependentValue() && !mapmd.isEmbeddedValue())
                {
                    dependentElements.add(entry.getValue());
                }
            }
        }

        clearInternal(ownerSM);

        if (dependentElements != null && !dependentElements.isEmpty())
        {
            // Delete all dependent objects
            ownerSM.getExecutionContext().deleteObjects(dependentElements.toArray());
        }
    }

    @Override
    public synchronized SetStore<K> keySetStore()
    {
        if (keySetStore == null)
        {
            keySetStore = new MapKeySetStore<K>(mapTable, this, clr);
        }
        return keySetStore;
    }

    @Override
    public synchronized CollectionStore<V> valueCollectionStore()
    {
        if (valueSetStore == null)
        {
            valueSetStore = new MapValueCollectionStore<V>(mapTable, this, clr);
        }
        return valueSetStore;
    }

    @Override
    public synchronized SetStore<Map.Entry<K, V>> entrySetStore()
    {
        if (entrySetStore == null)
        {
            entrySetStore =  new MapEntrySetStore<>(mapTable, this, clr);
        }
        return entrySetStore;
    }

    public JavaTypeMapping getAdapterMapping()
    {
        return adapterMapping;
    }

    /**
     * Generate statement to add an item to the Map.
     * Adds a row to the link table, linking container with value object.
     * <PRE>
     * INSERT INTO MAPTABLE (VALUECOL, OWNERCOL, KEYCOL) VALUES (?, ?, ?)
     * </PRE>
     * @return Statement to add an item to the Map.
     */
    private String getPutStmt()
    {
        StringBuilder stmt = new StringBuilder("INSERT INTO ");
        stmt.append(mapTable.toString());
        stmt.append(" (");
        for (int i=0; i<valueMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(",");
            }
            stmt.append(valueMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
        }

        for (int i=0; i<ownerMapping.getNumberOfColumnMappings(); i++)
        {
            stmt.append(",");
            stmt.append(ownerMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
        }
        if (adapterMapping != null)
        {
            for (int i=0; i<adapterMapping.getNumberOfColumnMappings(); i++)
            {
                stmt.append(",");
                stmt.append(adapterMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
            }
        }

        for (int i=0; i<keyMapping.getNumberOfColumnMappings(); i++)
        {
            stmt.append(",");
            stmt.append(keyMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
        }

        stmt.append(") VALUES (");
        for (int i=0; i<valueMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(",");
            }
            stmt.append(valueMapping.getColumnMapping(i).getInsertionInputParameter());
        }

        for (int i=0; i<ownerMapping.getNumberOfColumnMappings(); i++)
        {
            stmt.append(",");
            stmt.append(ownerMapping.getColumnMapping(i).getInsertionInputParameter());
        }
        if (adapterMapping != null)
        {
            for (int i=0; i<adapterMapping.getNumberOfColumnMappings(); i++)
            {
                stmt.append(",");
                stmt.append(adapterMapping.getColumnMapping(i).getInsertionInputParameter());
            }
        }
        for (int i=0; i<keyMapping.getNumberOfColumnMappings(); i++)
        {
            stmt.append(",");
            stmt.append(keyMapping.getColumnMapping(i).getInsertionInputParameter());
        }
        stmt.append(") ");

        return stmt.toString();
    }

    /**
     * Generate statement to update an item in the Map.
     * Updates the link table row, changing the value object for this key.
     * <PRE>
     * UPDATE MAPTABLE SET VALUECOL=? WHERE OWNERCOL=? AND KEYCOL=?
     * </PRE>
     * @return Statement to update an item in the Map.
     */
    private String getUpdateStmt()
    {
        StringBuilder stmt = new StringBuilder("UPDATE ");
        stmt.append(mapTable.toString());
        stmt.append(" SET ");
        for (int i=0; i<valueMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(",");
            }
            stmt.append(valueMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
            stmt.append(" = ");
            stmt.append(valueMapping.getColumnMapping(i).getUpdateInputParameter());
        }
        stmt.append(" WHERE ");
        BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
        BackingStoreHelper.appendWhereClauseForMapping(stmt, keyMapping, null, false);

        return stmt.toString();
    }

    /**
     * Generate statement to remove an item from the Map.
     * Deletes the link from the join table, leaving the value object in its own table.
     * <PRE>
     * DELETE FROM MAPTABLE WHERE OWNERCOL=? AND KEYCOL=?
     * </PRE>
     * @return Return an item from the Map.
     */
    private String getRemoveStmt()
    {
        StringBuilder stmt = new StringBuilder("DELETE FROM ");
        stmt.append(mapTable.toString());
        stmt.append(" WHERE ");
        BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
        BackingStoreHelper.appendWhereClauseForMapping(stmt, keyMapping, null, false);

        return stmt.toString();
    }

    /**
     * Generate statement to clear the Map.
     * Deletes the links from the join table for this Map, leaving the value objects in their own table(s).
     * <PRE>
     * DELETE FROM MAPTABLE WHERE OWNERCOL=?
     * </PRE>
     * @return Statement to clear the Map.
     */
    private String getClearStmt()
    {
        StringBuilder stmt = new StringBuilder("DELETE FROM ");
        stmt.append(mapTable.toString());
        stmt.append(" WHERE ");
        BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);

        return stmt.toString();
    }

    /**
     * Method to retrieve a value from the Map given the key.
     * @param ownerSM StateManager for the owner of the map.
     * @param key The key to retrieve the value for.
     * @return The value for this key
     * @throws NoSuchElementException if the value for the key was not found
     */
    protected V getValue(DNStateManager ownerSM, Object key)
    throws NoSuchElementException
    {
        if (!validateKeyForReading(ownerSM, key))
        {
            return null;
        }

        ExecutionContext ec = ownerSM.getExecutionContext();
        if (getStmtLocked == null)
        {
            synchronized (this) // Make sure this completes in case another thread needs the same info
            {
                if (getStmtLocked == null) 
                {
                    // Generate the "get" statement for unlocked and locked situations
                    SQLStatement sqlStmt = getSQLStatementForGet(ownerSM);

                    getStmtUnlocked = sqlStmt.getSQLText().toSQL();

                    sqlStmt.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE, true);
                    getStmtLocked = sqlStmt.getSQLText().toSQL();
                }
            }
        }

        Boolean serializeRead = ec.getTransaction().getSerializeRead();
        String stmt = (serializeRead != null && serializeRead ? getStmtLocked : getStmtUnlocked);
        Object value = null;
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                // Create the statement and supply owner/key params
                PreparedStatement ps = sqlControl.getStatementForQuery(mconn, stmt);

                StatementMappingIndex ownerIdx = getMappingParams.getMappingForParameter("owner");
                int numParams = ownerIdx.getNumberOfParameterOccurrences();
                for (int paramInstance=0;paramInstance<numParams;paramInstance++)
                {
                    ownerIdx.getMapping().setObject(ec, ps, ownerIdx.getParameterPositionsForOccurrence(paramInstance), ownerSM.getObject());
                }

                StatementMappingIndex keyIdx = getMappingParams.getMappingForParameter("key");
                numParams = keyIdx.getNumberOfParameterOccurrences();
                for (int paramInstance=0;paramInstance<numParams;paramInstance++)
                {
                    keyIdx.getMapping().setObject(ec, ps, keyIdx.getParameterPositionsForOccurrence(paramInstance), key);
                }

                try
                {
                    ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, stmt, ps);
                    try
                    {
                        boolean found = rs.next();
                        if (!found)
                        {
                            throw new NoSuchElementException();
                        }

                        if (valuesAreEmbedded || valuesAreSerialised)
                        {
                            int[] param = new int[valueMapping.getNumberOfColumnMappings()];
                            for (int i = 0; i < param.length; ++i)
                            {
                                param[i] = i + 1;
                            }

                            if (valueMapping instanceof SerialisedPCMapping ||
                                valueMapping instanceof SerialisedReferenceMapping ||
                                valueMapping instanceof EmbeddedKeyPCMapping)
                            {
                                // Value = Serialised
                                int ownerFieldNumber = mapTable.getOwnerMemberMetaData().getAbsoluteFieldNumber();
                                value = valueMapping.getObject(ec, rs, param, ownerSM, ownerFieldNumber);
                            }
                            else
                            {
                                // Value = Non-PC
                                value = valueMapping.getObject(ec, rs, param);
                            }
                        }
                        else if (valueMapping instanceof ReferenceMapping)
                        {
                            // Value = Reference (Interface/Object)
                            int[] param = new int[valueMapping.getNumberOfColumnMappings()];
                            for (int i = 0; i < param.length; ++i)
                            {
                                param[i] = i + 1;
                            }
                            value = valueMapping.getObject(ec, rs, param);
                        }
                        else
                        {
                            // Value = PC
                            ResultObjectFactory rof = new PersistentClassROF<>(ec, rs, ec.getFetchPlan(), getMappingDef, valueCmd, clr.classForName(valueType));
                            value = rof.getObject();
                        }

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
            throw new NucleusDataStoreException(Localiser.msg("056014", stmt), e);
        }
        return (V) value;
    }

    /**
     * Method to return an SQLStatement for retrieving the value for a key.
     * Selects the join table and optionally joins to the value table if it has its own table.
     * @param ownerSM StateManager for the owning object
     * @return The SQLStatement
     */
    protected SelectStatement getSQLStatementForGet(DNStateManager ownerSM)
    {
        SelectStatement sqlStmt = null;
        ExecutionContext ec = ownerSM.getExecutionContext();

        final ClassLoaderResolver clr = ownerSM.getExecutionContext().getClassLoaderResolver();
        Class<?> valueCls = clr.classForName(this.valueType);
        if (valuesAreEmbedded || valuesAreSerialised)
        {
            // Value is stored in join table
            sqlStmt = new SelectStatement(storeMgr, mapTable, null, null);
            sqlStmt.setClassLoaderResolver(clr);
            sqlStmt.select(sqlStmt.getPrimaryTable(), valueMapping, null);
        }
        else
        {
            // Value is stored in own table
            getMappingDef = new StatementClassMapping();
            if (!valueCmd.getFullClassName().equals(valueCls.getName()))
            {
                valueCls = clr.classForName(valueCmd.getFullClassName());
            }
            UnionStatementGenerator stmtGen = new UnionStatementGenerator(storeMgr, clr, valueCls, true, null, null, mapTable, null, valueMapping);
            stmtGen.setOption(SelectStatementGenerator.OPTION_SELECT_DN_TYPE);
            getMappingDef.setNucleusTypeColumnName(UnionStatementGenerator.DN_TYPE_COLUMN);
            sqlStmt = stmtGen.getStatement(ec);

            // Select the value field(s)
            SQLTable valueSqlTbl = sqlStmt.getTable(valueTable, sqlStmt.getPrimaryTable().getGroupName());
            if (valueSqlTbl == null)
            {
                // Root value candidate has no table, so try to find a value candidate with a table that exists in this statement
                Collection<String> valueSubclassNames = storeMgr.getSubClassesForClass(valueType, true, clr);
                if (valueSubclassNames != null && !valueSubclassNames.isEmpty())
                {
                    for (String valueSubclassName : valueSubclassNames)
                    {
                        DatastoreClass valueTbl = storeMgr.getDatastoreClass(valueSubclassName, clr);
                        if (valueTbl != null)
                        {
                            valueSqlTbl = sqlStmt.getTable(valueTbl, sqlStmt.getPrimaryTable().getGroupName());
                            if (valueSqlTbl != null)
                            {
                                break;
                            }
                        }
                    }
                }
            }
            SQLStatementHelper.selectFetchPlanOfSourceClassInStatement(sqlStmt, getMappingDef, ec.getFetchPlan(), valueSqlTbl, valueCmd, ec.getFetchPlan().getMaxFetchDepth());
        }

        // Apply condition on owner field to filter by owner
        SQLExpressionFactory exprFactory = storeMgr.getSQLExpressionFactory();
        SQLTable ownerSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), ownerMapping);
        SQLExpression ownerExpr = exprFactory.newExpression(sqlStmt, ownerSqlTbl, ownerMapping);
        SQLExpression ownerVal = exprFactory.newLiteralParameter(sqlStmt, ownerMapping, null, "OWNER");
        sqlStmt.whereAnd(ownerExpr.eq(ownerVal), true);

        // Apply condition on key
        if (keyMapping instanceof SerialisedMapping)
        {
            // if the keyMapping contains a BLOB column (or any other column not supported by the database as primary key), uses like instead of the operator OP_EQ (=)
            // in future do not check if the keyMapping is of ObjectMapping, but use the database adapter to check the data types not supported as primary key
            // if object mapping (BLOB) use like
            SQLExpression keyExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), keyMapping);
            SQLExpression keyVal = exprFactory.newLiteralParameter(sqlStmt, keyMapping, null, "KEY");
            sqlStmt.whereAnd(new org.datanucleus.store.rdbms.sql.expression.BooleanExpression(keyExpr, Expression.OP_LIKE, keyVal), true);
        }
        else
        {
            SQLExpression keyExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), keyMapping);
            SQLExpression keyVal = exprFactory.newLiteralParameter(sqlStmt, keyMapping, null, "KEY");
            sqlStmt.whereAnd(keyExpr.eq(keyVal), true);
        }

        // Input parameter(s) - owner, key
        int inputParamNum = 1;
        StatementMappingIndex ownerIdx = new StatementMappingIndex(ownerMapping);
        StatementMappingIndex keyIdx = new StatementMappingIndex(keyMapping);
        int numberOfUnions = sqlStmt.getNumberOfUnions();

        // Add parameter occurrence for each union of statement
        for (int j=0;j<numberOfUnions+1;j++)
        {
            int[] ownerPositions = new int[ownerMapping.getNumberOfColumnMappings()];
            for (int k=0;k<ownerPositions.length;k++)
            {
                ownerPositions[k] = inputParamNum++;
            }
            ownerIdx.addParameterOccurrence(ownerPositions);

            int[] keyPositions = new int[keyMapping.getNumberOfColumnMappings()];
            for (int k=0;k<keyPositions.length;k++)
            {
                keyPositions[k] = inputParamNum++;
            }
            keyIdx.addParameterOccurrence(keyPositions);
        }

        getMappingParams = new StatementParameterMapping();
        getMappingParams.addMappingForParameter("owner", ownerIdx);
        getMappingParams.addMappingForParameter("key", keyIdx);

        return sqlStmt;
    }

    protected void clearInternal(DNStateManager ownerSM)
    {
        try
        {
            ExecutionContext ec = ownerSM.getExecutionContext();
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, clearStmt, false);
                try
                {
                    int jdbcPosition = 1;
                    BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                    sqlControl.executeStatementUpdate(ec, mconn, clearStmt, ps, true);
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
            throw new NucleusDataStoreException(Localiser.msg("056013", clearStmt), e);
        }
    }

    protected void removeInternal(DNStateManager sm, Object key)
    {
        ExecutionContext ec = sm.getExecutionContext();
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, removeStmt, false);
                try
                {
                    int jdbcPosition = 1;
                    jdbcPosition = BackingStoreHelper.populateOwnerInStatement(sm, ec, ps, jdbcPosition, this);
                    BackingStoreHelper.populateKeyInStatement(ec, ps, key, jdbcPosition, keyMapping);
                    sqlControl.executeStatementUpdate(ec, mconn, removeStmt, ps, true);
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
            throw new NucleusDataStoreException(Localiser.msg("056012", removeStmt), e);
        }
    }

    /**
     * Method to process an "update" statement (where the key already has a value in the join table).
     * @param ownerSM StateManager for the owner
     * @param conn The Connection
     * @param batched Whether we are batching it
     * @param key The key
     * @param value The new value
     * @param executeNow Whether to execute the statement now or wait til any batch
     */
    protected void internalUpdate(DNStateManager ownerSM, ManagedConnection conn, boolean batched, Object key, Object value, boolean executeNow)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();
        SQLController sqlControl = storeMgr.getSQLController();
        try 
        {
            PreparedStatement ps = sqlControl.getStatementForUpdate(conn, updateStmt, batched);
            try
            {
                int jdbcPosition = 1;
                if (valueMapping != null)
                {
                    jdbcPosition = BackingStoreHelper.populateValueInStatement(ec, ps, value, jdbcPosition, valueMapping);
                }
                else
                {
                    jdbcPosition = BackingStoreHelper.populateEmbeddedValueFieldsInStatement(ownerSM, value, ps, jdbcPosition, mapTable, this);
                }
                jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                jdbcPosition = BackingStoreHelper.populateKeyInStatement(ec, ps, key, jdbcPosition, keyMapping);

                sqlControl.executeStatementUpdate(ec, conn, updateStmt, ps, executeNow);
            }
            finally
            {
                sqlControl.closeStatement(conn, ps);
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056011", updateStmt), e);
        }
    }

    /**
     * Method to process a "put" statement (where the key has no value in the join table).
     * @param ownerSM StateManager for the owner
     * @param conn The Connection
     * @param batched Whether we are batching it
     * @param key The key
     * @param value The value
     * @param executeNow Whether to execute the statement now or wait til batching
     * @return The return codes from any executed statement
     */
    protected int[] internalPut(DNStateManager ownerSM, ManagedConnection conn, boolean batched, Object key, Object value, boolean executeNow)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();
        SQLController sqlControl = storeMgr.getSQLController();
        try
        {
            int nextIdForAdapterColumn = -1;
            if (adapterMapping != null)
            {
                // Only set the adapter mapping if we have a new object
                nextIdForAdapterColumn = getNextIDForAdapterColumn(ownerSM);
            }

            PreparedStatement ps = sqlControl.getStatementForUpdate(conn, putStmt, batched);
            try
            {
                int jdbcPosition = 1;
                if (valueMapping != null)
                {
                    jdbcPosition = BackingStoreHelper.populateValueInStatement(ec, ps, value, jdbcPosition, valueMapping);
                }
                else
                {
                    jdbcPosition = BackingStoreHelper.populateEmbeddedValueFieldsInStatement(ownerSM, value, ps, jdbcPosition, mapTable, this);
                }
                jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                if (adapterMapping != null)
                {
                    adapterMapping.setObject(ec, ps, MappingHelper.getMappingIndices(jdbcPosition, adapterMapping), Long.valueOf(nextIdForAdapterColumn));
                    jdbcPosition += adapterMapping.getNumberOfColumnMappings();
                }
                jdbcPosition = BackingStoreHelper.populateKeyInStatement(ec, ps, key, jdbcPosition, keyMapping);

                // Execute the statement
                return sqlControl.executeStatementUpdate(ec, conn, putStmt, ps, executeNow);
            }
            finally
            {
                sqlControl.closeStatement(conn, ps);
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056016", putStmt), e);
        }
    }

    /**
     * Accessor for the higher id when elements primary key can't be part of the primary key by datastore limitations (e.g BLOB types can't be primary keys).
     * @param sm StateManager for container
     * @return The next id
     */
    private int getNextIDForAdapterColumn(DNStateManager sm)
    {
        int nextID;
        try
        {
            ExecutionContext ec = sm.getExecutionContext();
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                String stmt = getMaxAdapterColumnIdStmt();
                PreparedStatement ps = sqlControl.getStatementForQuery(mconn, stmt);

                try
                {
                    int jdbcPosition = 1;
                    BackingStoreHelper.populateOwnerInStatement(sm, ec, ps, jdbcPosition, this);
                    ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, stmt, ps);
                    try
                    {
                        nextID = (!rs.next()) ? nextID = 1 : rs.getInt(1)+1;
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
            throw new NucleusDataStoreException(Localiser.msg("056020", getMaxAdapterColumnIdStmt()),e);
        }

        return nextID;
    }

    /**
     * Generate statement for obtaining the maximum id.
     * <PRE>
     * SELECT MAX(SCOID) FROM MAPTABLE WHERE OWNERCOL=?
     * </PRE>
     * @return The Statement returning the higher id
     */
    private String getMaxAdapterColumnIdStmt()
    {
        if (maxAdapterColumnIdStmt == null)
        {
            StringBuilder stmt = new StringBuilder("SELECT MAX(" + adapterMapping.getColumnMapping(0).getColumn().getIdentifier().toString() + ")");
            stmt.append(" FROM ");
            stmt.append(mapTable.toString());
            stmt.append(" WHERE ");
            BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);

            maxAdapterColumnIdStmt = stmt.toString();
        }

        return maxAdapterColumnIdStmt;
    }

    @Override
    public boolean updateEmbeddedKey(DNStateManager sm, Object key, int fieldNumber, Object newValue)
    {
        boolean modified = false;
        if (keyMapping != null && keyMapping instanceof EmbeddedKeyPCMapping)
        {
            String fieldName = valueCmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber).getName();
            if (fieldName == null)
            {
                // We have no mapping for this field so presumably is the owner field or a PK field
                return false;
            }
            JavaTypeMapping fieldMapping = ((EmbeddedKeyPCMapping)keyMapping).getJavaTypeMapping(fieldName);
            if (fieldMapping == null)
            {
                // We have no mapping for this field so presumably is the owner field or a PK field
                return false;
            }

            // Update the embedded key
            String stmt = getUpdateEmbeddedKeyStmt(fieldMapping, getOwnerMapping(), getKeyMapping(), mapTable);
            try
            {
                ExecutionContext ec = sm.getExecutionContext();
                ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
                SQLController sqlControl = storeMgr.getSQLController();

                try
                {
                    PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, stmt, false);
                    try
                    {
                        int jdbcPosition = 1;
                        fieldMapping.setObject(ec, ps, MappingHelper.getMappingIndices(jdbcPosition, fieldMapping), key);
                        jdbcPosition += fieldMapping.getNumberOfColumnMappings();
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(sm, ec, ps, jdbcPosition, this);
                        jdbcPosition = BackingStoreHelper.populateEmbeddedKeyFieldsInStatement(sm, key, ps, jdbcPosition, mapTable, this);

                        sqlControl.executeStatementUpdate(ec, mconn, stmt, ps, true);
                        modified = true;
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
                String msg = Localiser.msg("056010", stmt);
                NucleusLogger.DATASTORE_PERSIST.warn(msg, e);
                throw new NucleusDataStoreException(msg, e);
            }
        }

        return modified;
    }

    @Override
    public boolean updateEmbeddedValue(DNStateManager sm, Object value, int fieldNumber, Object newValue)
    {
        boolean modified = false;
        if (valueMapping != null && valueMapping instanceof EmbeddedValuePCMapping)
        {
            String fieldName = valueCmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber).getName();
            if (fieldName == null)
            {
                // We have no mapping for this field so presumably is the owner field or a PK field
                return false;
            }
            JavaTypeMapping fieldMapping = ((EmbeddedValuePCMapping)valueMapping).getJavaTypeMapping(fieldName);
            if (fieldMapping == null)
            {
                // We have no mapping for this field so presumably is the owner field or a PK field
                return false;
            }

            // Update the embedded value
            String stmt = getUpdateEmbeddedValueStmt(fieldMapping, getOwnerMapping(), getValueMapping(), mapTable);
            try
            {
                ExecutionContext ec = sm.getExecutionContext();
                ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
                SQLController sqlControl = storeMgr.getSQLController();

                try
                {
                    PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, stmt, false);
                    try
                    {
                        int jdbcPosition = 1;
                        fieldMapping.setObject(ec, ps, MappingHelper.getMappingIndices(jdbcPosition, fieldMapping), newValue);
                        jdbcPosition += fieldMapping.getNumberOfColumnMappings();
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(sm, ec, ps, jdbcPosition, this);
                        jdbcPosition = BackingStoreHelper.populateEmbeddedValueFieldsInStatement(sm, value, ps, jdbcPosition, mapTable, this);
                        sqlControl.executeStatementUpdate(ec, mconn, stmt, ps, true);
                        modified = true;
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
                String msg = Localiser.msg("056011", stmt);
                NucleusLogger.DATASTORE_PERSIST.warn(msg, e);
                throw new NucleusDataStoreException(msg, e);
            }
        }

        return modified;
    }

    /**
     * Generate statement for update the field of an embedded key.
     * <PRE>
     * UPDATE MAPTABLE
     * SET EMBEDDEDKEYCOL1 = ?
     * WHERE OWNERCOL=?
     * AND EMBEDDEDKEYCOL1 = ?
     * AND EMBEDDEDKEYCOL2 = ? ...
     * </PRE>
     * @param fieldMapping The mapping for the field (of the key) to be updated
     * @param ownerMapping The owner mapping
     * @param keyMapping The key mapping
     * @param mapTable The map table
     * @return Statement for updating an embedded key in the Set
     */
    protected String getUpdateEmbeddedKeyStmt(JavaTypeMapping fieldMapping, JavaTypeMapping ownerMapping, JavaTypeMapping keyMapping, Table mapTable)
    {
        StringBuilder stmt = new StringBuilder("UPDATE ");
        stmt.append(mapTable.toString());
        stmt.append(" SET ");
        for (int i=0; i<fieldMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(",");
            }
            stmt.append(fieldMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
            stmt.append(" = ");
            stmt.append(fieldMapping.getColumnMapping(i).getUpdateInputParameter());
        }

        stmt.append(" WHERE ");
        BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);

        EmbeddedKeyPCMapping embeddedMapping = (EmbeddedKeyPCMapping)keyMapping;
        for (int i=0;i<embeddedMapping.getNumberOfJavaTypeMappings();i++)
        {
            JavaTypeMapping m = embeddedMapping.getJavaTypeMapping(i);
            if (m != null)
            {
                for (int j=0;j<m.getNumberOfColumnMappings();j++)
                {
                    stmt.append(" AND ");
                    stmt.append(m.getColumnMapping(j).getColumn().getIdentifier().toString());
                    stmt.append(" = ");
                    stmt.append(m.getColumnMapping(j).getUpdateInputParameter());
                }
            }
        }
        return stmt.toString();
    }

    /**
     * Generate statement for update the field of an embedded value.
     * <PRE>
     * UPDATE MAPTABLE
     * SET EMBEDDEDVALUECOL1 = ?
     * WHERE OWNERCOL=?
     * AND EMBEDDEDVALUECOL1 = ?
     * AND EMBEDDEDVALUECOL2 = ? ...
     * </PRE>
     * @param fieldMapping The mapping for the field to be updated
     * @param ownerMapping The owner mapping
     * @param valueMapping mapping for the value
     * @param mapTable The map table
     * @return Statement for updating an embedded value in the Set
     */
    protected String getUpdateEmbeddedValueStmt(JavaTypeMapping fieldMapping, JavaTypeMapping ownerMapping, JavaTypeMapping valueMapping, Table mapTable)
    {
        StringBuilder stmt = new StringBuilder("UPDATE ");
        stmt.append(mapTable.toString());
        stmt.append(" SET ");
        for (int i=0; i<fieldMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(",");
            }
            stmt.append(fieldMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
            stmt.append(" = ");
            stmt.append(fieldMapping.getColumnMapping(i).getUpdateInputParameter());
        }

        stmt.append(" WHERE ");
        BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);

        EmbeddedValuePCMapping embeddedMapping = (EmbeddedValuePCMapping)valueMapping;
        for (int i=0;i<embeddedMapping.getNumberOfJavaTypeMappings();i++)
        {
            JavaTypeMapping m = embeddedMapping.getJavaTypeMapping(i);
            if (m != null)
            {
                for (int j=0;j<m.getNumberOfColumnMappings();j++)
                {
                    stmt.append(" AND ");
                    stmt.append(m.getColumnMapping(j).getColumn().getIdentifier().toString());
                    stmt.append(" = ");
                    stmt.append(m.getColumnMapping(j).getUpdateInputParameter());
                }
            }
        }
        return stmt.toString();
    }
}