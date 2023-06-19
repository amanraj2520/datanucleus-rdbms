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
import org.datanucleus.ClassNameConstants;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlan;
import org.datanucleus.PersistableObjectType;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.CollectionMetaData;
import org.datanucleus.metadata.DiscriminatorStrategy;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.metadata.OrderMetaData.FieldOrder;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.mapping.MappingHelper;
import org.datanucleus.store.rdbms.mapping.MappingType;
import org.datanucleus.store.rdbms.mapping.java.EmbeddedPCMapping;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.mapping.java.ReferenceMapping;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.SQLController;
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
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.store.types.scostore.ListStore;
import org.datanucleus.store.types.wrappers.backed.BackedSCO;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Implementation of a {@link ListStore} using foreign keys.
 */
public class FKListStore<E> extends AbstractListStore<E>
{
    /** Statement for updating a foreign key in a 1-N unidirectional */
    private String updateFkStmt;

    private String clearNullifyStmt;

    private String removeAtNullifyStmt;

    private String setStmt;
    private String unsetStmt;

    /**
     * @param mmd Metadata for owning field/property
     * @param storeMgr Manager for the datastore
     * @param clr ClassLoader resolver
     */
    public FKListStore(AbstractMemberMetaData mmd, RDBMSStoreManager storeMgr, ClassLoaderResolver clr)
    {
        super(storeMgr, clr);

        setOwner(mmd);
        CollectionMetaData colmd = mmd.getCollection();
        if (colmd == null)
        {
            throw new NucleusUserException(Localiser.msg("056001", mmd.getFullFieldName()));
        }

        // Load the element class
        elementType = colmd.getElementType();
        Class element_class = clr.classForName(elementType);
        if (ClassUtils.isReferenceType(element_class))
        {
            elementIsPersistentInterface = storeMgr.getNucleusContext().getMetaDataManager().isPersistentInterface(element_class.getName());
            if (elementIsPersistentInterface)
            {
                elementCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForInterface(element_class,clr);
            }
            else
            {
                // Take the metadata for the first implementation of the reference type
                elementCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForImplementationOfReference(element_class,null,clr);
            }
        }
        else
        {
            // Check that the element class has MetaData
            elementCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(element_class, clr);
        }
        if (elementCmd == null)
        {
            throw new NucleusUserException(Localiser.msg("056003", element_class.getName(), mmd.getFullFieldName()));
        }

        elementInfo = getComponentInformationForClass(elementType, elementCmd);
        if (elementInfo == null || elementInfo.length == 0)
        {
            throw new NucleusUserException(Localiser.msg("056075", ownerMemberMetaData.getFullFieldName(), elementType));
        }
        else if (elementInfo.length == 1 && ClassUtils.isReferenceType(element_class))
        {
            // Special case : reference element type (interface/object) and single "implementation"
            elementType = elementCmd.getFullClassName();
        }

        elementMapping = elementInfo[0].getDatastoreClass().getIdMapping(); // Just use the first element type as the guide for the element mapping
        elementsAreEmbedded = false; // Can't embed element when using FK relation
        elementsAreSerialised = false; // Can't serialise element when using FK relation

        // Find the mapping back to the owning object
        for (int i=0;i<elementInfo.length;i++)
        {
            JavaTypeMapping ownerMapping = null;
            if (mmd.getMappedBy() != null)
            {
                // 1-N FK bidirectional - the element class has a field for the owner
                if (mmd.getMappedBy().indexOf('.') < 0)
                {
                    AbstractMemberMetaData eofmd = elementInfo[i].getAbstractClassMetaData().getMetaDataForMember(mmd.getMappedBy());
                    if (eofmd == null)
                    {
                        // Field for owner not found in element
                        throw new NucleusUserException(Localiser.msg("056024", mmd.getFullFieldName(), mmd.getMappedBy(), element_class.getName()));
                    }
                    if (!clr.isAssignableFrom(eofmd.getType(), mmd.getAbstractClassMetaData().getFullClassName()))
                    {
                        // Type of the element "mapped-by" field is not consistent with the owner type
                        throw new NucleusUserException(Localiser.msg("056025", mmd.getFullFieldName(), 
                            eofmd.getFullFieldName(), eofmd.getTypeName(), mmd.getAbstractClassMetaData().getFullClassName()));
                    }

                    String ownerFieldName = eofmd.getName();
                    ownerMapping = elementInfo[i].getDatastoreClass().getMemberMapping(eofmd);
                    if (ownerMapping == null)
                    {
                        throw new NucleusUserException(Localiser.msg("056029", mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), elementType, ownerFieldName));
                    }
                    if (isEmbeddedMapping(ownerMapping))
                    {
                        throw new NucleusUserException(Localiser.msg("056026", ownerFieldName, elementType, eofmd.getTypeName(), mmd.getClassName()));
                    }
                }
                else
                {
                    // mappedBy uses DOT notation, so refers to a field in an embedded field of the element
                    AbstractMemberMetaData otherMmd = null;
                    AbstractClassMetaData otherCmd = elementCmd;
                    String remainingMappedBy = ownerMemberMetaData.getMappedBy();
                    JavaTypeMapping otherMapping = null;
                    while (remainingMappedBy.indexOf('.') > 0)
                    {
                        // JPA mappedBy dot notation
                        int dotPosition = remainingMappedBy.indexOf('.');
                        String thisMappedBy = remainingMappedBy.substring(0, dotPosition);
                        otherMmd = otherCmd.getMetaDataForMember(thisMappedBy);
                        if (otherMapping == null)
                        {
                            otherMapping = elementInfo[i].getDatastoreClass().getMemberMapping(thisMappedBy);
                        }
                        else
                        {
                            if (!(otherMapping instanceof EmbeddedPCMapping))
                            {
                                throw new NucleusUserException("Processing of mappedBy DOT notation for " + ownerMemberMetaData.getFullFieldName() + " found mapping=" + otherMapping + 
                                        " but expected to be embedded");
                            }
                            otherMapping = ((EmbeddedPCMapping)otherMapping).getJavaTypeMapping(thisMappedBy);
                        }

                        remainingMappedBy = remainingMappedBy.substring(dotPosition+1);
                        otherCmd = storeMgr.getMetaDataManager().getMetaDataForClass(otherMmd.getTypeName(), clr); // TODO Cater for N-1
                        if (remainingMappedBy.indexOf('.') < 0)
                        {
                            if (!(otherMapping instanceof EmbeddedPCMapping))
                            {
                                throw new NucleusUserException("Processing of mappedBy DOT notation for " + ownerMemberMetaData.getFullFieldName() + " found mapping=" + otherMapping + 
                                        " but expected to be embedded");
                            }
                            otherMapping = ((EmbeddedPCMapping)otherMapping).getJavaTypeMapping(remainingMappedBy);
                        }
                    }
                    ownerMapping = otherMapping;
                }
            }
            else
            {
                // 1-N FK unidirectional : The element class knows nothing about the owner (but its table has external mappings)
                ownerMapping = elementInfo[i].getDatastoreClass().getExternalMapping(mmd, MappingType.EXTERNAL_FK);
                // TODO Allow for the situation where the user specified "table" in the elementMetaData to put the FK in a supertable. This only checks against default element table
                if (ownerMapping == null)
                {
                    throw new NucleusUserException(Localiser.msg("056030", mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), elementType));
                }
            }
            elementInfo[i].setOwnerMapping(ownerMapping);
        }
        this.ownerMapping = elementInfo[0].getOwnerMapping(); // TODO Get rid of ownerMapping and refer to elementInfo[i].getOwnerMapping

        // TODO If we have List<interface> we need to find the index by mappedBy name
        if (mmd.getOrderMetaData() != null && !mmd.getOrderMetaData().isIndexedList())
        {
            indexedList = false;
        }
        if (indexedList)
        {
            orderMapping = elementInfo[0].getDatastoreClass().getExternalMapping(mmd, MappingType.EXTERNAL_INDEX);
            if (orderMapping == null)
            {
                // "Indexed List" but no order mapping present!
                throw new NucleusUserException(Localiser.msg("056041", mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), elementType));
            }
        }

        relationDiscriminatorMapping = elementInfo[0].getDatastoreClass().getExternalMapping(mmd, MappingType.EXTERNAL_FK_DISCRIMINATOR);
        if (relationDiscriminatorMapping != null)
        {
            relationDiscriminatorValue = mmd.getValueForExtension("relation-discriminator-value");
            if (relationDiscriminatorValue == null)
            {
                // No value defined so just use the field name
                relationDiscriminatorValue = mmd.getFullFieldName();
            }
        }

        // TODO Cater for multiple element tables
        containerTable = elementInfo[0].getDatastoreClass();
        if (mmd.getMappedBy() != null && ownerMapping.getTable() != containerTable)
        {
            // Element and owner don't have consistent tables so use the one with the mapping
            // e.g collection is of subclass, yet superclass has the link back to the owner
            containerTable = ownerMapping.getTable();
        }
    }

    @Override
    public E set(DNStateManager ownerSM, int index, E element, boolean allowDependentField)
    {
        validateElementForWriting(ownerSM, element, -1); // Last argument means don't set the position on any INSERT

        // Find the original element at this position
        E oldElement  = null;
        List fieldVal = (List) ownerSM.provideField(ownerMemberMetaData.getAbsoluteFieldNumber());
        if (fieldVal != null && fieldVal instanceof BackedSCO && ((BackedSCO)fieldVal).isLoaded())
        {
            // Already loaded in the wrapper
            oldElement = (E) fieldVal.get(index);
        }
        else
        {
            oldElement = get(ownerSM, index);
        }

        ManagedConnection mconn = null;
        try
        {
            ExecutionContext ec = ownerSM.getExecutionContext();
            SQLController sqlControl = storeMgr.getSQLController();
            mconn = storeMgr.getConnectionManager().getConnection(ec);

            // Unset the existing object from this position
            String theUnsetStmt = getUnsetStmt();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, theUnsetStmt, false);
                try
                {
                    int jdbcPosition = 1;
                    jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                    if (orderMapping != null)
                    {
                        jdbcPosition = BackingStoreHelper.populateOrderInStatement(ec, ps, index, jdbcPosition, orderMapping);
                    }
                    if (relationDiscriminatorMapping != null)
                    {
                        jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                    }

                    sqlControl.executeStatementUpdate(ec, mconn, theUnsetStmt, ps, true);
                }
                finally
                {
                    sqlControl.closeStatement(mconn, ps);
                }
            }
            catch (SQLException e)
            {
                throw new NucleusDataStoreException(Localiser.msg("056015", theUnsetStmt), e);
            }
            finally
            {
            }

            // Set the new object at this position
            String theSetStmt = getSetStmt(element);
            try
            {
                PreparedStatement ps2 = sqlControl.getStatementForUpdate(mconn, theSetStmt, false);
                try
                {
                    ComponentInfo elemInfo = getComponentInfoForElement(element);
                    JavaTypeMapping elemMapping = this.elementMapping;
                    JavaTypeMapping orderMapping = this.orderMapping;
                    if (elemInfo != null)
                    {
                        elemMapping = elemInfo.getDatastoreClass().getIdMapping();
                        orderMapping = elemInfo.getDatastoreClass().getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_INDEX);
                    }

                    int jdbcPosition = 1;
                    jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps2, jdbcPosition, this);
                    if (orderMapping != null)
                    {
                        jdbcPosition = BackingStoreHelper.populateOrderInStatement(ec, ps2, index, jdbcPosition, orderMapping);
                    }
                    if (relationDiscriminatorMapping != null)
                    {
                        jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps2, jdbcPosition, this);
                    }
                    jdbcPosition = BackingStoreHelper.populateElementForWhereClauseInStatement(ec, ps2, element, jdbcPosition, elemMapping);

                    sqlControl.executeStatementUpdate(ec, mconn, theSetStmt, ps2, true);
                }
                finally
                {
                    sqlControl.closeStatement(mconn, ps2);
                }
            }
            catch (SQLException e)
            {
                throw new NucleusDataStoreException(Localiser.msg("056015", theSetStmt), e);
            }
        }
        finally
        {
            if (mconn != null)
            {
                mconn.release();
            }
        }

        // Dependent field
        boolean dependent = getOwnerMemberMetaData().getCollection().isDependentElement();
        if (getOwnerMemberMetaData().isCascadeRemoveOrphans())
        {
            dependent = true;
        }
        if (dependent && allowDependentField)
        {
            if (oldElement != null)
            {
                // Delete the element if it is dependent and doesnt have a duplicate entry in the list
                ownerSM.getExecutionContext().deleteObjectInternal(oldElement);
            }
        }

        return oldElement;
    }

    /**
     * Utility to update a foreign-key in the element in the case of a unidirectional 1-N relationship.
     * @param ownerSM StateManager for the owner
     * @param element The element to update
     * @param owner The owner object to set in the FK
     * @param index The index position (or -1 if not known)
     * @return Whether it was performed successfully
     */
    private boolean updateElementFk(DNStateManager ownerSM, Object element, Object owner, int index)
    {
        if (element == null)
        {
            return false;
        }

        ExecutionContext ec = ownerSM.getExecutionContext();
        String updateFkStmt = getUpdateFkStmt(element);
        boolean retval;
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, updateFkStmt, false);
                try
                {
                    ComponentInfo elemInfo = getComponentInfoForElement(element);
                    JavaTypeMapping ownerMapping = elemInfo.getOwnerMapping();
                    JavaTypeMapping elemMapping = elemInfo.getDatastoreClass().getIdMapping();
                    JavaTypeMapping orderMapping = elemInfo.getDatastoreClass().getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_INDEX);

                    int jdbcPosition = 1;
                    if (owner == null)
                    {
                        if (ownerMemberMetaData != null)
                        {
                            ownerMapping.setObject(ec, ps, MappingHelper.getMappingIndices(jdbcPosition, ownerMapping), null, 
                                ownerSM, ownerMemberMetaData.getAbsoluteFieldNumber());
                        }
                        else
                        {
                            ownerMapping.setObject(ec, ps, MappingHelper.getMappingIndices(jdbcPosition, ownerMapping), null);
                        }
                        jdbcPosition += ownerMapping.getNumberOfColumnMappings();
                    }
                    else
                    {
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                    }
                    if (orderMapping != null)
                    {
                        jdbcPosition = BackingStoreHelper.populateOrderInStatement(ec, ps, index, jdbcPosition, orderMapping);
                    }
                    if (relationDiscriminatorMapping != null)
                    {
                        jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                    }
                    jdbcPosition = BackingStoreHelper.populateElementForWhereClauseInStatement(ec, ps, element, jdbcPosition, elemMapping);

                    sqlControl.executeStatementUpdate(ec, mconn, updateFkStmt, ps, true);
                    retval = true;
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
            throw new NucleusDataStoreException(Localiser.msg("056027", updateFkStmt), e);
        }

        return retval;
    }

    @Override
    public void update(DNStateManager ownerSM, Collection<? extends E> coll)
    {
        if (coll == null || coll.isEmpty())
        {
            clear(ownerSM);
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

    /**
     * Internal method for adding item(s) to the List as a specific position.
     * @param ownerSM StateManager for the owner
     * @param startAt The start position
     * @param atEnd Whether to add at the end
     * @param c The Collection of elements to add.
     * @param size Current size of list (if known). -1 if not known
     * @return Whether it was successful
     */
    protected boolean internalAdd(DNStateManager ownerSM, int startAt, boolean atEnd, Collection<? extends E> c, int size)
    {
        if (c == null || c.size() == 0)
        {
            return true;
        }

        // Check what we have persistent already
        int currentListSize = (size < 0 ? size(ownerSM) : size);

        boolean shiftingElements = true;
        if (atEnd || startAt == currentListSize)
        {
            shiftingElements = false;
            startAt = currentListSize; // Not shifting so we insert from the end
        }

        boolean elementsNeedPositioning = false;
        int position = startAt;
        Iterator elementIter = c.iterator();
        while (elementIter.hasNext())
        {
            // Persist any non-persistent objects optionally at their final list position (persistence-by-reachability)
            if (shiftingElements)
            {
                // We have to shift things so dont bother with positioning
                position = -1;
            }

            boolean inserted = validateElementForWriting(ownerSM, elementIter.next(), position);
            if (!inserted || shiftingElements)
            {
                // This element wasn't positioned in the validate so we need to set the positions later
                elementsNeedPositioning = true;
            }
            if (!shiftingElements)
            {
                position++;
            }
        }

        if (shiftingElements)
        {
            // We need to shift existing elements before positioning the new ones
            // e.g if insert at start then do "UPDATE IDX=IDX+1 WHERE ID={...}" or similar, where the ID values are all ids after the position we insert at
            // Calculate the amount we need to shift any existing elements by
            // This is used where inserting between existing elements and have to shift down all elements after the start point
            int shift = c.size();

            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ownerSM.getExecutionContext());
            try
            {
                // shift existing elements after start position by "shift" to make room for new elements
                internalShiftBulk(ownerSM, startAt-1, shift, mconn, true, true);
            }
            finally
            {
                mconn.release();
            }
        }

        if (shiftingElements || elementsNeedPositioning)
        {
            // Some elements have been shifted so the new elements need positioning now, or we already had some
            // of the new elements persistent and so they need their positions setting now
            for (E element : c)
            {
                updateElementFk(ownerSM, element, ownerSM.getObject(), startAt);
                startAt++;
            }
        }

        return true;
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
        boolean dependent = (collmd.isDependentElement() || ownerMemberMetaData.isCascadeRemoveOrphans());
        if (dependent && !collmd.isEmbeddedElement())
        {
            // Delete the element if it is dependent and doesn't have a duplicate entry in the list
            ownerSM.getExecutionContext().deleteObjectInternal(element);
        }

        return element;
    }

    @Override
    public boolean removeAll(DNStateManager ownerSM, Collection elements, int size)
    {
        if (elements == null || elements.size() == 0)
        {
            return false;
        }

        if (indexedList)
        {
            // Get the indices of the elements to remove from the datastore
            int[] indices = getIndicesOf(ownerSM, elements);
            if (indices == null)
            {
                return false;
            }
            return removeAll(ownerSM, elements, size, indices);
        }

        return removeAll(ownerSM, elements, size, null);
    }

    @Override
    public boolean removeAll(DNStateManager ownerSM, Collection elements, int size, int[] elementIndices)
    {
        if (elements == null || elements.size() == 0)
        {
            return false;
        }

        boolean modified = false;
        if (indexedList)
        {
            // Ensure we have all indices of the elements (highest first)
            int[] indices = (elementIndices != null) ? elementIndices : getIndicesOf(ownerSM, elements);
            if (indices == null || indices.length == 0)
            {
                return false;
            }

            if (ownerMapping.isNullable())
            {
                NucleusLogger.DATASTORE.debug(Localiser.msg("056043"));

                // Get original size (prior to removals) from datastore if not provided
                int currentListSize = (size < 0) ? size(ownerSM) : size;

                ExecutionContext ec = ownerSM.getExecutionContext();
                ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
                try
                {
                    // Null out the specified indices
                    internalRemoveAtNullifyBulk(ownerSM, indices, mconn, false, true);

                    for (int i=0;i<indices.length;i++)
                    {
                        // Shift the subsequent elements to fill the gaps
                        if (indices[i] != currentListSize - 1)
                        {
                            // shift all subsequent elements down by 1 in single statement
                            internalShiftBulk(ownerSM, indices[i], -1, mconn, true, i == indices.length-1);
                        }
                        currentListSize--;
                    }
                }
                finally
                {
                    mconn.release();
                }
            }
            else
            {
                // Indexed list with non-nullable owner!
            }

            // Dependent-element
            if (ownerMemberMetaData.isCascadeRemoveOrphans() || ownerMemberMetaData.getCollection().isDependentElement())
            {
                // "delete-dependent" : delete elements if the collection is marked as dependent
                // TODO What if the collection contains elements that are not in the List ? should not delete them
                ownerSM.getExecutionContext().deleteObjects(elements.toArray());
            }
        }
        else
        {
            // Ordered List
            for (Object element : elements)
            {
                boolean success = remove(ownerSM, element, size, true);
                if (success)
                {
                    modified = true;
                }
            }
        }

        return modified;
    }

    /**
     * Convenience method to remove the specified element from the List.
     * @param ownerSM StateManager for the owner
     * @param element The element
     * @return Whether the List was modified
     */
    protected boolean internalRemove(DNStateManager ownerSM, Object element, int size)
    {
        if (indexedList)
        {
            // Indexed List
            // The element can be at one position only (no duplicates allowed in FK list)
            int index = indexOf(ownerSM, element);
            if (index == -1)
            {
                return false;
            }
            internalRemoveAt(ownerSM, index, size);
        }
        else
        {
            // Ordered List - no index so null the FK (if nullable) or delete the element
            if (ownerMapping.isNullable())
            {
                // Nullify the FK
                ExecutionContext ec = ownerSM.getExecutionContext();
                DNStateManager elementSM = ec.findStateManager(element);
                if (relationType == RelationType.ONE_TO_MANY_BI)
                {
                    // Set field in element to null (so it nulls the FK)
                    // TODO This is ManagedRelations - move into RelationshipManager
                    elementSM.replaceFieldMakeDirty(ownerMemberMetaData.getRelatedMemberMetaData(clr)[0].getAbsoluteFieldNumber(), null);
                    if (ownerSM.getExecutionContext().isFlushing())
                    {
                        elementSM.flush();
                    }
                }
                else
                {
                    // Null the (external) FK in the element
                    updateElementFk(ownerSM, element, null, -1);
                }
            }
            else
            {
                // Delete the element
                ownerSM.getExecutionContext().deleteObjectInternal(element);
            }
        }

        return true;
    }

    /**
     * Internal method to remove an object at a location in the List.
     * Differs from the JoinTable List in that it typically nulls out the owner FK (unless not nullable).
     * @param ownerSM StateManager for the owner
     * @param index The location
     * @param size Current size of list (if known). -1 if not known
     */
    protected void internalRemoveAt(DNStateManager ownerSM, int index, int size)
    {
        if (!indexedList)
        {
            throw new NucleusUserException("Cannot remove an element from a particular position with an ordered list since no indexes exist");
        }

        if (ownerMapping.isNullable())
        {
            NucleusLogger.DATASTORE.debug(Localiser.msg("056043"));
            ExecutionContext ec = ownerSM.getExecutionContext();
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            try
            {
                // Null out the element
                internalRemoveAtNullify(ownerSM, index, mconn, true, false);

                // Get current size from datastore if not provided
                int currentListSize = (size < 0) ? size(ownerSM) : size;

                // Shift the subsequent elements to fill the gap
                if (index != currentListSize - 1)
                {
                    // shift all subsequent elements down by 1 in single statement
                    internalShiftBulk(ownerSM, index, -1, mconn, true, true);
                }
            }
            finally
            {
                mconn.release();
            }
        }
        else
        {
            // Indexed list with owner mapping that is not nullable! Are we going to attempt to support this?
            // TODO Really ought to make this delete via ExecutionContext (sm.getExecutionContext().deleteObjectInternal(element)) but we need the element object
            NucleusLogger.DATASTORE.debug(Localiser.msg("056042"));
            String stmt = getRemoveAtStmt();
            internalRemoveAt(ownerSM, index, stmt, size);
        }
    }

    /**
     * Internal method to remove an object at a location in the List by nulling its owner and setting its List index to -1.
     * @param ownerSM StateManager for the list owner
     * @param index The index to nullify
     * @param mconn Managed Connection to use for datastore connectivity
     * @param batched Whether this statement should be batched
     * @param executeNow Whether we should execute this statement now
     */
    protected void internalRemoveAtNullify(DNStateManager ownerSM, int index, ManagedConnection mconn, boolean batched, boolean executeNow)
    {
        String stmt = getRemoveAtNullifyStmt();
        ExecutionContext ec = ownerSM.getExecutionContext();
        try
        {
            SQLController sqlControl = storeMgr.getSQLController();
            PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, stmt, batched);
            try
            {
                int jdbcPosition = 1;
                jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                jdbcPosition = BackingStoreHelper.populateOrderInStatement(ec, ps, index, jdbcPosition, orderMapping);
                if (relationDiscriminatorMapping != null)
                {
                    jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                }

                /* int[] rowsUpdated = */sqlControl.executeStatementUpdate(ec, mconn, stmt, ps, executeNow);
            }
            finally
            {
                sqlControl.closeStatement(mconn, ps);
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056012", stmt), e);
        }
    }

    /**
     * Internal method to remove an object at a location in the List by nulling its owner and setting its List index to -1.
     * @param ownerSM StateManager for the list owner
     * @param indices The indexes to nullify
     * @param mconn Managed Connection to use for datastore connectivity
     * @param batched Whether this statement should be batched
     * @param executeNow Whether we should execute this statement now
     */
    protected void internalRemoveAtNullifyBulk(DNStateManager ownerSM, int[] indices, ManagedConnection mconn, boolean batched, boolean executeNow)
    {
        String stmt = getRemoveAtNullifyBulkStmt(indices);
        ExecutionContext ec = ownerSM.getExecutionContext();
        try
        {
            SQLController sqlControl = storeMgr.getSQLController();
            PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, stmt, batched);
            try
            {
                int jdbcPosition = 1;
                for (int i=0;i<indices.length;i++)
                {
                    int index = indices[i];

                    jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                    jdbcPosition = BackingStoreHelper.populateOrderInStatement(ec, ps, index, jdbcPosition, orderMapping);
                    if (relationDiscriminatorMapping != null)
                    {
                        jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                    }
                }

                /* int[] rowsUpdated = */sqlControl.executeStatementUpdate(ec, mconn, stmt, ps, executeNow);
            }
            finally
            {
                sqlControl.closeStatement(mconn, ps);
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056012", stmt), e);
        }
    }

    /**
     * Method to clear the List.
     * This is called by the List.clear() method, or when the container object is being deleted
     * and the elements are to be removed (maybe for dependent field), or also when updating a Collection
     * and removing all existing prior to adding all new.
     * @param ownerSM StateManager for the owner
     */
    @Override
    public void clear(DNStateManager ownerSM)
    {
        boolean deleteElements = false;
        boolean dependent = (ownerMemberMetaData.getCollection().isDependentElement() || ownerMemberMetaData.isCascadeRemoveOrphans());
        if (dependent)
        {
            // Elements are dependent and can't exist on their own, so delete them all
            NucleusLogger.DATASTORE.debug(Localiser.msg("056034"));
            deleteElements = true;
        }
        else
        {
            if (ownerMapping.isNullable() && orderMapping == null)
            {
                // Field is not dependent, and nullable so we null the FK
                NucleusLogger.DATASTORE.debug(Localiser.msg("056036"));
                deleteElements = false;
            }
            else if (ownerMapping.isNullable() && orderMapping != null && orderMapping.isNullable())
            {
                // Field is not dependent, and nullable so we null the FK
                NucleusLogger.DATASTORE.debug(Localiser.msg("056036"));
                deleteElements = false;
            }
            else
            {
                // Field is not dependent, and not nullable so we just delete the elements
                NucleusLogger.DATASTORE.debug(Localiser.msg("056035"));
                deleteElements = true;
            }
        }

        ExecutionContext ec = ownerSM.getExecutionContext();
        if (deleteElements)
        {
            // Find elements present in the datastore and delete them one-by-one
            Iterator elementsIter = iterator(ownerSM);
            if (elementsIter != null)
            {
                while (elementsIter.hasNext())
                {
                    Object element = elementsIter.next();
                    if (ec.getApiAdapter().isPersistable(element) && ec.getApiAdapter().isDeleted(element))
                    {
                        // Element is waiting to be deleted so flush it (it has the FK)
                        DNStateManager objSM = ec.findStateManager(element);
                        objSM.flush();
                    }
                    else
                    {
                        // Element not yet marked for deletion so go through the normal process
                        ec.deleteObjectInternal(element);
                    }
                }
            }
        }
        else
        {
            boolean ownerSoftDelete = ownerSM.getClassMetaData().isSoftDelete();
            if (!ownerSoftDelete)
            {
                // Clear without delete
                // TODO If the relation is bidirectional we need to clear the owner in the element
                String clearNullifyStmt = getClearNullifyStmt();
                try
                {
                    ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
                    SQLController sqlControl = storeMgr.getSQLController();
                    try
                    {
                        PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, clearNullifyStmt, false);
                        try
                        {
                            int jdbcPosition = 1;
                            jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                            if (relationDiscriminatorMapping != null)
                            {
                                BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                            }
                            sqlControl.executeStatementUpdate(ec, mconn, clearNullifyStmt, ps, true);
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
                    throw new NucleusDataStoreException(Localiser.msg("056013", clearNullifyStmt), e);
                }
            }
        }
    }

    /**
     * Method to validate that an element is valid for writing to the datastore.
     * TODO Minimise differences to super.validateElementForWriting()
     * @param ownerSM StateManager for the List owner
     * @param element The element to validate
     * @param index The position that the element is being stored at in the list
     * @return Whether the element was inserted
     */
    protected boolean validateElementForWriting(final DNStateManager ownerSM, final Object element, final int index)
    {
        final Object newOwner = ownerSM.getObject();

        ComponentInfo info = getComponentInfoForElement(element);

        final DatastoreClass elementTable = storeMgr.getNucleusContext().getMetaDataManager().isPersistentInterface(elementType) ?
            storeMgr.getDatastoreClass(storeMgr.getNucleusContext().getMetaDataManager().getImplementationNameForPersistentInterface(elementType), clr) :
            storeMgr.getDatastoreClass(element.getClass().getName(), clr);

        final JavaTypeMapping orderMapping = (info != null) ?
            info.getDatastoreClass().getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_INDEX) : this.orderMapping;

        // Check if element is ok for use in the datastore, specifying any external mappings that may be required
        boolean inserted = super.validateElementForWriting(ownerSM.getExecutionContext(), element, new FieldValues()
        {
            public void fetchFields(DNStateManager elemOP)
            {
                // Find the (element) table storing the FK back to the owner
                if (elementTable != null)
                {
                    JavaTypeMapping externalFKMapping = elementTable.getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_FK);
                    if (externalFKMapping != null)
                    {
                        // The element has an external FK mapping so set the value it needs to use in the INSERT
                        elemOP.setAssociatedValue(externalFKMapping, ownerSM.getObject());
                    }
                    if (relationDiscriminatorMapping != null)
                    {
                        elemOP.setAssociatedValue(relationDiscriminatorMapping, relationDiscriminatorValue);
                    }
                    if (orderMapping != null && index >= 0)
                    {
                        if (ownerMemberMetaData.getOrderMetaData() != null && ownerMemberMetaData.getOrderMetaData().getMappedBy() != null)
                        {
                            // Order is stored in a field in the element so update it
                            // We support mapped-by fields of types int/long/Integer/Long currently
                            Object indexValue = null;
                            if (orderMapping.getMemberMetaData().getTypeName().equals(ClassNameConstants.JAVA_LANG_LONG) ||
                                orderMapping.getMemberMetaData().getTypeName().equals(ClassNameConstants.LONG))
                            {
                                indexValue = Long.valueOf(index);
                            }
                            else
                            {
                                indexValue = Integer.valueOf(index);
                            }
                            elemOP.replaceFieldMakeDirty(orderMapping.getMemberMetaData().getAbsoluteFieldNumber(), indexValue);
                        }
                        else
                        {
                            // Order is stored in a surrogate column so save its vaue for the element to use later
                            elemOP.setAssociatedValue(orderMapping, Integer.valueOf(index));
                        }
                    }
                }

                if (ownerMemberMetaData.getMappedBy() != null)
                {
                    // TODO This is ManagedRelations - move into RelationshipManager
                    // Managed Relations : 1-N bidir, so make sure owner is correct at persist
                    // TODO Support DOT notation in mappedBy
                    DNStateManager ownerHolderSM = elemOP;
                    int ownerFieldNumberInHolder = -1;
                    if (ownerMemberMetaData.getMappedBy().indexOf('.') > 0)
                    {
                        AbstractMemberMetaData otherMmd = null;
                        AbstractClassMetaData otherCmd = info.getAbstractClassMetaData();
                        String remainingMappedBy = ownerMemberMetaData.getMappedBy();
                        while (remainingMappedBy.indexOf('.') > 0)
                        {
                            // JPA mappedBy dot notation
                            int dotPosition = remainingMappedBy.indexOf('.');
                            String thisMappedBy = remainingMappedBy.substring(0, dotPosition);
                            otherMmd = otherCmd.getMetaDataForMember(thisMappedBy);

                            Object holderValueAtField = ownerHolderSM.provideField(otherMmd.getAbsoluteFieldNumber());
                            ownerHolderSM = ownerSM.getExecutionContext().findStateManagerForEmbedded(holderValueAtField, 
                                ownerHolderSM, otherMmd, PersistableObjectType.EMBEDDED_COLLECTION_ELEMENT_PC);

                            remainingMappedBy = remainingMappedBy.substring(dotPosition+1);
                            otherCmd = storeMgr.getMetaDataManager().getMetaDataForClass(otherMmd.getTypeName(), clr);
                            if (remainingMappedBy.indexOf('.') < 0)
                            {
                                otherMmd = otherCmd.getMetaDataForMember(remainingMappedBy);
                                ownerFieldNumberInHolder = otherMmd.getAbsoluteFieldNumber();
                            }
                        }
                    }
                    else
                    {
                        ownerFieldNumberInHolder = info.getAbstractClassMetaData().getAbsolutePositionOfMember(ownerMemberMetaData.getMappedBy());
                    }

                    Object currentOwner = ownerHolderSM.provideField(ownerFieldNumberInHolder);
                    if (currentOwner == null)
                    {
                        // No owner, so correct it
                        NucleusLogger.PERSISTENCE.info(Localiser.msg("056037", ownerSM.getObjectAsPrintable(), ownerMemberMetaData.getFullFieldName(), 
                            StringUtils.toJVMIDString(ownerHolderSM.getObject())));
                        ownerHolderSM.replaceFieldMakeDirty(ownerFieldNumberInHolder, newOwner);
                    }
                    else if (currentOwner != newOwner && ownerSM.getReferencedPC() == null)
                    {
                        // Owner of the element is neither this container nor is it being attached
                        // Inconsistent owner, so throw exception
                        throw new NucleusUserException(Localiser.msg("056038", ownerSM.getObjectAsPrintable(), ownerMemberMetaData.getFullFieldName(), 
                            StringUtils.toJVMIDString(ownerHolderSM.getObject()), StringUtils.toJVMIDString(currentOwner)));
                    }
                }
            }
            public void fetchNonLoadedFields(DNStateManager sm)
            {
            }
            public FetchPlan getFetchPlanForLoading()
            {
                return null;
            }
        });

        return inserted;
    }

    /**
     * Accessor for an iterator through the list elements.
     * @param ownerSM StateManager for the owner.
     * @param startIdx The start index in the list (only for indexed lists)
     * @param endIdx The end index in the list (only for indexed lists)
     * @return The List Iterator
     */
    @Override
    protected ListIterator<E> listIterator(DNStateManager ownerSM, int startIdx, int endIdx)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();

        if (elementInfo == null || elementInfo.length == 0)
        {
            return null;
        }

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
                        ResultObjectFactory<E> rof = null;
                        if (elementsAreEmbedded || elementsAreSerialised)
                        {
                            throw new NucleusException("Cannot have FK set with non-persistent objects");
                        }

                        rof = new PersistentClassROF<>(ec, rs, ec.getFetchPlan(), resultMapping, elementCmd, clr.classForName(elementType));
                        return new ListStoreIterator<E>(ownerSM, rs, rof, this);
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
            throw new NucleusDataStoreException(Localiser.msg("056006", stmt), e);
        }
    }

    /**
     * Generate statement for updating the owner, index columns in an inverse 1-N. 
     * Will result in the statement
     * <PRE>
     * UPDATE ELEMENTTABLE SET FK_COL_1 = ?, FK_COL_2 = ?, FK_IDX = ? [,DISTINGUISHER=?]
     * WHERE ELEMENT_ID = ?
     * </PRE>
     * when we have a single element table, and
     * <PRE>
     * UPDATE ? SET FK_COL_1=?, FK_COL_2=?, FK_IDX=? [,DISTINGUISHER=?]
     * WHERE ELEMENT_ID=?
     * </PRE>
     * when we have multiple element tables possible
     * @return Statement for updating the owner/index of an element in an inverse 1-N
     */
    private String getUpdateFkStmt(Object element)
    {
        if (elementMapping instanceof ReferenceMapping && elementMapping.getNumberOfColumnMappings() > 1)
        {
            // Don't cache since depends on the element
            return getUpdateFkStatementString(element);
        }

        if (updateFkStmt == null)
        {
            synchronized (this)
            {
                updateFkStmt = getUpdateFkStatementString(element);
            }
        }
        return updateFkStmt;
    }

    private String getUpdateFkStatementString(Object element)
    {
        JavaTypeMapping ownerMapping = this.ownerMapping;
        JavaTypeMapping elemMapping = this.elementMapping;
        JavaTypeMapping orderMapping = this.orderMapping;
        JavaTypeMapping relDiscrimMapping = this.relationDiscriminatorMapping;
        Table table = containerTable;
        if (elementInfo.length > 1)
        {
            ComponentInfo elemInfo = getComponentInfoForElement(element);
            if (elemInfo != null)
            {
                table = elemInfo.getDatastoreClass();
                ownerMapping = elemInfo.getOwnerMapping();
                elemMapping = elemInfo.getDatastoreClass().getIdMapping();
                orderMapping = elemInfo.getDatastoreClass().getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_INDEX);
                relDiscrimMapping = elemInfo.getDatastoreClass().getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_FK_DISCRIMINATOR);
            }
        }

        StringBuilder stmt = new StringBuilder("UPDATE ").append(table.toString()).append(" SET ");
        for (int i = 0; i < ownerMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(",");
            }
            stmt.append(ownerMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
            stmt.append("=");
            stmt.append(ownerMapping.getColumnMapping(i).getUpdateInputParameter());
        }
        if (orderMapping != null)
        {
            for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
            {
                stmt.append(",");
                stmt.append(orderMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                stmt.append("=");
                stmt.append(orderMapping.getColumnMapping(i).getUpdateInputParameter());
            }
        }
        if (relDiscrimMapping != null)
        {
            for (int i = 0; i < relDiscrimMapping.getNumberOfColumnMappings(); i++)
            {
                stmt.append(",");
                stmt.append(relDiscrimMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                stmt.append("=");
                stmt.append(relDiscrimMapping.getColumnMapping(i).getUpdateInputParameter());
            }
        }

        stmt.append(" WHERE ");
        BackingStoreHelper.appendWhereClauseForElement(stmt, elemMapping, element, elementsAreSerialised, null, true);

        return stmt.toString();
    }

    /**
     * Generates the statement for clearing items by nulling the owner link out. The statement will be
     * <PRE>
     * UPDATE LISTTABLE SET OWNERCOL=NULL, INDEXCOL=-1 [,DISTINGUISHER=NULL]
     * WHERE OWNERCOL=? [AND DISTINGUISHER=?]
     * </PRE>
     * when there is only one element table, and will be
     * <PRE>
     * UPDATE ? SET OWNERCOL=NULL, INDEXCOL=-1 [,DISTINGUISHER=NULL]
     * WHERE OWNERCOL=? [AND DISTINGUISHER=?]
     * </PRE>
     * when there is more than 1 element table.
     * @return The Statement for clearing items for the owner.
     */
    private String getClearNullifyStmt()
    {
        if (clearNullifyStmt == null)
        {
            synchronized (this)
            {
                // TODO If ownerMapping is not for containerTable then use owner table for the UPDATE
                StringBuilder stmt = new StringBuilder("UPDATE ");
//                if (elementInfo.length > 1)
//                {
//                    stmt.append("?");
//                }
//                else
//                {
                    // Could use elementInfo[0].getDatastoreClass but need to allow for relation in superclass table
                    stmt.append(containerTable.toString());
//                }
                stmt.append(" SET ");
                for (int i = 0; i < ownerMapping.getNumberOfColumnMappings(); i++)
                {
                    if (i > 0)
                    {
                        stmt.append(", ");
                    }
                    stmt.append(ownerMapping.getColumnMapping(i).getColumn().getIdentifier().toString() + "=NULL");
                }
                if (orderMapping != null)
                {
                    for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(", ");
                        stmt.append(orderMapping.getColumnMapping(i).getColumn().getIdentifier().toString() + "=-1");
                    }
                }
                if (relationDiscriminatorMapping != null)
                {
                    for (int i = 0; i < relationDiscriminatorMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(", ");
                        stmt.append(relationDiscriminatorMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                        stmt.append("=NULL");
                    }
                }

                stmt.append(" WHERE ");
                BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
                if (relationDiscriminatorMapping != null)
                {
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, null, false);
                }

                clearNullifyStmt = stmt.toString();
            }
        }
        return clearNullifyStmt;
    }

    /**
     * Generates the statement for setting an item to be at a position.
     * <PRE>
     * UPDATE LISTTABLE SET OWNERCOL=?, INDEXCOL = ? [,DISTINGUISHER=?]
     * WHERE ELEMENTCOL = ?
     * </PRE>
     * @param element The element to set
     * @return The Statement for setting an item
     */
    private String getSetStmt(Object element)
    {
        if (setStmt != null)
        {
            return setStmt;
        }

        String stmt = getSetStatementString(element);
        if (elementInfo.length == 1)
        {
            setStmt = stmt;
        }
        return stmt;
    }

    private String getSetStatementString(Object element)
    {
        ComponentInfo elemInfo = getComponentInfoForElement(element);
        Table table = this.containerTable;
        JavaTypeMapping ownerMapping = this.ownerMapping;
        JavaTypeMapping elemMapping = this.elementMapping;
        JavaTypeMapping relDiscrimMapping = this.relationDiscriminatorMapping;
        JavaTypeMapping orderMapping = this.orderMapping;
        if (elemInfo != null)
        {
            table = elemInfo.getDatastoreClass();
            elemMapping = elemInfo.getDatastoreClass().getIdMapping();
            ownerMapping = elemInfo.getOwnerMapping();
            orderMapping = elemInfo.getDatastoreClass().getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_INDEX);
            relDiscrimMapping = elemInfo.getDatastoreClass().getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_FK_DISCRIMINATOR);
        }

        // TODO If ownerMapping is not for containerTable then use owner table for the UPDATE
        StringBuilder stmt = new StringBuilder("UPDATE ").append(table.toString()).append(" SET ");
        for (int i = 0; i < ownerMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(",");
            }
            stmt.append(ownerMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
            stmt.append(" = ");
            stmt.append(ownerMapping.getColumnMapping(i).getUpdateInputParameter());
        }

        if (orderMapping != null)
        {
            for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
            {
                stmt.append(",");
                stmt.append(orderMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                stmt.append(" = ");
                stmt.append(orderMapping.getColumnMapping(i).getUpdateInputParameter());
            }
        }
        if (relDiscrimMapping != null)
        {
            for (int i = 0; i < relDiscrimMapping.getNumberOfColumnMappings(); i++)
            {
                stmt.append(",");
                stmt.append(relDiscrimMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                stmt.append(" = ");
                stmt.append(relDiscrimMapping.getColumnMapping(i).getUpdateInputParameter());
            }
        }

        stmt.append(" WHERE ");
        BackingStoreHelper.appendWhereClauseForElement(stmt, elemMapping, element, isElementsAreSerialised(), null, true);

        return stmt.toString();
    }

    /**
     * Generates the statement for unsetting an item from a list position.
     * <PRE>
     * UPDATE LISTTABLE SET OWNERCOL=NULL, INDEXCOL=-1 [, DISTINGUISHER = NULL]
     * WHERE OWNERCOL = ? AND INDEXCOL = ? [AND DISTINGUISHER = ?]
     * </PRE>
     * @return The Statement for unsetting an item
     */
    private String getUnsetStmt()
    {
        if (unsetStmt == null)
        {
            synchronized (this)
            {
                // TODO If ownerMapping is not for containerTable then use owner table for the UPDATE
                StringBuilder stmt = new StringBuilder("UPDATE ");
                // TODO Allow for multiple element tables
                stmt.append(containerTable.toString());
                stmt.append(" SET ");
                for (int i = 0; i < ownerMapping.getNumberOfColumnMappings(); i++)
                {
                    if (i > 0)
                    {
                        stmt.append(",");
                    }
                    stmt.append(ownerMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                    stmt.append("=NULL");
                }

                if (orderMapping != null)
                {
                    for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(",");
                        stmt.append(orderMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                        stmt.append("=-1");
                    }
                }
                if (relationDiscriminatorMapping != null)
                {
                    for (int i = 0; i < relationDiscriminatorMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(",");
                        stmt.append(relationDiscriminatorMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                        stmt.append(" = NULL");
                    }
                }

                stmt.append(" WHERE ");
                BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
                BackingStoreHelper.appendWhereClauseForMapping(stmt, orderMapping, null, false);
                if (relationDiscriminatorMapping != null)
                {
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, null, false);
                }

                unsetStmt = stmt.toString();
            }
        }
        return unsetStmt;
    }

    /**
     * Generates the statement for removing an item by nulling it out (setting owner to null, and index to -1).
     * When there is only a single element table the statement will be
     * <PRE>
     * UPDATE LISTTABLE SET OWNERCOL=NULL, INDEXCOL=-1
     * WHERE OWNERCOL = ?
     * AND INDEXCOL = ?
     * [AND DISTINGUISHER = ?]
     * </PRE>
     * @return The Statement for removing an item from a position
     */
    private String getRemoveAtNullifyStmt()
    {
        if (removeAtNullifyStmt == null)
        {
            synchronized (this)
            {
                // TODO If ownerMapping is not for containerTable then use owner table for the UPDATE. Allow for multiple elementInfo
                // Could use elementInfo[0].getDatastoreClass but need to allow for relation in superclass table
                StringBuilder stmt = new StringBuilder("UPDATE ").append(containerTable.toString()).append(" SET ");
                for (int i = 0; i < ownerMapping.getNumberOfColumnMappings(); i++)
                {
                    if (i > 0)
                    {
                        stmt.append(", ");
                    }
                    stmt.append(ownerMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                    stmt.append("=NULL");
                }
                if (orderMapping != null)
                {
                    for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(", ");
                        stmt.append(orderMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                        stmt.append("=-1");
                    }
                }

                stmt.append(" WHERE ");
                BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
                BackingStoreHelper.appendWhereClauseForMapping(stmt, orderMapping, null, false);
                if (relationDiscriminatorMapping != null)
                {
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, null, false);
                }

                removeAtNullifyStmt = stmt.toString();
            }
        }
        return removeAtNullifyStmt;
    }

    /**
     * Generates the statement for removing an item by nulling it out (setting owner to null, and index to -1).
     * When there is only a single element table the statement will be
     * <PRE>
     * UPDATE LISTTABLE SET OWNERCOL=NULL, INDEXCOL=-1
     * WHERE (OWNERCOL=? AND INDEXCOL=? [AND DISTINGUISHER=?]) OR (OWNERCOL=? AND INDEXCOL=? [AND DISTINGUISHER=?]) OR ...
     * </PRE>
     * @param indices The indexes to nullify at (only uses the length of the array)
     * @return The Statement for removing an item from a position
     */
    private String getRemoveAtNullifyBulkStmt(int[] indices)
    {
        // TODO If ownerMapping is not for containerTable then use owner table for the UPDATE. Allow for multiple elementInfo
        // Could use elementInfo[0].getDatastoreClass but need to allow for relation in superclass table
        StringBuilder stmt = new StringBuilder("UPDATE ").append(containerTable.toString()).append(" SET ");
        for (int i = 0; i < ownerMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(", ");
            }
            stmt.append(ownerMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
            stmt.append("=NULL");
        }
        if (orderMapping != null)
        {
            for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
            {
                stmt.append(", ");
                stmt.append(orderMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                stmt.append("=-1");
            }
        }

        stmt.append(" WHERE ");

        for (int i=0;i<indices.length;i++)
        {
            stmt.append(i == 0 ? "(" : " OR (");

            BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
            BackingStoreHelper.appendWhereClauseForMapping(stmt, orderMapping, null, false);
            if (relationDiscriminatorMapping != null)
            {
                BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, null, false);
            }

            stmt.append(")");
        }
        return stmt.toString();
    }

    /**
     * Method to return the SQLStatement and mapping for an iterator for this backing store.
     * Create a statement of the form
     * <pre>
     * SELECT ELEM_COLS
     * FROM ELEM_TBL
     * [WHERE]
     *   [ELEM_TBL.OWNER_ID = {value}] [AND]
     *   [ELEM_TBL.DISCRIM = {discrimValue}]
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

        if (elementInfo.length == 1 &&
            elementInfo[0].getDatastoreClass().getDiscriminatorMetaData() != null &&
            elementInfo[0].getDatastoreClass().getDiscriminatorMetaData().getStrategy() != DiscriminatorStrategy.NONE)
        {
            String elementType = ownerMemberMetaData.getCollection().getElementType();
            if (ClassUtils.isReferenceType(clr.classForName(elementType)))
            {
                String[] clsNames = storeMgr.getNucleusContext().getMetaDataManager().getClassesImplementingInterface(elementType, clr);
                Class[] cls = new Class[clsNames.length];
                for (int i=0; i<clsNames.length; i++)
                {
                    cls[i] = clr.classForName(clsNames[i]);
                }
                sqlStmt = new DiscriminatorStatementGenerator(storeMgr, clr, cls, true, null, null).getStatement(ec);
            }
            else
            {
                sqlStmt = new DiscriminatorStatementGenerator(storeMgr, clr, clr.classForName(elementInfo[0].getClassName()), true, null, null).getStatement(ec);
            }
            iterateUsingDiscriminator = true;

            // Select the required fields
            SQLStatementHelper.selectFetchPlanOfSourceClassInStatement(sqlStmt, elementClsMapping, fp, sqlStmt.getPrimaryTable(), elementCmd, fp.getMaxFetchDepth());
        }
        else
        {
            for (int i=0;i<elementInfo.length;i++)
            {
                final Class elementCls = clr.classForName(this.elementInfo[i].getClassName());
                UnionStatementGenerator stmtGen = new UnionStatementGenerator(storeMgr, clr, elementCls, true, null, null);
                stmtGen.setOption(SelectStatementGenerator.OPTION_SELECT_DN_TYPE);
                elementClsMapping.setNucleusTypeColumnName(UnionStatementGenerator.DN_TYPE_COLUMN);
                SelectStatement subStmt = stmtGen.getStatement(ec);

                // Select the required fields (of the element class)
                if (sqlStmt == null)
                {
                    if (elementInfo.length > 1)
                    {
                        SQLStatementHelper.selectIdentityOfCandidateInStatement(subStmt, elementClsMapping, elementInfo[i].getAbstractClassMetaData());
                    }
                    else
                    {
                        SQLStatementHelper.selectFetchPlanOfSourceClassInStatement(subStmt, elementClsMapping, fp, subStmt.getPrimaryTable(), elementInfo[i].getAbstractClassMetaData(),
                            fp.getMaxFetchDepth());
                    }
                }
                else
                {
                    if (elementInfo.length > 1)
                    {
                        SQLStatementHelper.selectIdentityOfCandidateInStatement(subStmt, null, elementInfo[i].getAbstractClassMetaData());
                    }
                    else
                    {
                        SQLStatementHelper.selectFetchPlanOfSourceClassInStatement(subStmt, null, fp, subStmt.getPrimaryTable(), elementInfo[i].getAbstractClassMetaData(),
                            fp.getMaxFetchDepth());
                    }
                }

                if (sqlStmt == null)
                {
                    sqlStmt = subStmt;
                }
                else
                {
                    sqlStmt.union(subStmt);
                }
            }
            if (sqlStmt == null)
            {
                throw new NucleusException("Unable to generate iterator statement for field=" + getOwnerMemberMetaData().getFullFieldName());
            }
        }

        if (addRestrictionOnOwner)
        {
            // Apply condition to filter by owner
            // TODO If ownerMapping is not for containerTable then do JOIN to ownerTable in the FROM clause (or find if already done)
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
                // Order by the ordering column
                SQLTable orderSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), orderMapping);
                SQLExpression[] orderExprs = new SQLExpression[orderMapping.getNumberOfColumnMappings()];
                boolean descendingOrder[] = new boolean[orderMapping.getNumberOfColumnMappings()];
                orderExprs[0] = exprFactory.newExpression(sqlStmt, orderSqlTbl, orderMapping);
                sqlStmt.setOrdering(orderExprs, descendingOrder);
            }
        }
        else
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

        return new ElementIteratorStatement(this, sqlStmt, elementClsMapping);
    }
}