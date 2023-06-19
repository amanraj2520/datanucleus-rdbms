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

import java.lang.reflect.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NotYetFlushedException;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.types.scostore.ArrayStore;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * Abstract representation of the backing store for an array.
 * @param <E> Type of element in this array
 */
public abstract class AbstractArrayStore<E> extends ElementContainerStore implements ArrayStore<E>
{
    /**
     * Constructor.
     * @param storeMgr Manager for the store
     * @param clr ClassLoader resolver
     */
    protected AbstractArrayStore(RDBMSStoreManager storeMgr, ClassLoaderResolver clr)
    {
        super(storeMgr, clr);
    }

    /**
     * Accessor for the array from the datastore.
     * @param sm SM for the owner
     * @return The array (as a List of objects)
     */
    public List<E> getArray(DNStateManager sm)
    {
        Iterator<E> iter = iterator(sm);
        List<E> elements = new ArrayList<>();
        while (iter.hasNext())
        {
            E elem = iter.next();
            elements.add(elem);
        }

        return elements;
    }

    /**
     * Clear the association from owner to all elements. Observes the necessary dependent field settings 
     * with respect to whether it should delete the element when doing so.
     * @param sm StateManager for the container.
     */
    public void clear(DNStateManager sm)
    {
        Collection<E> dependentElements = null;
        if (ownerMemberMetaData.getArray().isDependentElement())
        {
            // Retain the dependent elements that need deleting after clearing
            dependentElements = new HashSet<>();
            Iterator<E> iter = iterator(sm);
            while (iter.hasNext())
            {
                E elem = iter.next();
                if (sm.getExecutionContext().getApiAdapter().isPersistable(elem))
                {
                    dependentElements.add(elem);
                }
            }
        }
        clearInternal(sm);

        if (dependentElements != null && dependentElements.size() > 0)
        {
            sm.getExecutionContext().deleteObjects(dependentElements.toArray());
        }
    }

    /**
     * Method to set the array for the specified owner to the passed value.
     * @param sm StateManager for the owner
     * @param array the array
     * @return Whether the array was updated successfully
     */
    public boolean set(DNStateManager sm, Object array)
    {
        if (array == null || Array.getLength(array) == 0)
        {
            return true;
        }

        // Validate all elements for writing
        ExecutionContext ec = sm.getExecutionContext();
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++)
        {
            Object obj = Array.get(array, i);
            validateElementForWriting(ec, obj, null);
        }

        boolean modified = false;
        List<Throwable> exceptions = new ArrayList<>();
        boolean batched = allowsBatching() && length > 1;

        ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
        try
        {
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                sqlControl.processStatementsForConnection(mconn); // Process all waiting batched statements before we start our work
            }
            catch (SQLException e)
            {
                throw new NucleusDataStoreException("SQLException", e);
            }

            // Loop through all elements to be added
            E element = null;
            for (int i = 0; i < length; i++)
            {
                element = (E) Array.get(array, i);

                // Add the row to the join table
                int[] rc = internalAdd(sm, element, mconn, batched, i, (i == length - 1));
                if (rc != null)
                {
                    for (int j = 0; j < rc.length; j++)
                    {
                        if (rc[j] > 0)
                        {
                            // At least one record was inserted
                            modified = true;
                        }
                    }
                }
            }
        }
        finally
        {
            mconn.release();
        }

        if (!exceptions.isEmpty())
        {
            // Throw all exceptions received as the cause of a NucleusDataStoreException so the user can see which record(s) didn't persist
            String msg = Localiser.msg("056009", exceptions.get(0).getMessage());
            NucleusLogger.DATASTORE.error(msg);
            throw new NucleusDataStoreException(msg, exceptions.toArray(new Throwable[exceptions.size()]), sm.getObject());
        }

        return modified;
    }

    /**
     * Adds one element to the association owner vs elements
     * @param sm StateManager for the container
     * @param element The element to add
     * @param position The position to add this element at
     * @return Whether it was successful
     */
    public boolean add(DNStateManager sm, E element, int position)
    {
        ExecutionContext ec = sm.getExecutionContext();
        validateElementForWriting(ec, element, null);

        boolean modified = false;
        ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
        try
        {
            // Add a row to the join table
            int[] returnCode = internalAdd(sm, element, mconn, false, position, true);
            if (returnCode[0] > 0)
            {
                modified = true;
            }
        }
        finally
        {
            mconn.release();
        }

        return modified;
    }

    /**
     * Accessor for an iterator through the array elements.
     * @param ownerSM StateManager for the container.
     * @return The Iterator
     */
    public abstract Iterator<E> iterator(DNStateManager ownerSM);

    public void clearInternal(DNStateManager ownerSM)
    {
        String clearStmt = getClearStmt();
        try
        {
            ExecutionContext ec = ownerSM.getExecutionContext();
            ManagedConnection mconn = getStoreManager().getConnectionManager().getConnection(ec);
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

    /**
     * Internal method to add a row to the join table.
     * Used by add() and set() to add a row to the join table.
     * @param sm StateManager for the owner of the collection
     * @param element The element to add the relation to
     * @param conn The connection
     * @param batched Whether we are batching
     * @param orderId The order id to use for this element relation
     * @param executeNow Whether to execute the statement now (and not wait for any batch)
     * @return Whether a row was inserted
     */
    public int[] internalAdd(DNStateManager sm, E element, ManagedConnection conn, boolean batched, int orderId, boolean executeNow)
    {
        ExecutionContext ec = sm.getExecutionContext();
        SQLController sqlControl = storeMgr.getSQLController();
        String addStmt = getAddStmtForJoinTable();
        try
        {
            PreparedStatement ps = sqlControl.getStatementForUpdate(conn, addStmt, batched);
            boolean notYetFlushedError = false;
            try
            {
                // Insert the join table row
                int jdbcPosition = 1;
                jdbcPosition = BackingStoreHelper.populateOwnerInStatement(sm, ec, ps, jdbcPosition, this);
                jdbcPosition = BackingStoreHelper.populateElementInStatement(ec, ps, element, jdbcPosition, elementMapping);
                jdbcPosition = BackingStoreHelper.populateOrderInStatement(ec, ps, orderId, jdbcPosition, orderMapping);
                if (relationDiscriminatorMapping != null)
                {
                    jdbcPosition = BackingStoreHelper.populateRelationDiscriminatorInStatement(ec, ps, jdbcPosition, this);
                }

                // Execute the statement
                return sqlControl.executeStatementUpdate(ec, conn, addStmt, ps, executeNow);
            }
            catch (NotYetFlushedException nfe)
            {
                notYetFlushedError = true;
                throw nfe;
            }
            finally
            {
                if (notYetFlushedError)
                {
                    sqlControl.abortStatementForConnection(conn, ps);
                }
                else
                {
                    sqlControl.closeStatement(conn, ps);
                }
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056009", addStmt), e);
        }
    }
}