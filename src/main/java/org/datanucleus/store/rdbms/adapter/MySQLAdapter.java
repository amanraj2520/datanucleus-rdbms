/**********************************************************************
Copyright (c) 2003 Mike Martin (TJDO) and others. All rights reserved. 
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
2003 Andy Jefferson - added getCreateTableStatement() method and commented
2004 Andy Jefferson - fixed convert expression
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.adapter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Properties;
import java.util.regex.Pattern;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.ClassNotResolvedException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.identity.DatastoreId;
import org.datanucleus.plugin.PluginManager;
import org.datanucleus.store.StoreManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.RDBMSPropertyNames;
import org.datanucleus.store.rdbms.identifier.IdentifierFactory;
import org.datanucleus.store.rdbms.key.Index;
import org.datanucleus.store.rdbms.key.PrimaryKey;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedMapping;
import org.datanucleus.store.rdbms.schema.RDBMSColumnInfo;
import org.datanucleus.store.rdbms.schema.SQLTypeInfo;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.method.SQLMethod;
import org.datanucleus.store.rdbms.sql.operation.SQLOperation;
import org.datanucleus.store.rdbms.table.Column;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.store.rdbms.table.TableImpl;
import org.datanucleus.store.schema.StoreSchemaHandler;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.StringUtils;

/**
 * Provides methods for adapting SQL language elements to the MySQL database.
 * Note that this also currently supports the MariaDB database.
 * 
 * We could contemplate splitting this into separate MySQL and MariaDB support at some point, but one of the issues to overcome with that
 * is that "datanucleus-geospatial" adds support for Geospatial types, including MySQL types and extends this class. We would need to
 * have an equivalent extension for MariaDB.
 */
public class MySQLAdapter extends BaseDatastoreAdapter
{
    /**
     * A string containing the list of MySQL keywords that are not also SQL/92 <i>reserved words</i>, separated by commas.
     * This list is normally obtained dynamically from the driver using DatabaseMetaData.getSQLKeywords(), but MySQL drivers are known to return an incomplete list.
     * <p>
     * This list was produced based on the reserved word list in the MySQL Manual (Version 4.0.10-gamma) at http://www.mysql.com/doc/en/Reserved_words.html.
     */
    public static final String NONSQL92_RESERVED_WORDS =
        "ANALYZE,AUTO_INCREMENT,BDB,BERKELEYDB,BIGINT,BINARY,BLOB,BTREE," +
        "CHANGE,COLUMNS,DATABASE,DATABASES,DAY_HOUR,DAY_MINUTE,DAY_SECOND," +
        "DELAYED,DISTINCTROW,DIV,ENCLOSED,ERRORS,ESCAPED,EXPLAIN,FIELDS," +
        "FORCE,FULLTEXT,FUNCTION,GEOMETRY,HASH,HELP,HIGH_PRIORITY," +
        "HOUR_MINUTE,HOUR_SECOND,IF,IGNORE,INDEX,INFILE,INNODB,KEYS,KILL," +
        "LIMIT,LINES,LOAD,LOCALTIME,LOCALTIMESTAMP,LOCK,LONG,LONGBLOB," +
        "LONGTEXT,LOW_PRIORITY,MASTER_SERVER_ID,MEDIUMBLOB,MEDIUMINT," +
        "MEDIUMTEXT,MIDDLEINT,MINUTE_SECOND,MOD,MRG_MYISAM,OPTIMIZE," +
        "OPTIONALLY,OUTFILE,PURGE,REGEXP,RENAME,REPLACE,REQUIRE,RETURNS," +
        "RLIKE,RTREE,SHOW,SONAME,SPATIAL,SQL_BIG_RESULT,SQL_CALC_FOUND_ROWS," +
        "SQL_SMALL_RESULT,SSL,STARTING,STRAIGHT_JOIN,STRIPED,TABLES," +
        "TERMINATED,TINYBLOB,TINYINT,TINYTEXT,TYPES,UNLOCK,UNSIGNED,USE," +
        "USER_RESOURCES,VARBINARY,VARCHARACTER,WARNINGS,XOR,YEAR_MONTH," +
        "ZEROFILL";

    boolean isMariaDB = false;

    /**
     * Constructor.
     * Overridden so we can add on our own list of NON SQL92 reserved words which is returned incorrectly with the JDBC driver.
     * @param metadata MetaData for the DB
     **/
    public MySQLAdapter(DatabaseMetaData metadata)
    {
        super(metadata);

        if (driverName.equalsIgnoreCase("mariadb-jdbc"))
        {
            isMariaDB = true;
        }

        reservedKeywords.addAll(StringUtils.convertCommaSeparatedStringToSet(NONSQL92_RESERVED_WORDS));

        supportedOptions.remove(ALTER_TABLE_DROP_CONSTRAINT_SYNTAX);
        if (datastoreMajorVersion < 4 ||
            (datastoreMajorVersion == 4 && datastoreMinorVersion == 0 && datastoreRevisionVersion < 13))
        {
            supportedOptions.remove(ALTER_TABLE_DROP_FOREIGN_KEY_CONSTRAINT);
        }
        else
        {
            // MySQL version 4.0.13 started supporting this syntax
            supportedOptions.add(ALTER_TABLE_DROP_FOREIGN_KEY_CONSTRAINT);
        }
        supportedOptions.remove(DEFERRED_CONSTRAINTS);
        supportedOptions.remove(DEFAULT_BEFORE_NULL_IN_COLUMN_OPTIONS);
        supportedOptions.add(PRIMARYKEY_IN_CREATE_STATEMENTS);
        if (datastoreMajorVersion < 5 && (datastoreMajorVersion < 4 || datastoreMinorVersion < 1))
        {
            // Support starts at MySQL 4.1
            supportedOptions.remove(EXISTS_SYNTAX);
        }
        else
        {
            supportedOptions.add(EXISTS_SYNTAX);
        }
        if (datastoreMajorVersion < 4)
        {
            supportedOptions.remove(UNION_SYNTAX);
        }
        else
        {
            supportedOptions.add(UNION_SYNTAX);
        }
        supportedOptions.add(BLOB_SET_USING_SETSTRING);
        supportedOptions.add(CLOB_SET_USING_SETSTRING);
        supportedOptions.add(CREATE_INDEXES_BEFORE_FOREIGN_KEYS);
        supportedOptions.add(IDENTITY_COLUMNS);
        supportedOptions.add(LOCK_ROW_USING_SELECT_FOR_UPDATE);
        supportedOptions.add(STORED_PROCEDURES);
        supportedOptions.add(ORDERBY_NULLS_USING_ISNULL);

        // MySQL DATETIME/TIMESTAMP millisec storage
        if (isMariaDB && (datastoreMajorVersion < 5 || (datastoreMajorVersion == 5 && datastoreMinorVersion < 3)))
        {
            // MariaDB before v5.3 doesn't store millisecs
            supportedOptions.remove(DATETIME_STORES_MILLISECS);
        }
        else if (!isMariaDB && (datastoreMajorVersion < 5 || (datastoreMajorVersion == 5 && datastoreMinorVersion < 7)))
        {
            // MySQL before v5.7 doesn't store millisecs
            supportedOptions.remove(DATETIME_STORES_MILLISECS);
        }

        if (isMariaDB && (datastoreMajorVersion > 10 || (datastoreMajorVersion == 10 && datastoreMinorVersion >= 3)))
        {
            // Sequences added to MariaDB v10.3
            supportedOptions.add(SEQUENCES);
        }

        supportedOptions.add(OPERATOR_BITWISE_AND);
        supportedOptions.add(OPERATOR_BITWISE_OR);
        supportedOptions.add(OPERATOR_BITWISE_XOR);
        supportedOptions.add(PARAMETER_IN_CASE_IN_UPDATE_CLAUSE);

//        supportedOptions.add(NATIVE_ENUM_TYPE); // There is no point to supporting this since "CHECK IN(...)" is ANSI standard and does the same

        supportedOptions.remove(VALUE_GENERATION_UUID_STRING); // MySQL charsets don't seem to allow this
    }

    /**
     * Initialise the types for this datastore.
     * @param handler SchemaHandler that we initialise the types for
     * @param mconn Managed connection to use
     */
    public void initialiseTypes(StoreSchemaHandler handler, ManagedConnection mconn)
    {
        super.initialiseTypes(handler, mconn);

        // Add on any missing JDBC types

        // MySQL JDBC v8 has no NUMERIC, try to map it as DECIMAL SQL type - unconfirmed whether this works
        SQLTypeInfo sqlType = new org.datanucleus.store.rdbms.adapter.MySQLTypeInfo(
            "DECIMAL", (short)Types.NUMERIC, 65, null, null, "[(M,D])] [ZEROFILL]", 1, true, (short)3, false, false, false, "DECIMAL", (short)-308, (short)308, 10);
        addSQLTypeForJDBCType(handler, mconn, (short)Types.NUMERIC, sqlType, true);

        // Map BLOB JDBC type to MEDIUMBLOB SQL type if no BLOB provided
        sqlType = new org.datanucleus.store.rdbms.adapter.MySQLTypeInfo(
            "MEDIUMBLOB", (short)Types.BLOB, 2147483647, null, null, null, 1, false, (short)1, false, false, false, "MEDIUMBLOB", (short)0, (short)0, 0);
        addSQLTypeForJDBCType(handler, mconn, (short)Types.BLOB, sqlType, true);

        // Map CLOB JDBC type to MEDIUMTEXT SQL type if no CLOB provided
        sqlType = new org.datanucleus.store.rdbms.adapter.MySQLTypeInfo(
            "MEDIUMTEXT", (short)Types.CLOB, 2147483647, null, null, null, 1, true, (short)1, false, false, false, "MEDIUMTEXT", (short)0, (short)0, 0);
        addSQLTypeForJDBCType(handler, mconn, (short)Types.CLOB, sqlType, true);
    }

    public String getVendorID()
    {
        return "mysql";
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.adapter.DatabaseAdapter#isReservedKeyword(java.lang.String)
     */
    @Override
    public boolean isReservedKeyword(String word)
    {
        if (super.isReservedKeyword(word))
        {
            return true;
        }
 
        // MySQL also allows identifiers with '-' as well as many other unicode characters, but then they need quoting
        // From the mysql 5.7 documentation: Permitted characters in unquoted identifiers: ASCII: [0-9,a-z,A-Z$_] (basic Latin letters, digits 0-9, dollar, underscore)
        if (needsQuoting(word))
        {
            return true;
        }
        return false;
    }

    private final static Pattern needsQuoting = Pattern.compile("[[^0-9]&&[^a-z]&&[^A-Z]&&[^$_]]+"); 

    private static boolean needsQuoting(String columnName) 
    {
        return needsQuoting.matcher(columnName).find();
    }

    /**
     * Method to create a column info for the current row.
     * Overrides the dataType for BLOB/CLOB as necessary
     * @param rs ResultSet from DatabaseMetaData.getColumns()
     * @return column info
     */
    public RDBMSColumnInfo newRDBMSColumnInfo(ResultSet rs)
    {
        RDBMSColumnInfo info = super.newRDBMSColumnInfo(rs);

        short dataType = info.getDataType();
        String typeName = info.getTypeName();

        // Fix to an issue in the MySQL JDBC driver 3.1.13. For columns of type BLOB, the driver returns -4 but should return 2004 
        if (dataType == Types.LONGVARBINARY && typeName.equalsIgnoreCase("mediumblob"))
        {
            //change it to BLOB, since it is a BLOB
            info.setDataType((short)Types.BLOB);
        }

        // Fix to an issue in the MySQL JDBC driver 3.1.13. For columns of type CLOB, the driver returns -1 but should return 2005 
        if (dataType == Types.LONGVARCHAR && typeName.equalsIgnoreCase("mediumtext"))
        {
            //change it to CLOB, since it is a CLOB
            info.setDataType((short)Types.CLOB);
        }

        return info;
    }

    public SQLTypeInfo newSQLTypeInfo(ResultSet rs)
    {
        SQLTypeInfo info = new org.datanucleus.store.rdbms.adapter.MySQLTypeInfo(rs);

        // The following block originated in TJDO, and was carried across up to DataNucleus 3.0-m4
        // It is now commented out so people can use BINARY/VARBINARY. What is it trying to achieve?
        // Exclude BINARY and VARBINARY since these equate to CHAR(M) BINARY and VARCHAR(M) BINARY respectively, 
        // which aren't true binary types (e.g. trailing space characters are stripped).
      /*String typeName = info.getTypeName();
        if (typeName.equalsIgnoreCase("binary") || typeName.equalsIgnoreCase("varbinary"))
        {
            return null;
        }*/

        return info;
    }

    // ------------------------------- Schema Methods ------------------------------------

    public String getCreateDatabaseStatement(String catalogName, String schemaName)
    {
        return "CREATE DATABASE IF NOT EXISTS " + catalogName;
    }

    public String getDropDatabaseStatement(String catalogName, String schemaName)
    {
        return "DROP DATABASE IF EXISTS " + catalogName;
    }

    /**
     * MySQL, when using AUTO_INCREMENT, requires the primary key specified
     * in the CREATE TABLE, so we do nothing here. 
     * @param pk An object describing the primary key.
     * @param factory Identifier factory
     * @return The PK statement
     */
    public String getAddPrimaryKeyStatement(PrimaryKey pk, IdentifierFactory factory)
    {
        return null;
    }

    /**
     * Method to return the CREATE TABLE statement.
     * Versions before 5 need INNODB table type selecting for them.
     * It seems, MySQL &ge; 5 still needs innodb in order to support transactions.
     * @param table The table
     * @param columns The columns in the table
     * @param props Properties for controlling the table creation
     * @param factory Identifier factory
     * @return The creation statement 
     */
    public String getCreateTableStatement(TableImpl table, Column[] columns, Properties props, IdentifierFactory factory)  
    {
        StringBuilder createStmt = new StringBuilder(super.getCreateTableStatement(table, columns, props, factory));

        // Check for specification of the "engine"
        String engineType = "INNODB";
        if (props != null && props.containsKey("mysql-engine-type"))
        {
            engineType = props.getProperty("mysql-engine-type");
        }
        else if (table.getStoreManager().hasProperty(RDBMSPropertyNames.PROPERTY_RDBMS_MYSQL_ENGINETYPE))
        {
            engineType = table.getStoreManager().getStringProperty(RDBMSPropertyNames.PROPERTY_RDBMS_MYSQL_ENGINETYPE);
        }
        if (datastoreMajorVersion >= 5 ||
            (datastoreMajorVersion == 4 && datastoreMinorVersion >= 1 && datastoreRevisionVersion >= 2) ||
            (datastoreMajorVersion == 4 && datastoreMinorVersion == 0 && datastoreRevisionVersion >= 18))
        {
            // "ENGINE=" was introduced in 4.1.2 and 4.0.18 (http://dev.mysql.com/doc/refman/4.1/en/create-table.html)
            createStmt.append(" ENGINE=" + engineType);
        }
        else
        {
            createStmt.append(" TYPE=" + engineType);
        }

        // Check for specification of the "collation"
        String collation = null;
        if (props != null && props.contains("mysql-collation"))
        {
            collation = props.getProperty("mysql-collation");
        }
        else if (table.getStoreManager().hasProperty(RDBMSPropertyNames.PROPERTY_RDBMS_MYSQL_COLLATION))
        {
            collation = table.getStoreManager().getStringProperty(RDBMSPropertyNames.PROPERTY_RDBMS_MYSQL_COLLATION);
        }
        if (collation != null)
        {
            createStmt.append(" COLLATE=").append(collation);
        }

        // Check for specification of the "charset"
        String charset = null;
        if (props != null && props.contains("mysql-character-set"))
        {
            charset = props.getProperty("mysql-character-set");
        }
        else if (table.getStoreManager().hasProperty(RDBMSPropertyNames.PROPERTY_RDBMS_MYSQL_CHARACTERSET))
        {
            charset = table.getStoreManager().getStringProperty(RDBMSPropertyNames.PROPERTY_RDBMS_MYSQL_CHARACTERSET);
        }
        if (charset != null)
        {
            createStmt.append(" CHARACTER SET=").append(charset);
        }
        return createStmt.toString();
    }

    /**
     * Method to return the DROP TABLE statement.
     * @param table The table
     * @return The drop statement
     **/ 
    public String getDropTableStatement(Table table)
    {
        if (datastoreMajorVersion < 5)
        {
            // Earlier versions of MySQL didn't support the CASCADE keyword, whereas now it does but does nothing
            return "DROP TABLE " + table.toString();
        }
        return super.getDropTableStatement(table);
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
     * Method to return the basic SQL for a DELETE TABLE statement.
     * Returns the String as <code>DELETE t1 FROM tbl t1</code>. Doesn't include any where clause.
     * @param tbl The SQLTable to delete
     * @return The delete table string
     */
    public String getDeleteTableStatement(SQLTable tbl)
    {
        return "DELETE " + tbl.getAlias() + " FROM " + tbl.toString();
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.adapter.BaseDatastoreAdapter#getCreateIndexStatement(org.datanucleus.store.rdbms.key.Index, org.datanucleus.store.rdbms.identifier.IdentifierFactory)
     */
    @Override
    public String getCreateIndexStatement(Index idx, IdentifierFactory factory)
    {
        /**
        CREATE [UNIQUE|FULLTEXT|SPATIAL] INDEX index_name [USING {BTREE | HASH}]
            ON tableName (column [ASC|DESC], ...)
            [KEY_BLOCK_SIZE[=]value | index_type | WITH PARSER parser_name | COMMENT 'string']
            [ALGORITHM [=] {DEFAULT|INPLACE|COPY} | LOCK [=] {DEFAULT|NONE|SHARED|EXCLUSIVE}] ...
        */

        StringBuilder stringBuilder = new StringBuilder("CREATE ").append((idx.getUnique() ? "UNIQUE " : "")).append("INDEX ");
        stringBuilder.append(factory.newTableIdentifier(idx.getName()).getFullyQualifiedName(true));
        String indexType = idx.getValueForExtension(Index.EXTENSION_INDEX_TYPE);
        if (indexType != null)
        {
            stringBuilder.append(indexType.equalsIgnoreCase("BTREE") ? " USING BTREE" : indexType.equalsIgnoreCase("HASH") ? " USING HASH" : "");
        }
        stringBuilder.append(" ON ").append(idx.getTable().toString()).append(" ").append(idx.getColumnList(supportsOption(CREATE_INDEX_COLUMN_ORDERING)));

        String extendedSetting = idx.getValueForExtension(Index.EXTENSION_INDEX_EXTENDED_SETTING);
        if (extendedSetting != null)
        {
            stringBuilder.append(" ").append(extendedSetting);
        }

        return stringBuilder.toString();
    }

    /**
     * Accessor for the auto-increment sql statement for this datastore.
     * @param table Name of the table that the autoincrement is for
     * @param columnName Name of the column that the autoincrement is for
     * @return The statement for getting the latest auto-increment key
     **/
    public String getIdentityLastValueStmt(Table table, String columnName)
    {
        return "SELECT LAST_INSERT_ID()";
    }

    /**
     * Accessor for the auto-increment keyword for generating DDLs (CREATE TABLEs...).
     * @param storeMgr The Store Manager
     * @return The keyword for a column using auto-increment
     **/
    public String getIdentityKeyword(StoreManager storeMgr)
    {
        return "AUTO_INCREMENT";
    }

    /**
     * The function to creates a unique value of type uniqueidentifier.
     * MySQL generates 36-character hex uuids.
     * @return The function. e.g. "SELECT uuid()"
     **/
    public String getSelectNewUUIDStmt()
    {
        return "SELECT uuid()";
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
        if (offset >= 0 && count > 0)
        {
            return "LIMIT " + offset + "," + count + " ";
        }
        else if (offset <= 0 && count > 0)
        {
            return "LIMIT " + count + " ";
        }
        else if (offset >= 0 && count < 0)
        {
            // MySQL doesnt allow just offset so use Long.MAX_VALUE as count
            return "LIMIT " + offset + "," + Long.MAX_VALUE + " ";
        }
        else
        {
            return "";
        }
    }

    /**
     * The character for escaping patterns.
     * @return Escape character(s)
     **/
    public String getEscapePatternExpression()
    {
        return "ESCAPE '\\\\'";
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.adapter.BaseDatastoreAdapter#validToIndexMapping(org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping)
     */
    @Override
    public boolean validToIndexMapping(JavaTypeMapping mapping)
    {
        // TODO Improve this to omit BLOB/CLOB only
        if (mapping instanceof SerialisedMapping)
        {
            return false;
        }
        return super.validToIndexMapping(mapping);
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
        if (increment != null)
        {
            stmt.append(" INCREMENT BY " + increment);
        }
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
        if (cacheSize != null)
        {
            stmt.append(" CACHE " + cacheSize);
        }
        else
        {
            stmt.append(" NOCACHE");
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

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.adapter.BaseDatastoreAdapter#getSQLOperationClass(java.lang.String)
     */
    @Override
    public Class<? extends SQLOperation> getSQLOperationClass(String operationName)
    {
        if ("concat".equals(operationName)) return org.datanucleus.store.rdbms.sql.operation.Concat2Operation.class;
        else if ("numericToString".equals(operationName)) return org.datanucleus.store.rdbms.sql.operation.NumericToString2Operation.class;

        return super.getSQLOperationClass(operationName);
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.adapter.BaseDatastoreAdapter#getSQLMethodClass(java.lang.String, java.lang.String)
     */
    @Override
    public Class<? extends SQLMethod> getSQLMethodClass(String className, String methodName, ClassLoaderResolver clr)
    {
        if (className == null)
        {
            if ("DAY_OF_WEEK".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayOfWeekMethod3.class;
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
                if ("concat".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringConcat2Method.class;
                else if ("startsWith".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringStartsWith3Method.class;
                else if ("trim".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringTrim3Method.class;
                else if ("trimLeft".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringTrimLeft3Method.class;
                else if ("trimRight".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.StringTrimRight3Method.class;
            }
            if ("java.util.Date".equals(className) || (cls != null && java.util.Date.class.isAssignableFrom(cls)))
            {
                if ("getDayOfWeek".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayOfWeekMethod3.class;
            }
            if ("java.time.LocalDate".equals(className) && "getDayOfWeek".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayOfWeekMethod3.class;
            if ("java.time.LocalDateTime".equals(className) && "getDayOfWeek".equals(methodName)) return org.datanucleus.store.rdbms.sql.method.TemporalDayOfWeekMethod3.class;
        }

        return super.getSQLMethodClass(className, methodName, clr);
    }

    /**
     * Load all datastore mappings defined in the associated plugins.
     * We handle RDBMS datastore mappings so refer to rdbms-mapping-class, jdbc-type, sql-type in particular.
     * @param mgr the PluginManager
     * @param clr the ClassLoaderResolver
     */
    protected void loadColumnMappings(PluginManager mgr, ClassLoaderResolver clr)
    {
        // Load up built-in types for this datastore
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.BitColumnMapping.class, JDBCType.BIT, "BIT", true);
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.BooleanColumnMapping.class, JDBCType.BOOLEAN, "BOOLEAN", false);
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.BooleanColumnMapping.class, JDBCType.TINYINT, "TINYINT", false);
        registerColumnMapping(Boolean.class.getName(), org.datanucleus.store.rdbms.mapping.column.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerColumnMapping(Byte.class.getName(), org.datanucleus.store.rdbms.mapping.column.TinyIntColumnMapping.class, JDBCType.TINYINT, "TINYINT", true);
        registerColumnMapping(Byte.class.getName(), org.datanucleus.store.rdbms.mapping.column.SmallIntColumnMapping.class, JDBCType.SMALLINT, "SMALLINT", false);

        registerColumnMapping(Character.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", true);
        registerColumnMapping(Character.class.getName(), org.datanucleus.store.rdbms.mapping.column.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", false);

        registerColumnMapping(Double.class.getName(), org.datanucleus.store.rdbms.mapping.column.DoubleColumnMapping.class, JDBCType.DOUBLE, "DOUBLE", true);
        registerColumnMapping(Double.class.getName(), org.datanucleus.store.rdbms.mapping.column.DecimalColumnMapping.class, JDBCType.DECIMAL, "DECIMAL", false);

        registerColumnMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.column.FloatColumnMapping.class, JDBCType.FLOAT, "FLOAT", true);
        registerColumnMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.column.DoubleColumnMapping.class, JDBCType.DOUBLE, "DOUBLE", false);
        registerColumnMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.column.RealColumnMapping.class, JDBCType.REAL, "REAL", false);
        registerColumnMapping(Float.class.getName(), org.datanucleus.store.rdbms.mapping.column.DecimalColumnMapping.class, JDBCType.DECIMAL, "DECIMAL", false);

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
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.ClobColumnMapping.class, JDBCType.CLOB, "CLOB", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarcharColumnMapping.class, JDBCType.LONGVARCHAR, "LONGTEXT", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarcharColumnMapping.class, JDBCType.LONGVARCHAR, "MEDIUMTEXT", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarcharColumnMapping.class, JDBCType.LONGVARCHAR, "TEXT", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "LONGBLOB", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "MEDIUMBLOB", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.NVarcharColumnMapping.class, JDBCType.NVARCHAR, "NVARCHAR", false);
        registerColumnMapping(String.class.getName(), org.datanucleus.store.rdbms.mapping.column.NCharColumnMapping.class, JDBCType.NCHAR, "NCHAR", false);

        registerColumnMapping(BigDecimal.class.getName(), org.datanucleus.store.rdbms.mapping.column.DecimalColumnMapping.class, JDBCType.DECIMAL, "DECIMAL", true);
        registerColumnMapping(BigDecimal.class.getName(), org.datanucleus.store.rdbms.mapping.column.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);

        registerColumnMapping(BigInteger.class.getName(), org.datanucleus.store.rdbms.mapping.column.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", true);
        registerColumnMapping(BigInteger.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.DateColumnMapping.class, JDBCType.DATE, "DATE", true);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", false);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.sql.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIME", true);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", false);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.sql.Time.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", true);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.DateColumnMapping.class, JDBCType.DATE, "DATE", false);
        registerColumnMapping(java.sql.Timestamp.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIME", false);

        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimestampColumnMapping.class, JDBCType.TIMESTAMP, "TIMESTAMP", true);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.DateColumnMapping.class, JDBCType.DATE, "DATE", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.TimeColumnMapping.class, JDBCType.TIME, "TIME", false);
        registerColumnMapping(java.util.Date.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", false);

        registerColumnMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarBinaryColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);
        registerColumnMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
        registerColumnMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarBinaryColumnMapping.class, JDBCType.VARBINARY, "VARBINARY", false);
        registerColumnMapping(java.io.Serializable.class.getName(), org.datanucleus.store.rdbms.mapping.column.BinaryColumnMapping.class, JDBCType.BINARY, "BINARY", false);

        registerColumnMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.column.LongVarBinaryColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);
        registerColumnMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.column.BlobColumnMapping.class, JDBCType.BLOB, "BLOB", false);
        registerColumnMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.column.VarBinaryColumnMapping.class, JDBCType.VARBINARY, "VARBINARY", false);
        registerColumnMapping(byte[].class.getName(), org.datanucleus.store.rdbms.mapping.column.BinaryColumnMapping.class, JDBCType.BINARY, "BINARY", false);

        registerColumnMapping(java.io.File.class.getName(), org.datanucleus.store.rdbms.mapping.column.BinaryStreamColumnMapping.class, JDBCType.LONGVARBINARY, "LONGVARBINARY", true);

        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.BigIntColumnMapping.class, JDBCType.BIGINT, "BIGINT", true);
        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.IntegerColumnMapping.class, JDBCType.INTEGER, "INTEGER", false);
        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.NumericColumnMapping.class, JDBCType.NUMERIC, "NUMERIC", false);
        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.CharColumnMapping.class, JDBCType.CHAR, "CHAR", false);
        registerColumnMapping(DatastoreId.class.getName(), org.datanucleus.store.rdbms.mapping.column.VarCharColumnMapping.class, JDBCType.VARCHAR, "VARCHAR", false);

        super.loadColumnMappings(mgr, clr);
    }
}
