/**********************************************************************
Copyright (c) 2009 Andy Jefferson and others. All rights reserved.
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
import java.util.ListIterator;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlan;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.CollectionMetaData;
import org.datanucleus.metadata.DiscriminatorStrategy;
import org.datanucleus.metadata.FieldRole;
import org.datanucleus.metadata.MetaDataUtils;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.metadata.OrderMetaData.FieldOrder;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.mapping.java.ReferenceMapping;
import org.datanucleus.store.rdbms.RDBMSPropertyNames;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.rdbms.fieldmanager.DynamicSchemaFieldManager;
import org.datanucleus.store.rdbms.query.PersistentClassROF;
import org.datanucleus.store.rdbms.query.ResultObjectFactory;
import org.datanucleus.store.rdbms.query.StatementClassMapping;
import org.datanucleus.store.rdbms.query.StatementMappingIndex;
import org.datanucleus.store.rdbms.sql.DiscriminatorStatementGenerator;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLStatementHelper;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.SelectStatementGenerator;
import org.datanucleus.store.rdbms.sql.UnionStatementGenerator;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.CollectionTable;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.types.scostore.ListStore;
import org.datanucleus.store.types.wrappers.backed.BackedSCO;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Implementation of a {@link ListStore} using join table.
 */
public class JoinListStore<E> extends AbstractListStore<E>
{
    private String setStmt;

    private Class<E> elementCls;

    /**
     * Constructor for a join list store for RDBMS.
     * @param mmd Metadata for the member that has the list with join table
     * @param joinTable The Join table
     * @param clr ClassLoader resolver
     */
    public JoinListStore(AbstractMemberMetaData mmd, CollectionTable joinTable, ClassLoaderResolver clr)
    {
        super(joinTable.getStoreManager(), clr);
        this.containerTable = joinTable;
        setOwner(mmd);
        if (ownerMemberMetaData.getOrderMetaData() != null && !ownerMemberMetaData.getOrderMetaData().isIndexedList())
        {
            indexedList = false;
        }

        this.ownerMapping = joinTable.getOwnerMapping();
        this.elementMapping = joinTable.getElementMapping();
        this.orderMapping = joinTable.getOrderMapping();
        this.relationDiscriminatorMapping = joinTable.getRelationDiscriminatorMapping();
        this.relationDiscriminatorValue = joinTable.getRelationDiscriminatorValue();
        this.elementType = mmd.getCollection().getElementType();
        this.elementsAreEmbedded = joinTable.isEmbeddedElement();
        this.elementsAreSerialised = joinTable.isSerialisedElement();

        if (orderMapping == null && indexedList)
        {
            // If the user declares a field as java.util.Collection we use SetTable to generate the join table
            // If they then instantiate it as a List type it will come through here, so we need to ensure the order column exists
            throw new NucleusUserException(Localiser.msg("056044", ownerMemberMetaData.getFullFieldName(), joinTable.toString()));
        }

        elementCls = clr.classForName(elementType);
        if (elementsAreSerialised)
        {
            elementInfo = null;
        }
        else
        {
            if (ClassUtils.isReferenceType(elementCls))
            {
                // Collection of reference types (interfaces/Objects)
                String[] implNames = MetaDataUtils.getInstance().getImplementationNamesForReferenceField(ownerMemberMetaData, 
                    FieldRole.ROLE_COLLECTION_ELEMENT, clr, storeMgr.getMetaDataManager());
                elementInfo = new ComponentInfo[implNames.length];
                for (int i=0;i<implNames.length;i++)
                {
                    DatastoreClass table = storeMgr.getDatastoreClass(implNames[i], clr);
                    AbstractClassMetaData cmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(implNames[i], clr);
                    elementInfo[i] = new ComponentInfo(cmd,table);
                }
            }
            else
            {
                // Collection of PC or non-PC
                // Generate the information for the possible elements
                elementCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(elementCls, clr);
                if (elementCmd != null)
                {
                    if (!elementsAreEmbedded)
                    {
                        elementInfo = getComponentInformationForClass(elementType, elementCmd);
                        /*if (elementInfo != null && elementInfo.length > 1)
                        {
                            throw new NucleusUserException(Localiser.msg("056031", 
                                ownerFieldMetaData.getFullFieldName()));
                        }*/
                    }
                    else
                    {
                        elementInfo = null;
                    }
                }
                else
                {
                    elementInfo = null;
                }
            }
        }
    }

    /**
     * Internal method to add element(s) to the List.
     * Performs the add in 2 steps.
     * <ol>
     * <li>Shift all existing elements into their new positions so we can insert.</li>
     * <li>Insert all new elements directly at their desired positions</li>
     * </ol>
     * Both steps can be batched (separately).
     * @param ownerSM StateManager for the list owner
     * @param start The start location (if required)
     * @param atEnd Whether to add the element at the end
     * @param c The collection of objects to add.
     * @param size Current size of list if known. -1 if not known
     * @return Whether it was successful
     */
    protected boolean internalAdd(DNStateManager ownerSM, int start, boolean atEnd, Collection<? extends E> c, int size)
    {
        if (c == null || c.size() == 0)
        {
            return true;
        }

        if (relationType == RelationType.MANY_TO_MANY_BI && ownerMemberMetaData.getMappedBy() != null)
        {
            // M-N non-owner : don't add from this side to avoid duplicates
            return true;
        }

        // Calculate the amount we need to shift any existing elements by
        // This is used where inserting between existing elements and have to shift down all elements after the start point
        int shift = c.size();

        // check all elements are valid for persisting and exist (persistence-by-reachability)
        ExecutionContext ec = ownerSM.getExecutionContext();
        for (E element : c)
        {
            validateElementForWriting(ec, element, null);

            if (relationType == RelationType.ONE_TO_MANY_BI)
            {
                // TODO This is ManagedRelations - move into RelationshipManager
                DNStateManager elementSM = ec.findStateManager(element);
                if (elementSM != null)
                {
                    AbstractMemberMetaData[] relatedMmds = ownerMemberMetaData.getRelatedMemberMetaData(clr);
                    // TODO Cater for more than 1 related field
                    Object elementOwner = elementSM.provideField(relatedMmds[0].getAbsoluteFieldNumber());
                    if (elementOwner == null)
                    {
                        // No owner, so correct it
                        NucleusLogger.PERSISTENCE.info(Localiser.msg("056037", ownerSM.getObjectAsPrintable(), ownerMemberMetaData.getFullFieldName(), 
                            StringUtils.toJVMIDString(elementSM.getObject())));
                        elementSM.replaceField(relatedMmds[0].getAbsoluteFieldNumber(), ownerSM.getObject());
                    }
                    else if (elementOwner != ownerSM.getObject() && ownerSM.getReferencedPC() == null)
                    {
                        // Owner of the element is neither this container nor being attached, so throw exception
                        throw new NucleusUserException(Localiser.msg("056038", ownerSM.getObjectAsPrintable(), ownerMemberMetaData.getFullFieldName(), 
                            StringUtils.toJVMIDString(elementSM.getObject()), StringUtils.toJVMIDString(elementOwner)));
                    }
                }
            }

        }

        int currentListSize = (size < 0) ? size(ownerSM) : size;

        // Check for dynamic schema updates prior to addition
        if (storeMgr.getBooleanObjectProperty(RDBMSPropertyNames.PROPERTY_RDBMS_DYNAMIC_SCHEMA_UPDATES).booleanValue())
        {
            DynamicSchemaFieldManager dynamicSchemaFM = new DynamicSchemaFieldManager(storeMgr, ownerSM);
            dynamicSchemaFM.storeObjectField(getOwnerMemberMetaData().getAbsoluteFieldNumber(), c);
            if (dynamicSchemaFM.hasPerformedSchemaUpdates())
            {
                invalidateAddStmt();
            }
        }

        String addStmt = getAddStmtForJoinTable();
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                // Shift any existing elements so that we can insert the new element(s) at their position
                if (!atEnd && start != currentListSize)
                {
                    internalShiftBulk(ownerSM, start-1, shift, mconn, true, true);
                }
                else
                {
                    start = currentListSize;
                }

                // Insert the elements at their required location
                int jdbcPosition = 1;
                boolean batched = (c.size() > 1);

                Iterator elemIter = c.iterator();
                while (elemIter.hasNext())
                {
                    Object element = elemIter.next();
                    PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, addStmt, batched);
                    try
                    {
                        jdbcPosition = 1;
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                        jdbcPosition = BackingStoreHelper.populateElementInStatement(ec, ps, element, jdbcPosition, elementMapping);
                        if (orderMapping != null)
                        {
                            jdbcPosition = BackingStoreHelper.populateOrderInStatement(ec, ps, start, jdbcPosition, orderMapping);
                        }
                        if (relationDiscriminatorMapping != null)
                        {
                            jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                        }
                        start++;

                        // Execute the statement
                        sqlControl.executeStatementUpdate(ec, mconn, addStmt, ps, !elemIter.hasNext());
                    }
                    finally
                    {
                        sqlControl.closeStatement(mconn, ps);
                    }
                }
            }
            finally
            {
                mconn.release();
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056009", addStmt), e);
        }
        return true;
    }

    @Override
    public E set(DNStateManager ownerSM, int index, E element, boolean allowDependentField)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();
        validateElementForWriting(ec, element, null);

        // Find the original element at this position
        E oldElement  = null;
        List<E> fieldVal = (List) ownerSM.provideField(ownerMemberMetaData.getAbsoluteFieldNumber());
        if (fieldVal != null && fieldVal instanceof BackedSCO && ((BackedSCO)fieldVal).isLoaded())
        {
            // Already loaded in the wrapper
            oldElement = fieldVal.get(index);
        }
        else
        {
            oldElement = get(ownerSM, index);
        }

        // Check for dynamic schema updates prior to update
        if (storeMgr.getBooleanObjectProperty(RDBMSPropertyNames.PROPERTY_RDBMS_DYNAMIC_SCHEMA_UPDATES).booleanValue())
        {
            DynamicSchemaFieldManager dynamicSchemaFM = new DynamicSchemaFieldManager(storeMgr, ownerSM);
            Collection<E> coll = new ArrayList<>();
            coll.add(element);
            dynamicSchemaFM.storeObjectField(getOwnerMemberMetaData().getAbsoluteFieldNumber(), coll);
            if (dynamicSchemaFM.hasPerformedSchemaUpdates())
            {
                setStmt = null;
            }
        }

        String theSetStmt = getSetStmt();
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, theSetStmt, false);
                try
                {
                    int jdbcPosition = 1;
                    jdbcPosition = BackingStoreHelper.populateElementInStatement(ec, ps, element, jdbcPosition, elementMapping);
                    jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                    if (getOwnerMemberMetaData().getOrderMetaData() != null && !getOwnerMemberMetaData().getOrderMetaData().isIndexedList())
                    {
                        // Ordered list, so can't easily do a set!!!
                        NucleusLogger.PERSISTENCE.warn("Calling List.addElement at a position for an ordered list is a stupid thing to do; the ordering is set my the ordering specification. Use an indexed list to do this correctly");
                    }
                    else
                    {
                        jdbcPosition = BackingStoreHelper.populateOrderInStatement(ec, ps, index, jdbcPosition, orderMapping);
                    }
                    if (relationDiscriminatorMapping != null)
                    {
                        jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                    }

                    sqlControl.executeStatementUpdate(ec, mconn, theSetStmt, ps, true);
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
            throw new NucleusDataStoreException(Localiser.msg("056015", theSetStmt), e);
        }

        // Dependent field
        CollectionMetaData collmd = ownerMemberMetaData.getCollection();
        boolean dependent = collmd.isDependentElement();
        if (ownerMemberMetaData.isCascadeRemoveOrphans())
        {
            dependent = true;
        }
        if (dependent && !collmd.isEmbeddedElement() && allowDependentField)
        {
            if (oldElement != null && !contains(ownerSM, oldElement))
            {
                // Delete the element if it is dependent and doesn't have a duplicate entry in the list
                ec.deleteObjectInternal(oldElement);
            }
        }

        return oldElement;
    }

    @Override
    public void update(DNStateManager ownerSM, Collection<? extends E> coll)
    {
        if (coll == null || coll.isEmpty())
        {
            clear(ownerSM);
            return;
        }

        if (ownerMemberMetaData.getCollection().isSerializedElement() || ownerMemberMetaData.getCollection().isEmbeddedElement())
        {
            // Serialized/Embedded elements so just clear and add again
            clear(ownerSM);
            addAll(ownerSM, coll, 0);
            return;
        }

        // Find existing elements, and remove any that are no longer present
        Collection<E> existing = new ArrayList<>();
        Iterator<E> elemIter = iterator(ownerSM);
        while (elemIter.hasNext())
        {
            E elem = elemIter.next();
            if (!coll.contains(elem))
            {
                remove(ownerSM, elem, -1, true);
            }
            else
            {
                existing.add(elem);
            }
        }

        if (existing.equals(coll))
        {
            // Existing (after any removals) is same as the specified so job done
            return;
        }

        // TODO Improve this - need to allow for list element position changes etc
        clear(ownerSM);
        addAll(ownerSM, coll, 0);
    }

    @Override
    public E remove(DNStateManager ownerSM, int index, int size)
    {
        E element = get(ownerSM, index);
        if (indexedList)
        {
            // Remove the element at this position
            internalRemoveAt(ownerSM, index, size);
        }
        else
        {
            // Ordered list doesn't allow indexed removal so just remove the element
            internalRemove(ownerSM, element, size);
        }

        // Dependent element
        CollectionMetaData collmd = ownerMemberMetaData.getCollection();
        boolean dependent = collmd.isDependentElement() || ownerMemberMetaData.isCascadeRemoveOrphans();
        if (dependent && !collmd.isEmbeddedElement())
        {
            // With an indexed list it could be at multiple positions in the List (not so with an ordered list, so no need to check contains there)
            if (!indexedList || !contains(ownerSM, element))
            {
                // Delete the element if it is dependent and doesn't have a duplicate entry in the list
                ownerSM.getExecutionContext().deleteObjectInternal(element);
            }
        }

        return element;
    }

    /**
     * Convenience method to remove the specified element from the List.
     * @param element The element
     * @param ownerSM StateManager of the owner
     * @param size Current size of list if known. -1 if not known
     * @return Whether the List was modified
     */
    protected boolean internalRemove(DNStateManager ownerSM, Object element, int size)
    {
        boolean modified = false;
        if (indexedList)
        {
            // Indexed List, so retrieve the index of the element and remove the object
            // Get the indices of the elements to remove in reverse order (highest first)
            // This is done because the element could be duplicated in the list.
            Collection<E> elements = new ArrayList<>();
            elements.add((E) element);
            int[] indices = getIndicesOf(ownerSM, elements);
            if (indices == null)
            {
                return false;
            }

            // Remove each element in turn, doing the shifting of indexes each time
            // TODO : Change this to remove all in one go and then shift once
            for (int i=0;i<indices.length;i++)
            {
                internalRemoveAt(ownerSM, indices[i], size);
                modified = true;
            }
        }
        else
        {
            // Ordered List - just remove the list item since no indexing present
            ExecutionContext ec = ownerSM.getExecutionContext();
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            try
            {
                int[] rcs = internalRemove(ownerSM, mconn, false, element, true);
                if (rcs != null)
                {
                    if (rcs[0] > 0)
                    {
                        modified = true;
                    }
                }
            }
            finally
            {
                mconn.release();
            }
        }

        return modified;
    }

    private int[] internalRemove(DNStateManager ownerSM, ManagedConnection conn, boolean batched, Object element, boolean executeNow)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();
        SQLController sqlControl = storeMgr.getSQLController();
        String removeStmt = getRemoveStmt(element);
        try
        {
            PreparedStatement ps = sqlControl.getStatementForUpdate(conn, removeStmt, batched);
            try
            {
                int jdbcPosition = 1;

                jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                jdbcPosition = BackingStoreHelper.populateElementForWhereClauseInStatement(ec, ps, element, jdbcPosition, elementMapping);
                if (relationDiscriminatorMapping != null)
                {
                    jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                }

                // Execute the statement
                return sqlControl.executeStatementUpdate(ec, conn, removeStmt, ps, executeNow);
            }
            finally
            {
                sqlControl.closeStatement(conn, ps);
            }
        }
        catch (SQLException sqle)
        {
            throw new NucleusDataStoreException(Localiser.msg("056012", removeStmt), sqle);
        }
    }

    @Override
    public boolean removeAll(DNStateManager ownerSM, Collection elements, int size)
    {
        // Get the indices of the elements we are going to remove (highest first)
        int[] indices = getIndicesOf(ownerSM, elements);

        return removeAll(ownerSM, elements, size, indices);
    }

    @Override
    public boolean removeAll(DNStateManager ownerSM, Collection elements, int size, int[] elementIndices)
    {
        if (elements == null || elements.size() == 0)
        {
            return false;
        }

        // Get the current size of the list (and hence maximum index size)
        int currentListSize = (size > 0) ? size : size(ownerSM);

        // Ensure we have all indices of the elements (highest first)
        int[] indices = (elementIndices != null) ? elementIndices : getIndicesOf(ownerSM, elements);
        if (indices == null)
        {
            return false;
        }

        // Remove specified elements from join table using single SQL
        boolean modified = false;
        ExecutionContext ec = ownerSM.getExecutionContext();
        String removeAllStmt = getRemoveAllStmt(elements);
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            try
            {
                SQLController sqlControl = storeMgr.getSQLController();
                PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, removeAllStmt, false);
                try
                {
                    int jdbcPosition = 1;
                    Iterator iter = elements.iterator();
                    while (iter.hasNext())
                    {
                        Object element = iter.next();
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                        jdbcPosition = BackingStoreHelper.populateElementForWhereClauseInStatement(ec, ps, element, jdbcPosition, elementMapping);
                        if (relationDiscriminatorMapping != null)
                        {
                            jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                        }
                    }

                    int[] number = sqlControl.executeStatementUpdate(ec, mconn, removeAllStmt, ps, true);
                    if (number[0] > 0)
                    {
                        modified = true;
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
            NucleusLogger.DATASTORE.error(e);
            throw new NucleusDataStoreException(Localiser.msg("056012", removeAllStmt), e);
        }

        // Shift the remaining indices to remove the holes in ordering
        // TODO Make this process use fewer SQL
        boolean batched = storeMgr.allowsBatching();
        ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
        try
        {
            for (int i = 0; i < currentListSize; i++)
            {
                // Find the number of deleted indexes above this index
                int shift = 0;
                boolean removed = false;
                for (int j = 0; j < indices.length; j++)
                {
                    if (indices[j] == i)
                    {
                        removed = true;
                        break;
                    }
                    if (indices[j] < i)
                    {
                        shift++;
                    }
                }
                if (!removed && shift > 0)
                {
                    internalShift(ownerSM, i, -1 * shift, mconn, batched, (i == currentListSize - 1));
                }
            }
        }
        finally
        {
            mconn.release();
        }

        // Dependent field
        boolean dependent = getOwnerMemberMetaData().getCollection().isDependentElement();
        if (getOwnerMemberMetaData().isCascadeRemoveOrphans())
        {
            dependent = true;
        }
        if (dependent)
        {
            // "delete-dependent" : delete elements if the collection is marked as dependent
            // TODO What if the collection contains elements that are not in the List ? should not delete them
            ownerSM.getExecutionContext().deleteObjects(elements.toArray());
        }

        return modified;
    }

    /**
     * Method to remove an element from the specified position
     * @param ownerSM StateManager for the list owner
     * @param index The index of the element
     * @param size Current size of list (if known). -1 if not known
     */
    protected void internalRemoveAt(DNStateManager ownerSM, int index, int size)
    {
        if (!indexedList)
        {
            throw new NucleusUserException("Cannot remove an element from a particular position with an ordered list since no indexes exist");
        }

        internalRemoveAt(ownerSM, index, getRemoveAtStmt(), size);
    }

    /**
     * Accessor for an iterator through the list elements.
     * @param ownerSM StateManager for the list owner
     * @param startIdx The start point in the list (only for indexed lists).
     * @param endIdx End index in the list (only for indexed lists).
     * @return The List Iterator
     */
    protected ListIterator<E> listIterator(DNStateManager ownerSM, int startIdx, int endIdx)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();

        // Generate the statement. Note that this is not cached since depends on the current FetchPlan and other things
        ElementIteratorStatement iterStmt = getIteratorStatement(ownerSM.getExecutionContext(), ec.getFetchPlan(), true, startIdx, endIdx);
        SelectStatement sqlStmt = iterStmt.getSelectStatement();
        StatementClassMapping resultMapping = iterStmt.getElementClassMapping();

        // Input parameter(s) - the owner
        int inputParamNum = 1;
        StatementMappingIndex ownerIdx = new StatementMappingIndex(ownerMapping);
        if (sqlStmt.getNumberOfUnions() > 0)
        {
            // Add parameter occurrence for each union of statement
            for (int j=0;j<sqlStmt.getNumberOfUnions()+1;j++)
            {
                int[] paramPositions = new int[ownerMapping.getNumberOfColumnMappings()];
                for (int k=0;k<paramPositions.length;k++)
                {
                    paramPositions[k] = inputParamNum++;
                }
                ownerIdx.addParameterOccurrence(paramPositions);
            }
        }
        else
        {
            int[] paramPositions = new int[ownerMapping.getNumberOfColumnMappings()];
            for (int k=0;k<paramPositions.length;k++)
            {
                paramPositions[k] = inputParamNum++;
            }
            ownerIdx.addParameterOccurrence(paramPositions);
        }

        Boolean serializeRead = ec.getTransaction().getSerializeRead();
        if (serializeRead != null && serializeRead)
        {
            sqlStmt.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE, true);
        }
        String stmt = sqlStmt.getSQLText().toSQL();

        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                // Create the statement
                PreparedStatement ps = sqlControl.getStatementForQuery(mconn, stmt);

                // Set the owner
                DNStateManager stmtOwnerSM = BackingStoreHelper.getOwnerStateManagerForBackingStore(ownerSM);
                int numParams = ownerIdx.getNumberOfParameterOccurrences();
                for (int paramInstance=0;paramInstance<numParams;paramInstance++)
                {
                    ownerIdx.getMapping().setObject(ec, ps, ownerIdx.getParameterPositionsForOccurrence(paramInstance), stmtOwnerSM.getObject());
                }

                try
                {
                    ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, stmt, ps);
                    try
                    {
                        if (elementsAreEmbedded || elementsAreSerialised)
                        {
                            // No ResultObjectFactory needed - handled by SetStoreIterator
                            return new ListStoreIterator<E>(ownerSM, rs, null, this);
                        }
                        else if (elementMapping instanceof ReferenceMapping)
                        {
                            // No ResultObjectFactory needed - handled by SetStoreIterator
                            return new ListStoreIterator<E>(ownerSM, rs, null, this);
                        }
                        else
                        {
                            ResultObjectFactory<E> rof = new PersistentClassROF<E>(ec, rs, ec.getFetchPlan(), resultMapping, elementCmd, elementCls);
                            return new ListStoreIterator<E>(ownerSM, rs, rof, this);
                        }
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
            throw new NucleusDataStoreException(Localiser.msg("056006", stmt),e);
        }
    }

    /**
     * Generates the statement for setting an item.
     * <PRE>
     * UPDATE LISTTABLE SET [ELEMENTCOL = ?]
     * [EMBEDDEDFIELD1=?, EMBEDDEDFIELD2=?, ...]
     * WHERE OWNERCOL = ?
     * AND INDEXCOL = ?
     * [AND DISTINGUISHER=?]
     * </PRE>
     * @return The Statement for setting an item
     */
    protected String getSetStmt()
    {
        if (setStmt == null)
        {
            synchronized (this)
            {
                StringBuilder stmt = new StringBuilder("UPDATE ").append(containerTable.toString()).append(" SET ");
                for (int i = 0; i < elementMapping.getNumberOfColumnMappings(); i++)
                {
                    if (i > 0)
                    {
                        stmt.append(",");
                    }
                    stmt.append(elementMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                    stmt.append(" = ");
                    stmt.append(elementMapping.getColumnMapping(i).getUpdateInputParameter());
                }

                stmt.append(" WHERE ");
                BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
                if (getOwnerMemberMetaData().getOrderMetaData() == null || 
                        getOwnerMemberMetaData().getOrderMetaData().isIndexedList())
                {
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, orderMapping, null, false);
                }
                if (relationDiscriminatorMapping != null)
                {
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, null, false);
                }
                setStmt = stmt.toString();
            }
        }

        return setStmt;
    }

    /**
     * Generate statement for removing a collection of items from the List.
     * <PRE>
     * DELETE FROM LISTTABLE
     * WHERE (OWNERCOL=? AND ELEMENTCOL=?) OR
     * (OWNERCOL=? AND ELEMENTCOL=?) OR
     * (OWNERCOL=? AND ELEMENTCOL=?)
     * </PRE>
     * @param elements Collection of elements to remove
     * @return Statement for deleting items from the List.
     */
    protected String getRemoveAllStmt(Collection elements)
    {
        if (elements == null || elements.size() == 0)
        {
            return null;
        }

        StringBuilder stmt = new StringBuilder("DELETE FROM ").append(containerTable.toString()).append(" WHERE ");

        boolean first = true;
        Iterator elementsIter = elements.iterator();
        while (elementsIter.hasNext())
        {
            Object element = elementsIter.next();
            stmt.append(first ? "(" : " OR (");
            BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
            BackingStoreHelper.appendWhereClauseForElement(stmt, elementMapping, element, isElementsAreSerialised(), null, false);
            if (relationDiscriminatorMapping != null)
            {
                BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, null, false);
            }
            stmt.append(")");
            first = false;
        }

        return stmt.toString();
    }

    /**
     * Method to return the SQLStatement and mapping for an iterator for this backing store.
     * Create a statement of the form
     * <pre>
     * SELECT ELEM_COLS
     * FROM JOIN_TBL
     *   [JOIN ELEM_TBL ON ELEM_TBL.ID = JOIN_TBL.ELEM_ID]
     * [WHERE]
     *   [JOIN_TBL.OWNER_ID = {value}] [AND]
     *   [JOIN_TBL.DISCRIM = {discrimValue}]
     * [ORDER BY {orderClause}]
     * </pre>
     * This is public to provide access for BulkFetchXXXHandler class(es).
     * @param ec ExecutionContext
     * @param fp FetchPlan to use in determing which fields of element to select
     * @param addRestrictionOnOwner Whether to restrict to a particular owner (otherwise functions as bulk fetch for many owners).
     * @param startIdx Start index for the iterator (or -1)
     * @param endIdx End index for the iterator (or -1)
     * @return The SQLStatement and its associated StatementClassMapping
     */
    public ElementIteratorStatement getIteratorStatement(ExecutionContext ec, FetchPlan fp, boolean addRestrictionOnOwner, int startIdx, int endIdx)
    {
        SelectStatement sqlStmt = null;
        StatementClassMapping elementClsMapping = new StatementClassMapping();
        SQLExpressionFactory exprFactory = storeMgr.getSQLExpressionFactory();
        if (elementsAreEmbedded || elementsAreSerialised)
        {
            // Element = embedded, serialised (maybe Non-PC)
            // Just select the join table since we're going to return the embedded/serialised columns from it
            sqlStmt = new SelectStatement(storeMgr, containerTable, null, null);
            sqlStmt.setClassLoaderResolver(clr);

            // Select the element column - first select is assumed by ListStoreIterator
            sqlStmt.select(sqlStmt.getPrimaryTable(), elementMapping, null);
            // TODO If embedded element and it includes 1-1/N-1 in FetchPlan then select its fields also
        }
        else if (elementMapping instanceof ReferenceMapping)
        {
            // Element = Reference type (interface/Object)
            // Just select the join table since we're going to return the implementation id columns only
            sqlStmt = new SelectStatement(storeMgr, containerTable, null, null);
            sqlStmt.setClassLoaderResolver(clr);

            // Select the reference column(s) - first select is assumed by ListStoreIterator
            sqlStmt.select(sqlStmt.getPrimaryTable(), elementMapping, null);
        }
        else
        {
            // Element = PC
            // Join to the element table(s)
            if (elementInfo != null)
            {
                for (int i = 0; i < elementInfo.length; i++)
                {
                    // TODO This will only work if all element types have a discriminator
                    final int elementNo = i;
                    final Class elementCls = clr.classForName(elementInfo[elementNo].getClassName());
                    SelectStatement elementStmt = null;
                    if (elementInfo[elementNo].getDiscriminatorStrategy() != null && elementInfo[elementNo].getDiscriminatorStrategy() != DiscriminatorStrategy.NONE)
                    {
                        // The element uses a discriminator so just use that in the SELECT
                        String elementType = ownerMemberMetaData.getCollection().getElementType();
                        if (ClassUtils.isReferenceType(clr.classForName(elementType)))
                        {
                            String[] clsNames = storeMgr.getNucleusContext().getMetaDataManager().getClassesImplementingInterface(elementType, clr);
                            Class[] cls = new Class[clsNames.length];
                            for (int j = 0; j < clsNames.length; j++)
                            {
                                cls[j] = clr.classForName(clsNames[j]);
                            }

                            SelectStatementGenerator stmtGen = new DiscriminatorStatementGenerator(storeMgr, clr, cls, true, null, null, containerTable, null, elementMapping);
                            if (allowNulls)
                            {
                                stmtGen.setOption(SelectStatementGenerator.OPTION_ALLOW_NULLS);
                            }
                            elementStmt = stmtGen.getStatement(ec);
                        }
                        else
                        {
                            SelectStatementGenerator stmtGen = new DiscriminatorStatementGenerator(storeMgr, clr, elementCls, true, null, null, containerTable, null, elementMapping);
                            if (allowNulls)
                            {
                                stmtGen.setOption(SelectStatementGenerator.OPTION_ALLOW_NULLS);
                            }
                            elementStmt = stmtGen.getStatement(ec);
                        }
                        iterateUsingDiscriminator = true;
                    }
                    else
                    {
                        // No discriminator, but subclasses so use UNIONs
                        SelectStatementGenerator stmtGen = new UnionStatementGenerator(storeMgr, clr, elementCls, true, null, null, containerTable, null, elementMapping);
                        stmtGen.setOption(SelectStatementGenerator.OPTION_SELECT_DN_TYPE);
                        elementClsMapping.setNucleusTypeColumnName(UnionStatementGenerator.DN_TYPE_COLUMN);
                        elementStmt = stmtGen.getStatement(ec);
                    }

                    if (sqlStmt == null)
                    {
                        sqlStmt = elementStmt;
                    }
                    else
                    {
                        sqlStmt.union(elementStmt);
                    }
                }

                if (sqlStmt == null)
                {
                    throw new NucleusException("Error in generation of SQL statement for iterator over (Join) list. Statement is null");
                }

                // Select the required fields
                SQLTable elementSqlTbl = sqlStmt.getTable(elementInfo[0].getDatastoreClass(), sqlStmt.getPrimaryTable().getGroupName());
                SQLStatementHelper.selectFetchPlanOfSourceClassInStatement(sqlStmt, elementClsMapping, fp, elementSqlTbl, elementCmd, fp.getMaxFetchDepth());
            }
            else
            {
                throw new NucleusException("Unable to create SQL statement to retrieve elements of List");
            }
        }

        if (addRestrictionOnOwner)
        {
            // Apply condition on join-table owner field to filter by owner
            SQLTable ownerSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), ownerMapping);
            SQLExpression ownerExpr = exprFactory.newExpression(sqlStmt, ownerSqlTbl, ownerMapping);
            SQLExpression ownerVal = exprFactory.newLiteralParameter(sqlStmt, ownerMapping, null, "OWNER");
            sqlStmt.whereAnd(ownerExpr.eq(ownerVal), true);
        }

        if (relationDiscriminatorMapping != null)
        {
            // Apply condition on distinguisher field to filter by distinguisher (when present)
            SQLTable distSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), relationDiscriminatorMapping);
            SQLExpression distExpr = exprFactory.newExpression(sqlStmt, distSqlTbl, relationDiscriminatorMapping);
            SQLExpression distVal = exprFactory.newLiteral(sqlStmt, relationDiscriminatorMapping, relationDiscriminatorValue);
            sqlStmt.whereAnd(distExpr.eq(distVal), true);
        }

        if (indexedList)
        {
            // "Indexed List" so allow restriction on returned indexes
            boolean needsOrdering = true;
            if (startIdx == -1 && endIdx == -1)
            {
                // Just restrict to >= 0 so we don't get any disassociated elements
                SQLExpression indexExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), orderMapping);
                SQLExpression indexVal = exprFactory.newLiteral(sqlStmt, orderMapping, 0);
                sqlStmt.whereAnd(indexExpr.ge(indexVal), true);
            }
            else if (startIdx >= 0 && endIdx == startIdx)
            {
                // Particular index required so add restriction
                needsOrdering = false;
                SQLExpression indexExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), orderMapping);
                SQLExpression indexVal = exprFactory.newLiteral(sqlStmt, orderMapping, startIdx);
                sqlStmt.whereAnd(indexExpr.eq(indexVal), true);
            }
            else
            {
                // Add restrictions on start/end indices as required
                if (startIdx >= 0)
                {
                    SQLExpression indexExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), orderMapping);
                    SQLExpression indexVal = exprFactory.newLiteral(sqlStmt, orderMapping, startIdx);
                    sqlStmt.whereAnd(indexExpr.ge(indexVal), true);
                }
                else
                {
                    // Just restrict to >= 0 so we don't get any disassociated elements
                    SQLExpression indexExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), orderMapping);
                    SQLExpression indexVal = exprFactory.newLiteral(sqlStmt, orderMapping, 0);
                    sqlStmt.whereAnd(indexExpr.ge(indexVal), true);
                }

                if (endIdx >= 0)
                {
                    SQLExpression indexExpr2 = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), orderMapping);
                    SQLExpression indexVal2 = exprFactory.newLiteral(sqlStmt, orderMapping, endIdx);
                    sqlStmt.whereAnd(indexExpr2.lt(indexVal2), true);
                }
            }

            if (needsOrdering)
            {
                // Order by the ordering column, when present
                SQLTable orderSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), orderMapping);
                SQLExpression[] orderExprs = new SQLExpression[orderMapping.getNumberOfColumnMappings()];
                boolean descendingOrder[] = new boolean[orderMapping.getNumberOfColumnMappings()];
                orderExprs[0] = exprFactory.newExpression(sqlStmt, orderSqlTbl, orderMapping);
                sqlStmt.setOrdering(orderExprs, descendingOrder);
            }
        }
        else
        {
            if (elementInfo != null)
            {
                // Apply ordering defined by <order-by>
                DatastoreClass elementTbl = elementInfo[0].getDatastoreClass();
                FieldOrder[] orderComponents = ownerMemberMetaData.getOrderMetaData().getFieldOrders();
                SQLExpression[] orderExprs = new SQLExpression[orderComponents.length];
                boolean[] orderDirs = new boolean[orderComponents.length];

                for (int i=0;i<orderComponents.length;i++)
                {
                    String fieldName = orderComponents[i].getFieldName();
                    JavaTypeMapping fieldMapping = elementTbl.getMemberMapping(elementInfo[0].getAbstractClassMetaData().getMetaDataForMember(fieldName));
                    orderDirs[i] = !orderComponents[i].isForward();
                    SQLTable fieldSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), fieldMapping);
                    orderExprs[i] = exprFactory.newExpression(sqlStmt, fieldSqlTbl, fieldMapping);
                }

                sqlStmt.setOrdering(orderExprs, orderDirs);
            }
        }

        return new ElementIteratorStatement(this, sqlStmt, elementClsMapping);
    }
}