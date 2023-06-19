/**********************************************************************
Copyright (c) 2005 Andy Jefferson and others. All rights reserved.
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

import java.lang.reflect.Modifier;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.CollectionMetaData;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.store.schema.table.SurrogateColumnType;
import org.datanucleus.store.rdbms.JDBCUtils;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.types.SCOUtils;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * Representation of the store of an element-based container.
 * This is used to represent either a collection or an array.
 * There are the following types of situation that we try to cater for with respect to elements.
 * <UL>
 * <LI><B>element-type</B> is PC with "new-table" or "superclass-table" inheritance. In this case we will have <I>elementInfo</I> with 1 entry.</LI>
 * <LI><B>element-type</B> is PC with "subclass-table" inheritance. In this case we will have <I>elementInfo</I>
 * with "n" entries (1 for each subclass type with its own table). We also have <I>emd</I> being the MetaData for the element-type.</LI>
 * <LI><B>element-type</B> is Reference type. In this case we will have <I>elementInfo</I> with "n" entries (1 for each implementation type).</LI>
 * <LI><B>element-type</B> is non-PC. In this case we have no <I>elementInfo</I> and no <I>emd</I></LI>
 * </UL>
 */
public abstract class ElementContainerStore extends BaseContainerStore
{
    /** Flag to set whether the iterator statement will use a discriminator or not. */
    protected boolean iterateUsingDiscriminator = false;

    /** Statement for getting the size of the container. */
    protected String sizeStmt;

    /** Statement for clearing the container. */
    protected String clearStmt;

    /** Statement for adding an element to the container. */
    protected String addStmt;

    /** Statement for removing an element from the container. */
    protected String removeStmt;

    /**
     * Information for the elements of this container.
     * When the "element-type" table is new-table, or superclass-table then there is 1 value here.
     * When the "element-type" table uses subclass-table, or when it is a reference type then there can be multiple.
     * When the element is embedded/serialised (into join table) this will be null.
     */
    protected ComponentInfo[] elementInfo;

    /** MetaData for the "element-type" class. Not used for reference types since no metadata is present for the declared type. */
    protected AbstractClassMetaData elementCmd;

    /** Table containing the link between owner and element. */
    protected Table containerTable;

    /** Mapping for the element. */
    protected JavaTypeMapping elementMapping;

    /** Type of the element. */
    protected String elementType;

    /** Whether the elements are embedded. */
    protected boolean elementsAreEmbedded;

    /** Whether the elements are serialised. */
    protected boolean elementsAreSerialised;

    /** Whether the element is of a persistent-interface (defined using "{interface}") type. */
    protected boolean elementIsPersistentInterface = false;

    /**
     * Mapping for an ordering column to allow for duplicates in the container.
     * Can also be used for ordering elements in a List/array.
     * Can also be used where we have an embedded object and so need to form the PK with something.
     */
    protected JavaTypeMapping orderMapping;

    /** Optional mapping to distinguish elements of one collection from another when sharing the join table. */
    protected JavaTypeMapping relationDiscriminatorMapping;

    /** Value to use to discriminate between elements of this collection from others using the same join table. */
    protected String relationDiscriminatorValue;

    /**
     * Constructor.
     * @param storeMgr Manager for the store
     * @param clr ClassLoader resolver
     */
    protected ElementContainerStore(RDBMSStoreManager storeMgr, ClassLoaderResolver clr)
    {
        super(storeMgr, clr);
    }

    public JavaTypeMapping getElementMapping()
    {
        return elementMapping;
    }

    public JavaTypeMapping getRelationDiscriminatorMapping()
    {
        return relationDiscriminatorMapping;
    }

    public String getRelationDiscriminatorValue()
    {
        return relationDiscriminatorValue;
    }

    public Table getContainerTable()
    {
        return containerTable;
    }

    public AbstractClassMetaData getElementClassMetaData()
    {
        return elementCmd;
    }

    public boolean isElementsAreSerialised()
    {
        return elementsAreSerialised;
    }

    public boolean isElementsAreEmbedded()
    {
        return elementsAreEmbedded;
    }

    /**
     * Accessor for whether the store has an order mapping, to allow for duplicates or ordering.
     * @return Whether an order mapping is present.
     */
    public boolean hasOrderMapping()
    {
        return (orderMapping != null);
    }

    /**
     * Method to validate an element against the accepted type.
     * @param clr The ClassLoaderResolver
     * @param element The element to validate
     * @return Whether it is valid.
     */ 
    protected boolean validateElementType(ClassLoaderResolver clr, Object element)
    {
        if (element == null)
        {
            return true;
        }

        Class primitiveElementClass = ClassUtils.getPrimitiveTypeForType(element.getClass());
        if (primitiveElementClass != null)
        {            
            // Allow for the element type being primitive, and the user wanting to store its wrapper
            String elementTypeWrapper = elementType;
            Class elementTypeClass = clr.classForName(elementType);
            if (elementTypeClass.isPrimitive())
            {
                elementTypeWrapper = ClassUtils.getWrapperTypeForPrimitiveType(elementTypeClass).getName();
            }
            return clr.isAssignableFrom(elementTypeWrapper, element.getClass());
        }

        String elementType = null;
        if (ownerMemberMetaData.hasCollection()) 
        {
            elementType = ownerMemberMetaData.getCollection().getElementType();
        }
        else if (ownerMemberMetaData.hasArray())
        {
            elementType = ownerMemberMetaData.getArray().getElementType();
        }
        else
        {
            elementType = this.elementType;
        }

        Class elementCls = clr.classForName(elementType);
        if (!storeMgr.getNucleusContext().getMetaDataManager().isPersistentInterface(elementType) && elementCls.isInterface())
        {
            // Collection of interface types, so check against the available implementations TODO Check against allowed implementation in metadata
            String[] clsNames = storeMgr.getNucleusContext().getMetaDataManager().getClassesImplementingInterface(elementType, clr);
            if (clsNames != null && clsNames.length > 0)
            {
                for (int i=0;i<clsNames.length;i++)
                {
                    if (clsNames[i].equals(element.getClass().getName()))
                    {
                        return true;
                    }
                }
                return false;
            }
        }
        return clr.isAssignableFrom(elementType, element.getClass());
    }

    /**
     * Method to check if an element is already persistent or is persistent but managed by a different ExecutionContext.
     * @param sm StateManager of this owner
     * @param element The element
     * @return Whether it is valid for reading.
     */
    protected boolean validateElementForReading(DNStateManager sm, Object element)
    {
        if (!validateElementType(sm.getExecutionContext().getClassLoaderResolver(), element))
        {
            return false;
        }

        if (element != null && !elementsAreEmbedded && !elementsAreSerialised)
        {
            ExecutionContext ec = sm.getExecutionContext();
            if ((!ec.getApiAdapter().isPersistent(element) || ec != ec.getApiAdapter().getExecutionContext(element)) && !ec.getApiAdapter().isDetached(element))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Method to check if an element is already persistent, or is managed by a different ExecutionContext. If not persistent, this will persist it.
     * @param ec execution context
     * @param element The element
     * @param fieldValues any initial field values to use if persisting the element
     * @return Whether the element was persisted during this call
     */
    protected boolean validateElementForWriting(ExecutionContext ec, Object element, FieldValues fieldValues)
    {
        // TODO Pass in cascade flag and if element not present then throw exception
        // Check the element type for this collection
        if (!elementIsPersistentInterface && !validateElementType(ec.getClassLoaderResolver(), element))
        {
            throw new ClassCastException(Localiser.msg("056033", element.getClass().getName(), ownerMemberMetaData.getFullFieldName(), elementType));
        }

        boolean persisted = false;
        if (elementsAreEmbedded || elementsAreSerialised)
        {
            // Element is embedded/serialised so has no id
        }
        else
        {
            DNStateManager elementSM = ec.findStateManager(element);
            if (elementSM != null && elementSM.isEmbedded())
            {
                // Element is already with StateManager and is embedded in another field!
                throw new NucleusUserException(Localiser.msg("056028", ownerMemberMetaData.getFullFieldName(), element));
            }

            persisted = SCOUtils.validateObjectForWriting(ec, element, fieldValues);
        }
        return persisted;
    }

    /**
     * Accessor for an iterator through the container elements.
     * @param ownerSM StateManager for the container.
     * @return The Iterator
     */
    public abstract Iterator iterator(DNStateManager ownerSM);

    /**
     * Clear the association from owner to all elements.
     * Provides cascade-delete when the elements being deleted are PC types.
     * @param ownerSM StateManager for the container. 
     */
    public void clear(DNStateManager ownerSM)
    {
        Collection<Object> dependentElements = null;
        CollectionMetaData collmd = ownerMemberMetaData.getCollection();
        boolean dependent = collmd.isDependentElement();
        if (ownerMemberMetaData.isCascadeRemoveOrphans())
        {
            dependent = true;
        }
        if (dependent && !collmd.isEmbeddedElement() && !collmd.isSerializedElement())
        {
            // Retain the dependent elements that need deleting after clearing
            dependentElements = new HashSet<>();
            Iterator iter = iterator(ownerSM);
            while (iter.hasNext())
            {
                dependentElements.add(iter.next());
            }
        }

        boolean ownerSoftDelete = ownerSM.getClassMetaData().isSoftDelete();
        boolean elementSoftDelete = (elementInfo != null ? elementInfo[0].cmd.isSoftDelete() : false);

        if (ownerSoftDelete && elementSoftDelete)
        {
            // Owner and elements are being soft deleted, so no need to touch join table entries
        }
        else if (!dependent && ownerSoftDelete)
        {
            // Not deleting the elements, but owner is being soft deleted, so leave join table entries
        }
        else
        {
            String clearStmt = getClearStmt();
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
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                        if (relationDiscriminatorMapping != null)
                        {
                            BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                        }

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

        // Cascade-delete
        if (dependentElements != null && dependentElements.size() > 0)
        {
            Iterator iter = dependentElements.iterator();
            while (iter.hasNext())
            {
                Object obj = iter.next();
                if (ownerSM.getExecutionContext().getApiAdapter().isDeleted(obj))
                {
                    // Element is tagged for deletion so will be deleted at flush(), and we dont need it immediately
                }
                else
                {
                    ownerSM.getExecutionContext().deleteObjectInternal(obj);
                }
            }
        }
    }

    /**
     * Generate statement for clearing the (join table) container.
     * <PRE>
     * DELETE FROM CONTAINERTABLE WHERE OWNERCOL = ? [AND RELATION_DISCRIM=?]
     * </PRE>
     * TODO Add a discriminator restriction on this statement so we only clear ones with a valid discriminator value
     * @return Statement for clearing the container.
     */
    protected String getClearStmt()
    {
        if (clearStmt == null)
        {
            synchronized (this)
            {
                StringBuilder stmt = new StringBuilder("DELETE FROM ").append(containerTable.toString()).append(" WHERE ");
                BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, null, true);
                if (getRelationDiscriminatorMapping() != null)
                {
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, null, false);
                }

                clearStmt = stmt.toString();
            }
        }

        return clearStmt;
    }

    /**
     * Method to remove any stored statement for addition of an element.
     */
    protected void invalidateAddStmt()
    {
        addStmt = null;
    }

    /**
     * Generates the statement for adding items to a (join table) container.
     * The EMBEDDEDFIELDX columns are only added for embedded PC elements.
     * <PRE>
     * INSERT INTO COLLTABLE (OWNERCOL,[ELEMENTCOL],[EMBEDDEDFIELD1, EMBEDDEDFIELD2,...],[ORDERCOL]) VALUES (?,?,?)
     * </PRE>
     * @return The Statement for adding an item
     */
    protected String getAddStmtForJoinTable()
    {
        if (addStmt == null)
        {
            synchronized (this)
            {
                StringBuilder stmt = new StringBuilder("INSERT INTO ");
                stmt.append(containerTable.toString());
                stmt.append(" (");
                for (int i = 0; i < getOwnerMapping().getNumberOfColumnMappings(); i++)
                {
                    if (i > 0)
                    {
                        stmt.append(",");
                    }
                    stmt.append(getOwnerMapping().getColumnMapping(i).getColumn().getIdentifier().toString());
                }

                for (int i = 0; i < elementMapping.getNumberOfColumnMappings(); i++)
                {
                    stmt.append(",").append(elementMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                }

                if (orderMapping != null)
                {
                    for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(",").append(orderMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                    }
                }
                if (relationDiscriminatorMapping != null)
                {
                    for (int i = 0; i < relationDiscriminatorMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(",").append(relationDiscriminatorMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                    }
                }

                stmt.append(") VALUES (");
                for (int i = 0; i < getOwnerMapping().getNumberOfColumnMappings(); i++)
                {
                    if (i > 0)
                    {
                        stmt.append(",");
                    }
                    stmt.append(getOwnerMapping().getColumnMapping(i).getInsertionInputParameter());
                }

                for (int i = 0; i < elementMapping.getNumberOfColumnMappings(); i++)
                {
                    stmt.append(",").append(elementMapping.getColumnMapping(0).getInsertionInputParameter());
                }

                if (orderMapping != null)
                {
                    for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(",").append(orderMapping.getColumnMapping(0).getInsertionInputParameter());
                    }
                }
                if (relationDiscriminatorMapping != null)
                {
                    for (int i = 0; i < relationDiscriminatorMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(",").append(relationDiscriminatorMapping.getColumnMapping(0).getInsertionInputParameter());
                    }
                }

                stmt.append(") ");

                addStmt = stmt.toString();
            }
        }

        return addStmt;
    }

    /**
     * Method to return the size of the container.
     * @param sm StateManager.
     * @return The size.
     */
    public int size(DNStateManager sm)
    {
        return getSize(sm);
    }

    public int getSize(DNStateManager ownerSM)
    {
        int numRows;

        String sizeStmt = getSizeStmt();
        try
        {
            ExecutionContext ec = ownerSM.getExecutionContext();
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForQuery(mconn, sizeStmt);
                try
                {
                    int jdbcPosition = 1;
                    if (elementInfo == null)
                    {
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                    }
                    else
                    {
                        if (usingJoinTable())
                        {
                            jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                            if (elementInfo[0].getDiscriminatorMapping() != null)
                            {
                                jdbcPosition = BackingStoreHelper.populateElementDiscriminatorInStatement(ec, ps, jdbcPosition, true, elementInfo[0], clr);
                            }
                            if (relationDiscriminatorMapping != null)
                            {
                                jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                            }
                        }
                        else
                        {
                            for (int i=0;i<elementInfo.length;i++)
                            {
                                jdbcPosition = BackingStoreHelper.populateOwnerInStatement(ownerSM, ec, ps, jdbcPosition, this);
                                if (elementInfo[i].getDiscriminatorMapping() != null)
                                {
                                    jdbcPosition = BackingStoreHelper.populateElementDiscriminatorInStatement(ec, ps, jdbcPosition, true, elementInfo[i], clr);
                                }
                                if (relationDiscriminatorMapping != null)
                                {
                                    jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                                }
                            }
                        }
                    }

                    ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, sizeStmt, ps);
                    try
                    {
                        if (!rs.next())
                        {
                            throw new NucleusDataStoreException(Localiser.msg("056007", sizeStmt));
                        }

                        numRows = rs.getInt(1);

                        if (elementInfo != null && elementInfo.length > 1)
                        {
                            while (rs.next())
                            {
                                numRows = numRows + rs.getInt(1);
                            }
                        }

                        JDBCUtils.logWarnings(rs);
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                catch (SQLException sqle)
                {
                    NucleusLogger.GENERAL.error("Exception in size", sqle);
                    throw sqle;
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
            throw new NucleusDataStoreException(Localiser.msg("056007", sizeStmt), e);
        }

        return numRows;
    }

    /**
     * Generate statement for getting the size of the container.
     * The order part is only present when an order mapping is used.
     * The discriminator part is only present when the element has a discriminator.
     * <PRE>
     * SELECT COUNT(*) FROM TBL THIS
     * [INNER JOIN ELEM_TBL ELEM ON TBL.COL = ELEM.ID] - when no null
     * [LEFT OUTER JOIN ELEM_TBL ELEM ON TBL.COL = ELEM.ID] - when allows null
     * WHERE THIS.OWNERCOL=?
     * [AND THIS.ORDERCOL IS NOT NULL]
     * [AND (DISCRIMINATOR=? OR DISCRMINATOR=? OR DISCRIMINATOR=? [OR DISCRIMINATOR IS NULL])]
     * [AND RELATION_DISCRIM=?]
     * </PRE>
     * The discriminator part includes all subclasses of the element type.
     * If the element is in a different table to the container then an INNER JOIN will be present to
     * link the two tables, and table aliases will be present also.
     * @return The Statement returning the size of the container.
     */
    protected String getSizeStmt()
    {
        if (sizeStmt != null)
        {
            // Statement exists and didn't need any discriminator when setting up the statement so just reuse it
            return sizeStmt;
        }

        synchronized (this)
        {
            boolean usingDiscriminatorInSizeStmt = false;
            String containerAlias = "THIS";
            StringBuilder stmt = new StringBuilder();
            if (elementInfo == null)
            {
                // Serialised/embedded elements in a join table
                stmt.append("SELECT COUNT(*) FROM ").append(containerTable.toString()).append(" ").append(containerAlias);
                stmt.append(" WHERE ");
                BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, containerAlias, true);
                if (orderMapping != null)
                {
                    // If an ordering is present, restrict to items where the index is not null to
                    // eliminate records that are added but may not be positioned yet.
                    for (int i = 0; i < orderMapping.getNumberOfColumnMappings(); i++)
                    {
                        stmt.append(" AND ");
                        stmt.append(containerAlias).append(".").append(orderMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
                        stmt.append(">=0");
                    }
                }

                JavaTypeMapping softDeleteMapping = containerTable.getSurrogateMapping(SurrogateColumnType.SOFTDELETE, false);
                if (softDeleteMapping != null)
                {
                    stmt.append(" AND ").append(containerAlias).append(".");
                    stmt.append(softDeleteMapping.getColumnMapping(0).getColumn().getIdentifier().toString());
                    stmt.append("=FALSE"); // TODO Cater for columns that store the DELETED flag as ("Y","N") or (1,0)
                }

                if (relationDiscriminatorMapping != null)
                {
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, containerAlias, false);
                }

                sizeStmt = stmt.toString();
                return sizeStmt;
            }

            if (usingJoinTable())
            {
                // Join table collection/array, so do COUNT of join table
                String joinedElementAlias = "ELEM";
                ComponentInfo elemInfo = elementInfo[0];

                stmt.append("SELECT COUNT(*) FROM ").append(containerTable.toString()).append(" ").append(containerAlias);

                // Add join to element table if required (only allows for 1 element table currently)
                boolean joinedDiscrim = false;
                if (elemInfo.getDiscriminatorMapping() != null)
                {
                    // Need join to the element table to restrict the discriminator
                    joinedDiscrim = true;
                    JavaTypeMapping elemIdMapping = elemInfo.getDatastoreClass().getIdMapping();
                    stmt.append(allowNulls ? " LEFT OUTER JOIN " : " INNER JOIN ");
                    stmt.append(elemInfo.getDatastoreClass().toString()).append(" ").append(joinedElementAlias).append(" ON ");
                    for (int j = 0; j < elementMapping.getNumberOfColumnMappings(); j++)
                    {
                        if (j > 0)
                        {
                            stmt.append(" AND ");
                        }
                        stmt.append(containerAlias).append(".").append(elementMapping.getColumnMapping(j).getColumn().getIdentifier());
                        stmt.append("=");
                        stmt.append(joinedElementAlias).append(".").append(elemIdMapping.getColumnMapping(j).getColumn().getIdentifier());
                    }
                }
                // TODO Add join to owner if ownerMapping is for supertable

                stmt.append(" WHERE ");
                BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, containerAlias, true);
                if (orderMapping != null)
                {
                    // If an ordering is present, restrict to items where the index is not null to
                    // eliminate records that are added but may not be positioned yet.
                    for (int j = 0; j < orderMapping.getNumberOfColumnMappings(); j++)
                    {
                        stmt.append(" AND ");
                        stmt.append(containerAlias).append(".").append(orderMapping.getColumnMapping(j).getColumn().getIdentifier().toString());
                        stmt.append(">=0");
                    }
                }

                JavaTypeMapping softDeleteMapping = containerTable.getSurrogateMapping(SurrogateColumnType.SOFTDELETE, false);
                if (softDeleteMapping != null)
                {
                    stmt.append(" AND ").append(containerAlias).append(".");
                    stmt.append(softDeleteMapping.getColumnMapping(0).getColumn().getIdentifier().toString());
                    stmt.append("=FALSE");
                }

                // Add a discriminator filter for collections with an element discriminator
                StringBuilder discrStmt = new StringBuilder();
                if (elemInfo.getDiscriminatorMapping() != null)
                {
                    usingDiscriminatorInSizeStmt = true;
                    JavaTypeMapping discrimMapping = elemInfo.getDiscriminatorMapping();

                    Collection<String> classNames = storeMgr.getSubClassesForClass(elemInfo.getClassName(), true, clr);
                    classNames.add(elemInfo.getClassName());
                    for (String className : classNames)
                    {
                        Class cls = clr.classForName(className);
                        if (!Modifier.isAbstract(cls.getModifiers()))
                        {
                            for (int j = 0; j < discrimMapping.getNumberOfColumnMappings(); j++)
                            {
                                if (discrStmt.length() > 0)
                                {
                                    discrStmt.append(" OR ");
                                }

                                discrStmt.append(joinedDiscrim ? joinedElementAlias : containerAlias);
                                discrStmt.append(".");
                                discrStmt.append(discrimMapping.getColumnMapping(j).getColumn().getIdentifier().toString());
                                discrStmt.append("=");
                                discrStmt.append(discrimMapping.getColumnMapping(j).getUpdateInputParameter());
                            }
                        }
                    }
                }

                if (discrStmt.length() > 0)
                {
                    stmt.append(" AND (");
                    stmt.append(discrStmt);
                    if (allowNulls)
                    {
                        stmt.append(" OR ");
                        stmt.append(elemInfo.getDiscriminatorMapping().getColumnMapping(0).getColumn().getIdentifier().toString());
                        stmt.append(" IS NULL");
                    }
                    stmt.append(")");
                }
                if (relationDiscriminatorMapping != null)
                {
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, containerAlias, false);
                }
            }
            else
            {
                // ForeignKey collection/array, so UNION all of the element COUNTs
                for (int i=0;i<elementInfo.length;i++)
                {
                    if (i > 0)
                    {
                        stmt.append(" UNION ");
                    }
                    ComponentInfo elemInfo = elementInfo[i];

                    stmt.append("SELECT COUNT(*),").append("'" + elemInfo.getAbstractClassMetaData().getName() + "'");
                    stmt.append(" FROM ").append(elemInfo.getDatastoreClass().toString()).append(" ").append(containerAlias);

                    stmt.append(" WHERE ");
                    BackingStoreHelper.appendWhereClauseForMapping(stmt, ownerMapping, containerAlias, true);
                    if (orderMapping != null)
                    {
                        // If an ordering is present, restrict to items where the index is not null to
                        // eliminate records that are added but may not be positioned yet.
                        for (int j = 0; j < orderMapping.getNumberOfColumnMappings(); j++)
                        {
                            stmt.append(" AND ");
                            stmt.append(containerAlias).append(".").append(orderMapping.getColumnMapping(j).getColumn().getIdentifier().toString());
                            stmt.append(">=0");
                        }
                    }

                    JavaTypeMapping softDeleteMapping = containerTable.getSurrogateMapping(SurrogateColumnType.SOFTDELETE, false);
                    if (softDeleteMapping != null)
                    {
                        stmt.append(" AND ").append(containerAlias).append(".");
                        stmt.append(softDeleteMapping.getColumnMapping(0).getColumn().getIdentifier().toString());
                        stmt.append("=FALSE");
                    }

                    // Add a discriminator filter for collections with an element discriminator
                    StringBuilder discrStmt = new StringBuilder();
                    if (elemInfo.getDiscriminatorMapping() != null)
                    {
                        usingDiscriminatorInSizeStmt = true;
                        JavaTypeMapping discrimMapping = elemInfo.getDiscriminatorMapping();

                        Collection<String> classNames = storeMgr.getSubClassesForClass(elemInfo.getClassName(), true, clr);
                        classNames.add(elemInfo.getClassName());
                        for (String className : classNames)
                        {
                            Class cls = clr.classForName(className);
                            if (!Modifier.isAbstract(cls.getModifiers()))
                            {
                                for (int j = 0; j < discrimMapping.getNumberOfColumnMappings(); j++)
                                {
                                    if (discrStmt.length() > 0)
                                    {
                                        discrStmt.append(" OR ");
                                    }

                                    discrStmt.append(containerAlias).append(".").append(discrimMapping.getColumnMapping(j).getColumn().getIdentifier().toString());
                                    discrStmt.append("=");
                                    discrStmt.append(discrimMapping.getColumnMapping(j).getUpdateInputParameter());
                                }
                            }
                        }
                    }

                    if (discrStmt.length() > 0)
                    {
                        stmt.append(" AND (");
                        stmt.append(discrStmt);
                        if (allowNulls)
                        {
                            stmt.append(" OR ");
                            stmt.append(elemInfo.getDiscriminatorMapping().getColumnMapping(0).getColumn().getIdentifier().toString());
                            stmt.append(" IS NULL");
                        }
                        stmt.append(")");
                    }
                    if (relationDiscriminatorMapping != null)
                    {
                        BackingStoreHelper.appendWhereClauseForMapping(stmt, relationDiscriminatorMapping, containerAlias, false);
                    }
                }
            }

            if (!usingDiscriminatorInSizeStmt)
            {
                sizeStmt = stmt.toString();
            }
            return stmt.toString();
        }
    }

    protected ComponentInfo getComponentInfoForElement(Object element)
    {
        if (elementInfo == null)
        {
            return null;
        }
        ComponentInfo elemInfo = null;

        for (int i=0;i<elementInfo.length;i++)
        {
            if (elementInfo[i].getClassName().equals(element.getClass().getName()))
            {
                elemInfo = elementInfo[i];
                break;
            }
        }
        if (elemInfo == null)
        {
            Class<?> elementCls = element.getClass();
            for (int i=0;i<elementInfo.length;i++)
            {
                Class<?> elemInfoCls = clr.classForName(elementInfo[i].getClassName());
                if (elemInfoCls.isAssignableFrom(elementCls))
                {
                    elemInfo = elementInfo[i];
                    break;
                }
            }
        }
        return elemInfo;
    }

    protected boolean usingJoinTable()
    {
        // elementInfo == null means embedded/serialised into join table
        // elementInfo[0].datastoreClass will be element table when using join table (as container table)
        return (elementInfo == null || (elementInfo[0].getDatastoreClass() != containerTable));
    }
}