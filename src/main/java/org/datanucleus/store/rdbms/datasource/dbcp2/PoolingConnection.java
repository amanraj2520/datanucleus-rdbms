/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.datanucleus.store.rdbms.datasource.dbcp2;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.NoSuchElementException;

import org.datanucleus.store.rdbms.datasource.dbcp2.pool2.KeyedObjectPool;
import org.datanucleus.store.rdbms.datasource.dbcp2.pool2.KeyedPooledObjectFactory;
import org.datanucleus.store.rdbms.datasource.dbcp2.pool2.PooledObject;
import org.datanucleus.store.rdbms.datasource.dbcp2.pool2.impl.DefaultPooledObject;

/**
 * A {@link DelegatingConnection} that pools {@link PreparedStatement}s.
 * <p>
 * The {@link #prepareStatement} and {@link #prepareCall} methods, rather than creating a new PreparedStatement each
 * time, may actually pull the statement from a pool of unused statements. The {@link PreparedStatement#close} method of
 * the returned statement doesn't actually close the statement, but rather returns it to the pool. (See
 * {@link PoolablePreparedStatement}, {@link PoolableCallableStatement}.)
 * </p>
 *
 * @see PoolablePreparedStatement
 * @since 2.0
 */
public class PoolingConnection extends DelegatingConnection<Connection>
        implements KeyedPooledObjectFactory<PStmtKey, DelegatingPreparedStatement> {

    /**
     * Statement types.
     *
     * @since 2.0 protected enum.
     * @since 2.4.0 public enum.
     */
    public enum StatementType {

        /**
         * Callable statement.
         */
        CALLABLE_STATEMENT,

        /**
         * Prepared statement.
         */
        PREPARED_STATEMENT
    }

    /** Pool of {@link PreparedStatement}s. and {@link CallableStatement}s */
    private KeyedObjectPool<PStmtKey, DelegatingPreparedStatement> pstmtPool;

    /**
     * Constructor.
     *
     * @param connection
     *            the underlying {@link Connection}.
     */
    public PoolingConnection(final Connection connection) {
        super(connection);
    }

    /**
     * {@link KeyedPooledObjectFactory} method for activating pooled statements.
     *
     * @param key
     *            ignored
     * @param pooledObject
     *            wrapped pooled statement to be activated
     */
    @Override
    public void activateObject(final PStmtKey key, final PooledObject<DelegatingPreparedStatement> pooledObject)
            throws Exception {
        pooledObject.getObject().activate();
    }

    /**
     * Closes and frees all {@link PreparedStatement}s or {@link CallableStatement}s from the pool, and close the
     * underlying connection.
     */
    @Override
    public synchronized void close() throws SQLException {
        try {
            if (null != pstmtPool) {
                final KeyedObjectPool<PStmtKey, DelegatingPreparedStatement> oldpool = pstmtPool;
                pstmtPool = null;
                try {
                    oldpool.close();
                } catch (final RuntimeException e) {
                    throw e;
                } catch (final Exception e) {
                    throw new SQLException("Cannot close connection", e);
                }
            }
        } finally {
            try {
                getDelegateInternal().close();
            } finally {
                setClosedInternal(true);
            }
        }
    }

    /**
     * Creates a PStmtKey for the given arguments.
     *
     * @param sql
     *            the SQL string used to define the statement
     *
     * @return the PStmtKey created for the given arguments.
     */
    protected PStmtKey createKey(final String sql) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull());
    }

    /**
     * Creates a PStmtKey for the given arguments.
     *
     * @param sql
     *            the SQL string used to define the statement
     * @param columnIndexes
     *            column indexes
     *
     * @return the PStmtKey created for the given arguments.
     */
    protected PStmtKey createKey(final String sql, final int columnIndexes[]) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull(), columnIndexes);
    }

    protected PStmtKey createKey(final String sql, final int autoGeneratedKeys) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull(), autoGeneratedKeys);
    }

    /**
     * Creates a PStmtKey for the given arguments.
     *
     * @param sql
     *            the SQL string used to define the statement
     * @param resultSetType
     *            result set type
     * @param resultSetConcurrency
     *            result set concurrency
     *
     * @return the PStmtKey created for the given arguments.
     */
    protected PStmtKey createKey(final String sql, final int resultSetType, final int resultSetConcurrency) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull(), resultSetType, resultSetConcurrency);
    }

    /**
     * Creates a PStmtKey for the given arguments.
     *
     * @param sql
     *            the SQL string used to define the statement
     * @param resultSetType
     *            result set type
     * @param resultSetConcurrency
     *            result set concurrency
     * @param resultSetHoldability
     *            result set holdability
     *
     * @return the PStmtKey created for the given arguments.
     */
    protected PStmtKey createKey(final String sql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull(), resultSetType, resultSetConcurrency,
                resultSetHoldability);
    }

    /**
     * Creates a PStmtKey for the given arguments.
     *
     * @param sql
     *            the SQL string used to define the statement
     * @param resultSetType
     *            result set type
     * @param resultSetConcurrency
     *            result set concurrency
     * @param resultSetHoldability
     *            result set holdability
     * @param statementType
     *            statement type
     *
     * @return the PStmtKey created for the given arguments.
     */
    protected PStmtKey createKey(final String sql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability, final StatementType statementType) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull(), resultSetType, resultSetConcurrency,
                resultSetHoldability, statementType);
    }

    /**
     * Creates a PStmtKey for the given arguments.
     *
     * @param sql
     *            the SQL string used to define the statement
     * @param resultSetType
     *            result set type
     * @param resultSetConcurrency
     *            result set concurrency
     * @param statementType
     *            statement type
     *
     * @return the PStmtKey created for the given arguments.
     */
    protected PStmtKey createKey(final String sql, final int resultSetType, final int resultSetConcurrency,
            final StatementType statementType) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull(), resultSetType, resultSetConcurrency, statementType);
    }

    /**
     * Creates a PStmtKey for the given arguments.
     *
     * @param sql
     *            the SQL string used to define the statement
     * @param statementType
     *            statement type
     *
     * @return the PStmtKey created for the given arguments.
     */
    protected PStmtKey createKey(final String sql, final StatementType statementType) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull(), statementType, null);
    }

    /**
     * Creates a PStmtKey for the given arguments.
     *
     * @param sql
     *            the SQL string used to define the statement
     * @param columnNames
     *            column names
     *
     * @return the PStmtKey created for the given arguments.
     */
    protected PStmtKey createKey(final String sql, final String columnNames[]) {
        return new PStmtKey(normalizeSQL(sql), getCatalogOrNull(), getSchemaOrNull(), columnNames);
    }

    /**
     * {@link KeyedPooledObjectFactory} method for destroying PoolablePreparedStatements and PoolableCallableStatements.
     * Closes the underlying statement.
     *
     * @param key
     *            ignored
     * @param pooledObject
     *            the wrapped pooled statement to be destroyed.
     */
    @Override
    public void destroyObject(final PStmtKey key, final PooledObject<DelegatingPreparedStatement> pooledObject)
            throws Exception {
        pooledObject.getObject().getInnermostDelegate().close();
    }

    private String getCatalogOrNull() {
        String catalog = null;
        try {
            catalog = getCatalog();
        } catch (final SQLException e) {
            // Ignored
        }
        return catalog;
    }

    private String getSchemaOrNull() {
        String catalog = null;
        try {
            catalog = getSchema();
        } catch (final SQLException e) {
            // Ignored
        }
        return catalog;
    }

    /**
     * {@link KeyedPooledObjectFactory} method for creating {@link PoolablePreparedStatement}s or
     * {@link PoolableCallableStatement}s. The <code>stmtType</code> field in the key determines whether a
     * PoolablePreparedStatement or PoolableCallableStatement is created.
     *
     * @param key
     *            the key for the {@link PreparedStatement} to be created
     * @see #createKey(String, int, int, StatementType)
     */
    @SuppressWarnings("resource")
    @Override
    public PooledObject<DelegatingPreparedStatement> makeObject(final PStmtKey key) throws Exception {
        if (null == key) {
            throw new IllegalArgumentException("Prepared statement key is null or invalid.");
        }
        if (key.getStmtType() == StatementType.PREPARED_STATEMENT) {
            final PreparedStatement statement = (PreparedStatement) key.createStatement(getDelegate());
            @SuppressWarnings({"rawtypes", "unchecked" }) // Unable to find way to avoid this
            final PoolablePreparedStatement pps = new PoolablePreparedStatement(statement, key, pstmtPool, this);
            return new DefaultPooledObject<>(pps);
        }
        final CallableStatement statement = (CallableStatement) key.createStatement(getDelegate());
        final PoolableCallableStatement pcs = new PoolableCallableStatement(statement, key, pstmtPool, this);
        return new DefaultPooledObject<>(pcs);
    }

    /**
     * Normalizes the given SQL statement, producing a canonical form that is semantically equivalent to the original.
     *
     * @param sql The statement to be normalized.
     *
     * @return The canonical form of the supplied SQL statement.
     */
    protected String normalizeSQL(final String sql) {
        return sql.trim();
    }

    /**
     * {@link KeyedPooledObjectFactory} method for passivating {@link PreparedStatement}s or {@link CallableStatement}s.
     * Invokes {@link PreparedStatement#clearParameters}.
     *
     * @param key
     *            ignored
     * @param pooledObject
     *            a wrapped {@link PreparedStatement}
     */
    @Override
    public void passivateObject(final PStmtKey key, final PooledObject<DelegatingPreparedStatement> pooledObject)
            throws Exception {
        @SuppressWarnings("resource")
        final DelegatingPreparedStatement dps = pooledObject.getObject();
        dps.clearParameters();
        dps.passivate();
    }

    /**
     * Creates or obtains a {@link CallableStatement} from the pool.
     *
     * @param sql
     *            the SQL string used to define the CallableStatement
     * @return a {@link PoolableCallableStatement}
     * @throws SQLException
     *             Wraps an underlying exception.
     */
    @Override
    public CallableStatement prepareCall(final String sql) throws SQLException {
        try {
            return (CallableStatement) pstmtPool.borrowObject(createKey(sql, StatementType.CALLABLE_STATEMENT));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenCallableStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow callableStatement from pool failed", e);
        }
    }

    /**
     * Creates or obtains a {@link CallableStatement} from the pool.
     *
     * @param sql
     *            the SQL string used to define the CallableStatement
     * @param resultSetType
     *            result set type
     * @param resultSetConcurrency
     *            result set concurrency
     * @return a {@link PoolableCallableStatement}
     * @throws SQLException
     *             Wraps an underlying exception.
     */
    @Override
    public CallableStatement prepareCall(final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        try {
            return (CallableStatement) pstmtPool.borrowObject(
                    createKey(sql, resultSetType, resultSetConcurrency, StatementType.CALLABLE_STATEMENT));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenCallableStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow callableStatement from pool failed", e);
        }
    }

    /**
     * Creates or obtains a {@link CallableStatement} from the pool.
     *
     * @param sql
     *            the SQL string used to define the CallableStatement
     * @param resultSetType
     *            result set type
     * @param resultSetConcurrency
     *            result set concurrency
     * @param resultSetHoldability
     *            result set holdability
     * @return a {@link PoolableCallableStatement}
     * @throws SQLException
     *             Wraps an underlying exception.
     */
    @Override
    public CallableStatement prepareCall(final String sql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) throws SQLException {
        try {
            return (CallableStatement) pstmtPool.borrowObject(createKey(sql, resultSetType, resultSetConcurrency,
                    resultSetHoldability, StatementType.CALLABLE_STATEMENT));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenCallableStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow callableStatement from pool failed", e);
        }
    }

    /**
     * Creates or obtains a {@link PreparedStatement} from the pool.
     *
     * @param sql
     *            the SQL string used to define the PreparedStatement
     * @return a {@link PoolablePreparedStatement}
     */
    @Override
    public PreparedStatement prepareStatement(final String sql) throws SQLException {
        if (null == pstmtPool) {
            throw new SQLException("Statement pool is null - closed or invalid PoolingConnection.");
        }
        try {
            return pstmtPool.borrowObject(createKey(sql));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenPreparedStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow prepareStatement from pool failed", e);
        }
    }

    @Override
    public PreparedStatement prepareStatement(final String sql, final int autoGeneratedKeys) throws SQLException {
        if (null == pstmtPool) {
            throw new SQLException("Statement pool is null - closed or invalid PoolingConnection.");
        }
        try {
            return pstmtPool.borrowObject(createKey(sql, autoGeneratedKeys));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenPreparedStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow prepareStatement from pool failed", e);
        }
    }

    /**
     * Creates or obtains a {@link PreparedStatement} from the pool.
     *
     * @param sql
     *            the SQL string used to define the PreparedStatement
     * @param columnIndexes
     *            column indexes
     * @return a {@link PoolablePreparedStatement}
     */
    @Override
    public PreparedStatement prepareStatement(final String sql, final int columnIndexes[]) throws SQLException {
        if (null == pstmtPool) {
            throw new SQLException("Statement pool is null - closed or invalid PoolingConnection.");
        }
        try {
            return pstmtPool.borrowObject(createKey(sql, columnIndexes));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenPreparedStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow prepareStatement from pool failed", e);
        }
    }

    /**
     * Creates or obtains a {@link PreparedStatement} from the pool.
     *
     * @param sql
     *            the SQL string used to define the PreparedStatement
     * @param resultSetType
     *            result set type
     * @param resultSetConcurrency
     *            result set concurrency
     * @return a {@link PoolablePreparedStatement}
     */
    @Override
    public PreparedStatement prepareStatement(final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        if (null == pstmtPool) {
            throw new SQLException("Statement pool is null - closed or invalid PoolingConnection.");
        }
        try {
            return pstmtPool.borrowObject(createKey(sql, resultSetType, resultSetConcurrency));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenPreparedStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow prepareStatement from pool failed", e);
        }
    }

    /**
     * Creates or obtains a {@link PreparedStatement} from the pool.
     *
     * @param sql
     *            the SQL string used to define the PreparedStatement
     * @param resultSetType
     *            result set type
     * @param resultSetConcurrency
     *            result set concurrency
     * @param resultSetHoldability
     *            result set holdability
     * @return a {@link PoolablePreparedStatement}
     */
    @Override
    public PreparedStatement prepareStatement(final String sql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) throws SQLException {
        if (null == pstmtPool) {
            throw new SQLException("Statement pool is null - closed or invalid PoolingConnection.");
        }
        try {
            return pstmtPool.borrowObject(createKey(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenPreparedStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow prepareStatement from pool failed", e);
        }
    }

    /**
     * Creates or obtains a {@link PreparedStatement} from the pool.
     *
     * @param sql
     *            the SQL string used to define the PreparedStatement
     * @param columnNames
     *            column names
     * @return a {@link PoolablePreparedStatement}
     */
    @Override
    public PreparedStatement prepareStatement(final String sql, final String columnNames[]) throws SQLException {
        if (null == pstmtPool) {
            throw new SQLException("Statement pool is null - closed or invalid PoolingConnection.");
        }
        try {
            return pstmtPool.borrowObject(createKey(sql, columnNames));
        } catch (final NoSuchElementException e) {
            throw new SQLException("MaxOpenPreparedStatements limit reached", e);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new SQLException("Borrow prepareStatement from pool failed", e);
        }
    }

    /**
     * Sets the prepared statement pool.
     *
     * @param pool
     *            the prepared statement pool.
     */
    public void setStatementPool(final KeyedObjectPool<PStmtKey, DelegatingPreparedStatement> pool) {
        pstmtPool = pool;
    }

    @Override
    public synchronized String toString() {
        if (pstmtPool != null) {
            return "PoolingConnection: " + pstmtPool.toString();
        }
        return "PoolingConnection: null";
    }

    /**
     * {@link KeyedPooledObjectFactory} method for validating pooled statements. Currently always returns true.
     *
     * @param key
     *            ignored
     * @param pooledObject
     *            ignored
     * @return {@code true}
     */
    @Override
    public boolean validateObject(final PStmtKey key, final PooledObject<DelegatingPreparedStatement> pooledObject) {
        return true;
    }
}
