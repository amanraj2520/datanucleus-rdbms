/**********************************************************************
Copyright (c) 2002 Mike Martin (TJDO) and others. All rights reserved.
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
2003 Andy Jefferson - coding standards
2004 Andy Jefferson - added sequence methods
2004 Andy Jefferson - patch from Rey Amarego for "escape" method
2004 Andy Jefferson - update to cater for bitReallyBoolean (Tibor Kiss)
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.adapter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.ClassNotResolvedException;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.identity.DatastoreId;
import org.datanucleus.metadata.JdbcType;
import org.datanucleus.plugin.PluginManager;
import org.datanucleus.store.StoreManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.identifier.IdentifierFactory;
import org.datanucleus.store.rdbms.key.Index;
import org.datanucleus.store.rdbms.key.PrimaryKey;
import org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping;
import org.datanucleus.store.rdbms.mapping.column.ColumnMapping;
import org.datanucleus.store.rdbms.schema.ForeignKeyInfo;
import org.datanucleus.store.rdbms.schema.RDBMSColumnInfo;
import org.datanucleus.store.rdbms.schema.SQLTypeInfo;
import org.datanucleus.store.rdbms.sql.method.SQLMethod;
import org.datanucleus.store.rdbms.table.Column;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.store.schema.StoreSchemaHandler;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Provides methods for adapting SQL language elements to the PostgreSQL database.
 */
public class PostgreSQLAdapter extends BaseDatastoreAdapter
{
    /** List of Postgresql keywords that aren't in SQL92, SQL99 */
    public static final String POSTGRESQL_RESERVED_WORDS =
        "ALL,ANALYSE,ANALYZE,DO,FREEZE,ILIKE,ISNULL,OFFSET,PLACING,VERBOSE";

    /**
     * Constructor.
     * @param metadata MetaData from the JDBC driver
     */
    public PostgreSQLAdapter(DatabaseMetaData metadata)
    {
        super(metadata);

        if (datastoreMajorVersion < 7)
        {
            // TODO Localise this message
            throw new NucleusDataStoreException("PostgreSQL version is " + datastoreMajorVersion + '.' + datastoreMinorVersion + ", 7.0 or later required");
        }
        if (datastoreMajorVersion == 7 && datastoreMinorVersion <= 2)
        {
            // The driver correctly reports the max table name length as 32.
            // However, constraint names are apparently limited to 31.  In this case we get better looking names by simply treating them all as limited to 31.
            --maxTableNameLength;
            --maxConstraintNameLength;
            --maxIndexNameLength;
        }

        reservedKeywords.addAll(StringUtils.convertCommaSeparatedStringToSet(POSTGRESQL_RESERVED_WORDS));

        supportedOptions.add(LOCK_ROW_USING_SELECT_FOR_UPDATE);
        supportedOptions.add(LOCK_ROW_USING_SELECT_FOR_UPDATE_NOWAIT);
        supportedOptions.add(PRIMARYKEY_IN_CREATE_STATEMENTS);
        supportedOptions.add(SEQUENCES);
        supportedOptions.add(IDENTITY_COLUMNS);
        supportedOptions.add(ORDERBY_NULLS_DIRECTIVES);
        supportedOptions.add(PARAMETER_IN_CASE_IN_UPDATE_CLAUSE);
        supportedOptions.remove(IDENTITY_COLUMN_TYPE_SPECIFICATION);
        supportedOptions.remove(IDENTITY_KEYS_NULL_SPECIFICATION);
        supportedOptions.remove(DISTINCT_WITH_SELECT_FOR_UPDATE);
        supportedOptions.remove(PERSIST_OF_UNASSIGNED_CHAR);
        if ((datastoreMajorVersion == 7 && datastoreMinorVersion < 2))
        {
            supportedOptions.remove(ALTER_TABLE_DROP_CONSTRAINT_SYNTAX);
        }
        else
        {
            supportedOptions.add(ALTER_TABLE_DROP_CONSTRAINT_SYNTAX);
        }
        supportedOptions.add(BIT_IS_REALLY_BOOLEAN);
        supportedOptions.add(CHAR_COLUMNS_PADDED_WITH_SPACES);
        if (datastoreMajorVersion >= 11)
        {
            // PostgreSQL 11 allows simple "stored procedures" but cannot select result sets or have OUT params
            supportedOptions.add(STORED_PROCEDURES);
        }

        supportedOptions.remove(TX_ISOLATION_NONE);
        supportedOptions.remove(UPDATE_STATEMENT_ALLOW_TABLE_ALIAS_IN_SET_CLAUSE);
        supportedOptions.remove(TX_ISOLATION_READ_UNCOMMITTED); // Not supported in PostgreSQL AFAIK

        supportedOptions.add(OPERATOR_BITWISE_AND);
        supportedOptions.add(OPERATOR_BITWISE_OR);
        supportedOptions.add(OPERATOR_BITWISE_XOR);

        supportedOptions.add(NATIVE_ENUM_TYPE);

        supportedOptions.remove(VALUE_GENERATION_UUID_STRING); // PostgreSQL charsets don't seem to allow this
    }

    /**
     * Initialise the types for this datastore.
     * @param handler SchemaHandler that we initialise the types for
     * @param mconn Managed connection to use
     */
    public void initialiseTypes(StoreSchemaHandler handler, ManagedConnection mconn)
    {
        super.initialiseTypes(handler, mconn);

        // Add on any missing JDBC types when not available from driver

        // Add CHAR : Not present in PSQL 8.1.405
        SQLTypeInfo sqlType = new PostgreSQLTypeInfo("char", (short)Types.CHAR, 65000, null, null, null, 0, false, (short)3, false, false, false, "char", (short)0, (short)0, 10);
        boolean added = addSQLTypeForJDBCType(handler, mconn, (short)Types.CHAR, sqlType, true);

        // Add CLOB mirrored from LONGVARCHAR ("text") from JDBC driver ** if not provided by the driver **
        sqlType = new PostgreSQLTypeInfo("text", (short)Types.CLOB, 0, null, null, null, 1, true, (short)3, true, false, false, "text", (short)0, (short)0, 10);
        added = addSQLTypeForJDBCType(handler, mconn, (short)Types.CLOB, sqlType, true);
        if (added)
        {
            // Mimic as if LONGVARCHAR
            registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarcharColumnMapping.class, JDBCType.CLOB, "text", false);
            // Note that <= 5.2.2 we used ClobColumnMapping here, but PSQL uses a TEXT so why would that work?
//            registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.ClobColumnMapping.class, JDBCType.CLOB, "clob", false);
        }
        else
        {
            // Support as CLOB since provided by the driver
            registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.ClobColumnMapping.class, JDBCType.CLOB, "CLOB", false);
        }

        // Add BLOB mirrored from LONGVARBINARY ("bytea") from JDBC driver ** if not provided by the driver **
        sqlType = new PostgreSQLTypeInfo("bytea", (short)Types.BLOB, 0, null, null, null, 1, true, (short)3, true, false, false, "bytea", (short)0, (short)0, 10);
        added = addSQLTypeForJDBCType(handler, mconn, (short)Types.BLOB, sqlType, true);
        if (added)
        {
            // Mimic as if LONGVARBINARY
            registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarBinaryColumnMapping.class, JDBCType.BLOB, "bytea", false);
            registerColumnMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarBinaryColumnMapping.class, JDBCType.BLOB, "bytea", false);
            registerColumnMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarBinaryColumnMapping.class, JDBCType.BLOB, "bytea", false);
        }
        else
        {
            // Support as BLOB since provided by the driver
            registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
            registerColumnMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
            registerColumnMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
        }

        // Add BOOLEAN : Not present in PSQL 9.2.8 - just mirror what BIT does
        sqlType = new PostgreSQLTypeInfo("bool", (short)Types.BOOLEAN, 0, null, null, null, 1, false, (short)3, true, false, false, "bool", (short)0, (short)0, 10);
        added = addSQLTypeForJDBCType(handler, mconn, (short)Types.BOOLEAN, sqlType, true);

        // Add TINYINT : Not present in PSQL 9.2.8 - just mirror what SMALLINT does
        sqlType = new PostgreSQLTypeInfo("int2", (short)Types.TINYINT, 0, null, null, null, 1, false, (short)3, false, false, false, "int2", (short)0, (short)0, 10);
        added = addSQLTypeForJDBCType(handler, mconn, (short)Types.TINYINT, sqlType, true);

        // Add ARRAY : Not present in PSQL 9.2.8
        sqlType = new PostgreSQLTypeInfo("text array", (short)Types.ARRAY, 0, null, null, null, 1, false, (short)3, false, false, false, "text array", (short)0, (short)0, 10);
        added = addSQLTypeForJDBCType(handler, mconn, (short)Types.ARRAY, sqlType, true);
        sqlType = new PostgreSQLTypeInfo("int array", (short)Types.ARRAY, 0, null, null, null, 1, false, (short)3, false, false, false, "int array", (short)0, (short)0, 10);
        added = addSQLTypeForJDBCType(handler, mconn, (short)Types.ARRAY, sqlType, true);
    }

    /**
     * Accessor for the vendor id.
     * @return The vendor id.
     */
    public String getVendorID()
    {
        return "postgresql";
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.adapter.BaseDatastoreAdapter#getPreferredDefaultSQLTypeForJDBCType(org.datanucleus.metadata.JdbcType)
     */
    @Override
    public String getPreferredDefaultSQLTypeForJDBCType(JdbcType jdbcType)
    {
        if (jdbcType == JdbcType.BIGINT)
        {
            return "int8";
        }
        else if (jdbcType == JdbcType.BIT)
        {
            return "bool";
        }
        else if (jdbcType == JdbcType.BLOB)
        {
            return "bytea";
        }
        else if (jdbcType == JdbcType.CHAR)
        {
            return "char";
        }
        else if (jdbcType == JdbcType.CLOB)
        {
            return "text";
        }
        else if (jdbcType == JdbcType.DATE)
        {
            return "date";
        }
        else if (jdbcType == JdbcType.DOUBLE)
        {
            return "float8";
        }
        else if (jdbcType == JdbcType.INTEGER)
        {
            return "int4";
        }
        else if (jdbcType == JdbcType.LONGVARCHAR)
        {
            return "text";
        }
        else if (jdbcType == JdbcType.NUMERIC)
        {
            return "numeric";
        }
        else if (jdbcType == JdbcType.REAL)
        {
            return "float4";
        }
        else if (jdbcType == JdbcType.SMALLINT)
        {
            return "int2";
        }
        else if (jdbcType == JdbcType.TIME)
        {
            return "time";
        }
        else if (jdbcType == JdbcType.TIMESTAMP)
        {
            return "timestamptz";
        }
        else if (jdbcType == JdbcType.VARCHAR)
        {
            return "varchar";
        }

        return super.getPreferredDefaultSQLTypeForJDBCType(jdbcType);
    }

    public SQLTypeInfo newSQLTypeInfo(ResultSet rs)
    {
        SQLTypeInfo info = new PostgreSQLTypeInfo(rs);

        return info;
    }

    /**
     * Method to create a column info for the current row.
     * Overrides the dataType/columnSize/decimalDigits to cater for Postgresql particularities.
     * @param rs ResultSet from DatabaseMetaData.getColumns()
     * @return column info
     */
    public RDBMSColumnInfo newRDBMSColumnInfo(ResultSet rs)
    {
        RDBMSColumnInfo info = new RDBMSColumnInfo(rs);

        String typeName = info.getTypeName();
        if (typeName.equalsIgnoreCase("text"))
        {
            // Equate "text" to Types.LONGVARCHAR
            info.setDataType((short)Types.LONGVARCHAR);
        }
        else if (typeName.equalsIgnoreCase("bytea"))
        {
            // Equate "bytea" to Types.LONGVARBINARY
            info.setDataType((short)Types.LONGVARBINARY);
        }

        // The PostgreSQL drivers sometimes produce some truly funky metadata. 
        // Observed these next two occur during unit testing when decimal_widget.big_decimal_field reported a columnSize of 65535 and a decimalDigits of 65534, 
        // instead of the correct answers of 18 and 2. A -1 for either of these will cause their validation to be bypassed, which is a shame but it's all we can do.
        // No one should be surprised if we end up needing more of these.
        int columnSize = info.getColumnSize();
        if (columnSize > PostgreSQLTypeInfo.MAX_PRECISION)
        {
            info.setColumnSize(-1);
        }

        int decimalDigits = info.getDecimalDigits();
        if (decimalDigits > PostgreSQLTypeInfo.MAX_PRECISION)
        {
            info.setDecimalDigits(-1);
        }

        String columnDef = info.getColumnDef();
        if (columnDef != null)
        {
            // Ignore some columnDef cases where we do not support PostgreSQL specific syntax
            if (columnDef.startsWith("nextval("))
            {
                // Ignore any "nextval(...)"
                info.setColumnDef(null);
            }
            else if (columnDef.startsWith("'") && columnDef.endsWith("'"))
            {
                // Ignore when we have just a string (that may contain ::)
            }
            else if (columnDef.contains("::"))
            {
                // We want to strip off any PostgreSQL-specific "::" where this is not part of a default string
                info.setColumnDef(columnDef.substring(0, columnDef.indexOf("::")));
            }
        }

        return info;
    }

    /**
     * Method to return ForeignKeyInfo for the current row of the ResultSet which will have been
     * obtained from a call to DatabaseMetaData.getImportedKeys() or DatabaseMetaData.getExportedKeys().
     * @param rs The result set returned from DatabaseMetaData.get??portedKeys()
     * @return The foreign key info 
     */
    public ForeignKeyInfo newFKInfo(ResultSet rs)
    {
        org.datanucleus.store.rdbms.schema.ForeignKeyInfo info = super.newFKInfo(rs);

        // PostgreSQL sometimes return strange FK info. This checks for the FK name with various
        // extraneous info on the end with a separator of \000.
        String fkName = (String)info.getProperty("fk_name");
        int firstBackslashIdx = fkName.indexOf('\\');
        if (firstBackslashIdx > 0)
        {
            info.addProperty("fk_name", fkName.substring(0, firstBackslashIdx));
        }
        return info;
    }

    // ------------------------------------ Schema Methods -------------------------------------

    public String getDropDatabaseStatement(String catalogName, String schemaName)
    {
        return "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE";
    }

    /**
     * Accessor for the SQL statement to add a column to a table.
     * @param table The table
     * @param col The column
     * @return The SQL necessary to add the column
     */
    public String getAddColumnStatement(Table table, Column col)
    {
        return "ALTER TABLE " + table.toString() + " ADD COLUMN " + col.getSQLDefinition();
    }

    /**
     * Method to return the INSERT statement to use when inserting into a table and we dont want to
     * specify columns. This is the case when we have a single column in the table and that column
     * is autoincrement/identity (and so is assigned automatically in the datastore).
     * Postgresql expects something like
     * <pre>
     * INSERT INTO tbl VALUES(DEFAULT)
     * </pre>
     * @param table The table
     * @return The statement for the INSERT
     */
    public String getInsertStatementForNoColumns(Table table)
    {
        return "INSERT INTO " + table.toString() + " VALUES (DEFAULT)";
    }

    /**
     * PostgreSQL allows specification of PRIMARY KEY in the CREATE TABLE, so
     * we need nothing here.
     * @param pk An object describing the primary key.
     * @param factory Identifier factory
     * @return The PK statement
     */
    public String getAddPrimaryKeyStatement(PrimaryKey pk, IdentifierFactory factory)
    {
        return null;
    }
 
    /**
     * Accessor for the statement for dropping a table.
     * PostgreSQL has supported the DROP TABLE tbl-name CASCADE since 7.3.
     * @param table The table to drop.
     * @return The statement for dropping a table.
     */
    public String getDropTableStatement(Table table)
    {
        // DROP TABLE {table} CASCADE is supported beginning in 7.3
        if (datastoreMajorVersion < 7 || (datastoreMajorVersion == 7 && datastoreMinorVersion < 3))
        {
            return "DROP TABLE " + table.toString();
        }
        return super.getDropTableStatement(table);
    }

    /**
     * Returns the appropriate DDL to create an index.
     * Overrides the superclass variant since Postgresql doesn't support having index names specified in a particular schema (i.e "{schema}.{indexName}").
     * @param idx An object describing the index.
     * @param factory Identifier factory
     * @return The text of the SQL statement.
     */
    public String getCreateIndexStatement(Index idx, IdentifierFactory factory)
    {
        /**
        CREATE [UNIQUE] INDEX [CONCURRENTLY] [name] ON tableName 
            [USING method] ( {column | (expression)} [COLLATE collation] [opclass] [ASC|DESC] [NULLS {FIRST|LAST}] [, ...] )
            [WITH (storage_parameter = value [, ... ])]
            [TABLESPACE tablespace ]
            [WHERE predicate]
        */
        // TODO Support USING, WITH, TABLESPACE, WHERE keywords
        StringBuilder stringBuilder = new StringBuilder("CREATE ").append((idx.getUnique() ? "UNIQUE " : "")).append("INDEX ");
        stringBuilder.append(factory.getIdentifierInAdapterCase(idx.getName()));
        stringBuilder.append(" ON ").append(idx.getTable().toString()).append(" ").append(idx.getColumnList(supportsOption(CREATE_INDEX_COLUMN_ORDERING)));

        String extendedSetting = idx.getValueForExtension(Index.EXTENSION_INDEX_EXTENDED_SETTING);
        if (extendedSetting != null)
        {
            stringBuilder.append(" ").append(extendedSetting);
        }

        return stringBuilder.toString();
    }

    /**
     * Accessor for the autoincrement sql access statement for this datastore.
     * @param table Table that the autoincrement is for
     * @param columnName Name of the column that the autoincrement is for
     * @return The statement for getting the latest auto-increment key
     */
    public String getIdentityLastValueStmt(Table table, String columnName)
    {
        StringBuilder stmt=new StringBuilder("SELECT currval('");

        // PostgreSQL creates a sequence for each SERIAL column with name of the form "{table}_seq"
        // in the current catalog/schema. PostgreSQL doesn't use catalog so ignore that
        if (table.getSchemaName() != null)
        {
            stmt.append(table.getSchemaName().replace(getIdentifierQuoteString(), ""));
            stmt.append(getCatalogSeparator());
        }

        String tableName = table.getIdentifier().toString();
        boolean quoted = tableName.startsWith(getIdentifierQuoteString());
        if (quoted)
        {
            stmt.append(getIdentifierQuoteString());
        }
        stmt.append(tableName.replace(getIdentifierQuoteString(), ""));
        stmt.append("_");
        stmt.append(columnName.replace(getIdentifierQuoteString(), ""));
        stmt.append("_seq");
        if (quoted)
        {
            stmt.append(getIdentifierQuoteString());
        }

        stmt.append("')");

        return stmt.toString();
    }

    /**
     * Accessor for the identity (auto-increment) keyword for generating DDLs (CREATE TABLEs...).
     * Provides the {@link ColumnMapping} as context for data stores that have different identity
     * keywords based on the mapped Java / JDBC type.
     * @param storeMgr The Store manager
     * @param columnMapping The column mapping
     * @return The keyword for a column using auto-increment/identity
     */
    @Override
    public String getIdentityKeyword(StoreManager storeMgr, ColumnMapping columnMapping)
    {
        // PostgreSQL differentiates between SERIAL (integer) and BIGSERIAL (bigint).
        // https://www.postgresql.org/docs/14/datatype-numeric.html
        if (columnMapping instanceof BigIntColumnMapping)
        {
            return "BIGSERIAL";
        }

        return "SERIAL";
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.adapter.BaseDatastoreAdapter#sequenceExists(java.sql.Connection, java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public boolean sequenceExists(Connection conn, String catalogName, String schemaName, String seqName)
    {
        String stmtStr = "SELECT relname FROM pg_class WHERE relname=?";
        PreparedStatement ps = null;
        ResultSet rs = null;
        String seqNameToSearch = seqName;
        if (seqName.startsWith("\"") && seqName.endsWith("\""))
        {
            seqNameToSearch = seqName.substring(1, seqName.length() - 1);
        }

        try
        {
            NucleusLogger.DATASTORE_NATIVE.debug(stmtStr + " : for sequence=" + seqNameToSearch);
            ps = conn.prepareStatement(stmtStr);
            ps.setString(1, seqNameToSearch);
            rs = ps.executeQuery();
            if (rs.next())
            {
                return true;
            }
            return false;
        }
        catch (SQLException sqle)
        {
            NucleusLogger.DATASTORE_RETRIEVE.debug("Exception while executing query for sequence " + seqNameToSearch + " : " + stmtStr + " - " + sqle.getMessage());
        }
        finally
        {
            try
            {
                try
                {
                    if (rs != null && !rs.isClosed())
                    {
                        rs.close();
                    }
                }
                finally
                {
                    if (ps != null)
                    {
                        ps.close();
                    }
                }
            }
            catch (SQLException sqle)
            {
            }
        }

        return super.sequenceExists(conn, catalogName, schemaName, seqName);
    }

    /**
     * Accessor for the sequence statement to create the sequence.
     * @param sequenceName Name of the sequence 
     * @param min Minimum value for the sequence
     * @param max Maximum value for the sequence
     * @param start Start value for the sequence
     * @param increment Increment value for the sequence
     * @param cacheSize Cache size for the sequence
     * @return The statement for getting the next id from the sequence
     */
    public String getSequenceCreateStmt(String sequenceName, Integer min, Integer max, Integer start, Integer increment, Integer cacheSize)
    {
        if (sequenceName == null)
        {
            throw new NucleusUserException(Localiser.msg("051028"));
        }

        StringBuilder stmt = new StringBuilder("CREATE SEQUENCE ");
        stmt.append(sequenceName);
        if (min != null)
        {
            stmt.append(" MINVALUE " + min);
        }
        if (max != null)
        {
            stmt.append(" MAXVALUE " + max);
        }
        if (start != null)
        {
            stmt.append(" START WITH " + start);
        }
        if (increment != null)
        {
            stmt.append(" INCREMENT BY " + increment);
        }
        if (cacheSize != null)
        {
            stmt.append(" CACHE " + cacheSize);
        }
        else
        {
            stmt.append(" CACHE 1");
        }

        return stmt.toString();
    }

    /**
     * Accessor for the statement for getting the next id from the sequence for this datastore.
     * @param sequenceName Name of the sequence 
     * @return The statement for getting the next id for the sequence
     **/
    public String getSequenceNextStmt(String sequenceName)
    {
        if (sequenceName == null)
        {
            throw new NucleusUserException(Localiser.msg("051028"));
        }

        StringBuilder stmt=new StringBuilder("SELECT nextval('");
        stmt.append(sequenceName);
        stmt.append("')");

        return stmt.toString();
    }

    /**
     * Whether the datastore will support setting the query fetch size to the supplied value.
     * @param size The value to set to
     * @return Whether it is supported.
     */
    public boolean supportsQueryFetchSize(int size)
    {
        if (driverMajorVersion > 7)
        {
            // Supported for Postgresql 8
            return true;
        }
        return false;
    }

    /**
     * Method to return the SQL to append to the WHERE clause of a SELECT statement to handle
     * restriction of ranges using the LIMUT keyword.
     * @param offset The offset to return from
     * @param count The number of items to return
     * @param hasOrdering Whether ordering is present
     * @return The SQL to append to allow for ranges using LIMIT.
     */
    @Override
    public String getRangeByLimitEndOfStatementClause(long offset, long count, boolean hasOrdering)
    {
        if (offset <= 0 && count <= 0)
        {
            return "";
        }

        if (datastoreMajorVersion < 8 || (datastoreMajorVersion == 8 && datastoreMinorVersion <= 3))
        {
            String str = "";
            if (count > 0)
            {
                str += "LIMIT " + count + " ";
            }
            if (offset >= 0)
            {
                str += "OFFSET " + offset + " ";
            }
            return str;
        }

        // Use SQL 2008 standard OFFSET/FETCH keywords
        StringBuilder str = new StringBuilder();
        if (offset > 0)
        {
            str.append("OFFSET " + offset + (offset > 1 ? " ROWS " : " ROW "));
        }
        if (count > 0)
        {
            str.append("FETCH NEXT " + (count > 1 ? (count + " ROWS ONLY ") : "ROW ONLY "));
        }
        return str.toString();
    }

    /**
     * The character for escaping patterns.
     * @return Escape character(s)
     */
    public String getEscapePatternExpression()
    {
        if (datastoreMajorVersion > 8 || (datastoreMajorVersion == 8 && datastoreMinorVersion >= 3))
        {
            return "ESCAPE E'\\\\'";
        }
        return "ESCAPE '\\\\'";
    }

    /**
     * return whether this exception represents a cancelled statement.
     * @param sqle the exception
     * @return whether it is a cancel
     */
    public boolean isStatementCancel(SQLException sqle)
    {
        if (sqle.getErrorCode() == 57014)
        {
            return true;
        }
        return false;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.adapter.BaseDatastoreAdapter#getSQLMethodClass(java.lang.String, java.lang.String)
     */
    @Override
    public Class<? extends SQLMethod> getSQLMethodClass(String className, String methodName, ClassLoaderResolver clr)
    {
        if (className == null)
        {
            if ("log".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.LogFunction2.class;
            else if ("YEAR".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalYearMethod3.class;
            else if ("MONTH".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMonthMethod3.class;
            else if ("MONTH_JAVA".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMonthJavaMethod3.class;
            else if ("DAY".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayMethod3.class;
            else if ("DAY_OF_WEEK".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayOfWeekMethod2.class;
            else if ("HOUR".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalHourMethod3.class;
            else if ("MINUTE".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMinuteMethod3.class;
            else if ("SECOND".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalSecondMethod3.class;
            else if ("WEEK".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalWeekMethod3.class;
            else if ("QUARTER".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalQuarterMethod3.class;
        }
        else
        {
            Class cls = null;
            try
            {
                cls = clr.classForName(className);
            }
            catch (ClassNotResolvedException cnre) {}

            if ("java.lang.String".equals(className))
            {
                if ("concat".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringConcat1Method.class;
                else if ("indexOf".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringIndexOf5Method.class;
                else if ("similarTo".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringSimilarPostgresqlMethod.class;
                else if ("substring".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringSubstring5Method.class;
                else if ("translate".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringTranslateMethod.class;
                else if ("trim".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringTrim3Method.class;
                else if ("trimLeft".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringTrimLeft3Method.class;
                else if ("trimRight".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringTrimRight3Method.class;
            }
            else if ("java.util.Date".equals(className) || (cls != null && java.util.Date.class.isAssignableFrom(cls)))
            {
                if ("getDay".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayMethod3.class;
                else if ("getDayOfWeek".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayOfWeekMethod2.class;
                else if ("getDate".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayMethod3.class;
                else if ("getMonth".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMonthJavaMethod3.class;
                else if ("getYear".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalYearMethod3.class;
                else if ("getHour".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalHourMethod3.class;
                else if ("getMinute".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMinuteMethod3.class;
                else if ("getSecond".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalSecondMethod3.class;
            }
            else if ("java.time.LocalTime".equals(className))
            {
                if ("getHour".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalHourMethod3.class;
                else if ("getMinute".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMinuteMethod3.class;
                else if ("getSecond".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalSecondMethod3.class;
            }
            else if ("java.time.LocalDate".equals(className))
            {
                if ("getDayOfMonth".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayMethod3.class;
                else if ("getDayOfWeek".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayOfWeekMethod2.class;
                else if ("getMonthValue".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMonthMethod3.class;
                else if ("getYear".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalYearMethod3.class;
            }
            else if ("java.time.LocalDateTime".equals(className))
            {
                if ("getDayOfMonth".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayMethod3.class;
                else if ("getDayOfWeek".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayOfWeekMethod2.class;
                else if ("getMonthValue".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMonthMethod3.class;
                else if ("getYear".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalYearMethod3.class;
                else if ("getHour".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalHourMethod3.class;
                else if ("getMinute".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMinuteMethod3.class;
                else if ("getSecond".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalSecondMethod3.class;
            }
            else if ("java.time.MonthDay".equals(className))
            {
                if ("getDayOfMonth".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayMethod3.class;
                else if ("getMonthValue".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMonthMethod3.class;
            }
            else if ("java.time.Period".equals(className))
            {
                if ("getMonths".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMonthMethod3.class;
                else if ("getDays".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayMethod3.class;
                else if ("getYears".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalYearMethod3.class;
            }
            else if ("java.time.YearMonth".equals(className))
            {
                if ("getMonthValue".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalMonthMethod3.class;
                else if ("getYear".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalYearMethod3.class;
            }
        }

        return super.getSQLMethodClass(className, methodName, clr);
    }

    /**
     * Load all datastore mappings for this RDBMS database.
     * @param mgr the PluginManager
     * @param clr the ClassLoaderResolver
     */
    protected void loadColumnMappings(PluginManager mgr, ClassLoaderResolver clr)
    {
        // Load up built-in types for this datastore
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.BitColumnMapping.class, JDBCType.BIT, "BOOL", true);
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.BooleanColumnMapping.class, JDBCType.BOOLEAN, "BOOLEAN", false);
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.BooleanColumnMapping.class, JDBCType.TINYINT, "TINYINT", false);
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerColumnMapping(Byte.class.getName(), org.datanucleus.store.rdbms.mapping.column.TinyIntColumnMapping.class, JDBCType.TINYINT, "TINYINT", true);
        registerColumnMapping(Byte.class.getName(), org.datanucleus.store.rdbms.mapping.column.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerColumnMapping(Character.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", true);
        registerColumnMapping(Character.class.getName(), org.datanucleus.store.rdbms.mapping.column.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", false);

        registerColumnMapping(Double.class.getName(), org.datanucleus.store.rdbms.mapping.column.DoubleColumnMapping.class, JDBCType.DOUBLE, "DOUBLE", true);

        registerColumnMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.column.DoubleColumnMapping.class, JDBCType.DOUBLE, "DOUBLE", true);
        registerColumnMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.column.RealColumnMapping.class, JDBCType.REAL, "REAL", false);

        registerColumnMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.column.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", true);
        registerColumnMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);
        registerColumnMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.column.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);
        registerColumnMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.column.TinyIntColumnMapping.class, JDBCType.TINYINT, "TINYINT", false);
        registerColumnMapping(Integer.class.getName(), org.datanucleus.store.rdbms.mapping.column.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerColumnMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", true);
        registerColumnMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.column.IntegerColumnMapping.class, JDBCType.INTEGER, "INT", false);
        registerColumnMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.column.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);
        registerColumnMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.column.TinyIntColumnMapping.class, JDBCType.TINYINT, "TINYINT", false);
        registerColumnMapping(Long.class.getName(), org.datanucleus.store.rdbms.mapping.column.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerColumnMapping(Short.class.getName(), org.datanucleus.store.rdbms.mapping.column.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", true);
        registerColumnMapping(Short.class.getName(), org.datanucleus.store.rdbms.mapping.column.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", false);
        registerColumnMapping(Short.class.getName(), org.datanucleus.store.rdbms.mapping.column.TinyIntColumnMapping.class, JDBCType.TINYINT, "TINYINT", false);

        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", true);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarcharColumnMapping.class, JDBCType.LONGVARCHAR, "LONGVARCHAR", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.SqlXmlColumnMapping.class, JDBCType.SQLXML, "SQLXML", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.NVarcharColumnMapping.class, JDBCType.NVARCHAR, "NVARCHAR", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.NCharColumnMapping.class, JDBCType.NCHAR, "NCHAR", false);
// Added in initialiseTypes dependent on JDBC driver
//        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.ClobColumnMapping.class, JDBCType.CLOB, "CLOB", false);
//        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);

        registerColumnMapping(BigDecimal.class.getName(), org.datanucleus.store.rdbms.mapping.column.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", true);

        registerColumnMapping(BigInteger.class.getName(), org.datanucleus.store.rdbms.mapping.column.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", true);
        registerColumnMapping(BigInteger.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.DateColumnMapping.class, JDBCType.DATE, "DATE", true);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMPTZ", false);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", false);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIME", true);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIMETZ", false);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMPTZ", false);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", false);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMPTZ", true);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", false);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.DateColumnMapping.class, JDBCType.DATE, "DATE", false);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIME", false);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIMETZ", false);

        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMPTZ", true);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.DateColumnMapping.class, JDBCType.DATE, "DATE", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIME", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIMETZ", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerColumnMapping(java.util.UUID.class.getName(), org.datanucleus.store.rdbms.mapping.column.OtherColumnMapping.class, JDBCType.OTHER, "UUID", false);
        registerColumnMapping(java.util.UUID.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.util.UUID.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);

        // Use Collection as the way to signal that we want an ARRAY type
        registerColumnMapping(java.util.Collection.class.getName(), org.datanucleus.store.rdbms.mapping.column.ArrayColumnMapping.class, JDBCType.ARRAY, "TEXT ARRAY", false);
        registerColumnMapping(java.util.Collection.class.getName(), org.datanucleus.store.rdbms.mapping.column.ArrayColumnMapping.class, JDBCType.ARRAY, "INT ARRAY", false);

        registerColumnMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarBinaryColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);
// Added in initialiseTypes dependent on JDBC driver
//        registerColumnMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);

        registerColumnMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarBinaryColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);
// Added in initialiseTypes dependent on JDBC driver
//        registerColumnMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);

        registerColumnMapping(java.io.File.class.getName(), org.datanucleus.store.rdbms.mapping.column.BinaryStreamColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);

        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", true);
        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", false);
        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);
        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);

        super.loadColumnMappings(mgr, clr);
    }
}