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
package org.datanucleus.store.rdbms.request;

import java.lang.reflect.Modifier;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlan;
import org.datanucleus.FetchPlanForClass;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusObjectNotFoundException;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.IdentityType;
import org.datanucleus.metadata.VersionMetaData;
import org.datanucleus.state.LockMode;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.mapping.MappingCallbacks;
import org.datanucleus.store.rdbms.mapping.MappingHelper;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.mapping.java.PersistableMapping;
import org.datanucleus.store.rdbms.mapping.java.ReferenceMapping;
import org.datanucleus.store.rdbms.mapping.java.SingleCollectionMapping;
import org.datanucleus.store.rdbms.query.StatementClassMapping;
import org.datanucleus.store.rdbms.query.StatementMappingIndex;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.rdbms.fieldmanager.ParameterSetter;
import org.datanucleus.store.rdbms.fieldmanager.ResultSetGetter;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLStatementHelper;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.expression.InExpression;
import org.datanucleus.store.rdbms.sql.expression.ParameterLiteral;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.AbstractClassTable;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.schema.table.SurrogateColumnType;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * Class to retrieve the fields of an object of a specified class from the datastore.
 * If some of those fields are themselves persistent objects then this can optionally
 * retrieve fields of those objects in the same fetch.
 * <p>
 * Any surrogate version stored in this table will be fetched *if* the object being updated doesn't
 * already have a value for it. If the caller wants the surrogate version to be updated then
 * they should nullify the "transactional" version before calling.
 * </p>
 */
public class FetchRequest extends Request
{
    /** JDBC fetch statement without locking. */
    private String statementUnlocked;

    /** JDBC fetch statement with locking. */
    private String statementLocked;

    /** Absolute numbers of the fields/properties of the class to fetch. */
    private int[] memberNumbersToFetch = null;

    /** Absolute numbers of the members of the class to store the value for. */
    private int[] memberNumbersToStore = null;

    /** The mapping of the results of the SQL statement. */
    private StatementClassMapping mappingDefinition;

    /** Callbacks for postFetch() operations, to be called after the fetch itself (relation fields). */
    private final List<MappingCallbacks> mappingCallbacks;

    private int numberOfFieldsToFetch = 0;

    /** Convenience string listing the fields to be fetched by this request. */
    private final String fieldsToFetch;

    /** Whether we are fetching a surrogate version in this fetch. */
    private boolean fetchingSurrogateVersion = false;

    /** Name of the version field. Only applies if the class has a version field (not surrogate). */
    private String versionFieldName = null;

    /**
     * Constructor. Uses the structure of the datastore table to build a basic SELECT query.
     * @param classTable The Class Table representing the datastore table to retrieve
     * @param fpClass FetchPlan for class
     * @param clr ClassLoader resolver
     * @param cmd ClassMetaData of the candidate
     * @param mmds MetaData of the members to fetch
     * @param mmdsToStore MetaData of the members to store
     */
    public FetchRequest(DatastoreClass classTable, FetchPlanForClass fpClass, ClassLoaderResolver clr, AbstractClassMetaData cmd, 
            AbstractMemberMetaData[] mmds, AbstractMemberMetaData[] mmdsToStore)
    {
        super(classTable);

        // Work out the real candidate table.
        // Instead of just taking the most derived table as the candidate we find the table closest to the root table necessary to retrieve the requested fields
        boolean found = false;
        DatastoreClass candidateTable = classTable;
        if (mmds != null)
        {
            while (candidateTable != null)
            {
                for (int i=0;i<mmds.length;i++)
                {
                    JavaTypeMapping m = candidateTable.getMemberMappingInDatastoreClass(mmds[i]);
                    if (m != null)
                    {
                        found = true;
                        break;
                    }
                }
                if (found)
                {
                    break;
                }
                candidateTable = candidateTable.getSuperDatastoreClass();
            }
        }
        if (candidateTable == null)
        {
            candidateTable = classTable;
        }
        this.table = candidateTable;
        this.key = ((AbstractClassTable)table).getPrimaryKey();

        // Extract version information, from this table and any super-tables
        DatastoreClass currentTable = table;
        while (currentTable != null)
        {
            VersionMetaData currentVermd = currentTable.getVersionMetaData();
            if (currentVermd != null)
            {
                if (currentVermd.getMemberName() == null)
                {
                    // Surrogate version stored in this table
                    fetchingSurrogateVersion = true;
                }
                else
                {
                    // Version field
                    versionFieldName = currentVermd.getMemberName();
                }
            }

            currentTable = currentTable.getSuperDatastoreClass();
        }

        // Generate the statement for the requested members
        RDBMSStoreManager storeMgr = classTable.getStoreManager();
        SelectStatement sqlStatement = new SelectStatement(storeMgr, table, null, null);
        mappingDefinition = new StatementClassMapping();
        mappingCallbacks = new ArrayList<>();

        List<Integer> memberNumbersToStoreTmp = new ArrayList<>();
        numberOfFieldsToFetch = processMembersOfClass(sqlStatement, fpClass, mmds, mmdsToStore, table, sqlStatement.getPrimaryTable(), mappingDefinition, mappingCallbacks, clr,
            memberNumbersToStoreTmp);
        memberNumbersToFetch = mappingDefinition.getMemberNumbers();
        if (memberNumbersToStoreTmp.size() > 0)
        {
            // Remove memberNumbersToStoreTmp from memberNumbersToFetch so we have distinct lists
            int[] memberNumberTmp = new int[memberNumbersToFetch.length - memberNumbersToStoreTmp.size()];
            int j = 0;
            for (int i=0;i<memberNumbersToFetch.length;i++)
            {
                if (!memberNumbersToStoreTmp.contains(memberNumbersToFetch[i]))
                {
                    memberNumberTmp[j++] = memberNumbersToFetch[i];
                }
            }
            memberNumbersToFetch = memberNumberTmp;
            memberNumbersToStore = new int[memberNumbersToStoreTmp.size()];
            j = 0;
            for (Integer absNum : memberNumbersToStoreTmp)
            {
                memberNumbersToStore[j++] = absNum;
            }

            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug("FetchRequest fetch=" + StringUtils.intArrayToString(memberNumbersToFetch) + 
                    " store=" + StringUtils.intArrayToString(memberNumbersToStore));
            }
        }

        // TODO Can we skip the statement generation if we know there are no selectable fields?

        // Add WHERE clause restricting to an object of this type
        int inputParamNum = 1;
        SQLExpressionFactory exprFactory = storeMgr.getSQLExpressionFactory();
        if (cmd.getIdentityType() == IdentityType.DATASTORE)
        {
            // Datastore identity value for input
            JavaTypeMapping datastoreIdMapping = table.getSurrogateMapping(SurrogateColumnType.DATASTORE_ID, false);
            SQLExpression expr = exprFactory.newExpression(sqlStatement, sqlStatement.getPrimaryTable(), datastoreIdMapping);
            SQLExpression val = exprFactory.newLiteralParameter(sqlStatement, datastoreIdMapping, null, "ID");
            if (val instanceof ParameterLiteral)
            {
                val = exprFactory.replaceParameterLiteral((ParameterLiteral)val, expr);
            }
            sqlStatement.whereAnd(expr.eq(val), true);

            StatementMappingIndex datastoreIdx = mappingDefinition.getMappingForMemberPosition(SurrogateColumnType.DATASTORE_ID.getFieldNumber());
            if (datastoreIdx == null)
            {
                datastoreIdx = new StatementMappingIndex(datastoreIdMapping);
                mappingDefinition.addMappingForMember(SurrogateColumnType.DATASTORE_ID.getFieldNumber(), datastoreIdx);
            }
            datastoreIdx.addParameterOccurrence(new int[] {inputParamNum++});
        }
        else if (cmd.getIdentityType() == IdentityType.APPLICATION)
        {
            // Application identity value(s) for input
            int[] pkNums = cmd.getPKMemberPositions();
            for (int i=0;i<pkNums.length;i++)
            {
                AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(pkNums[i]);
                JavaTypeMapping pkMapping = table.getMemberMapping(mmd);
                SQLExpression expr = exprFactory.newExpression(sqlStatement, sqlStatement.getPrimaryTable(), pkMapping);
                SQLExpression val = exprFactory.newLiteralParameter(sqlStatement, pkMapping, null, "PK" + i);
                if (val instanceof ParameterLiteral)
                {
                    val = exprFactory.replaceParameterLiteral((ParameterLiteral)val, expr);
                }
                sqlStatement.whereAnd(expr.eq(val), true);

                StatementMappingIndex pkIdx = mappingDefinition.getMappingForMemberPosition(pkNums[i]);
                if (pkIdx == null)
                {
                    pkIdx = new StatementMappingIndex(pkMapping);
                    mappingDefinition.addMappingForMember(pkNums[i], pkIdx);
                }
                int[] inputParams = new int[pkMapping.getNumberOfColumnMappings()];
                for (int j=0;j<pkMapping.getNumberOfColumnMappings();j++)
                {
                    inputParams[j] = inputParamNum++;
                }
                pkIdx.addParameterOccurrence(inputParams);
            }
        }

        JavaTypeMapping multitenancyMapping = table.getSurrogateMapping(SurrogateColumnType.MULTITENANCY, false);
        if (multitenancyMapping != null)
        {
            // Add WHERE clause for multi-tenancy
            SQLExpression tenantExpr = exprFactory.newExpression(sqlStatement, sqlStatement.getPrimaryTable(), multitenancyMapping);

            String[] tenantReadIds = storeMgr.getNucleusContext().getTenantReadIds(null); // We have no execution context!
            if (tenantReadIds != null && tenantReadIds.length > 0)
            {
                // Hardcode the IN clause with values
                SQLExpression[] readIdExprs = new SQLExpression[tenantReadIds.length];
                for (int i=0;i<tenantReadIds.length;i++)
                {
                    readIdExprs[i] = sqlStatement.getSQLExpressionFactory().newLiteral(sqlStatement, multitenancyMapping, tenantReadIds[i].trim());
                }
                sqlStatement.whereAnd(new InExpression(tenantExpr, readIdExprs), true);
            }
            else
            {
                // Add EQ expression with parameter for tenantId
                SQLExpression tenantVal = exprFactory.newLiteralParameter(sqlStatement, multitenancyMapping, null, "TENANT");
                if (tenantVal instanceof ParameterLiteral)
                {
                    tenantVal = exprFactory.replaceParameterLiteral((ParameterLiteral)tenantVal, tenantExpr);
                }
                sqlStatement.whereAnd(tenantExpr.eq(tenantVal), true);

                StatementMappingIndex multitenancyIdx = mappingDefinition.getMappingForMemberPosition(SurrogateColumnType.MULTITENANCY.getFieldNumber());
                if (multitenancyIdx == null)
                {
                    multitenancyIdx = new StatementMappingIndex(multitenancyMapping);
                    mappingDefinition.addMappingForMember(SurrogateColumnType.MULTITENANCY.getFieldNumber(), multitenancyIdx);
                }
                multitenancyIdx.addParameterOccurrence(new int[] {inputParamNum++});
            }
        }

        JavaTypeMapping softDeleteMapping = table.getSurrogateMapping(SurrogateColumnType.SOFTDELETE, false);
        if (softDeleteMapping != null)
        {
            // Add restriction on soft-delete
            SQLExpression softDeleteExpr = exprFactory.newExpression(sqlStatement, sqlStatement.getPrimaryTable(), softDeleteMapping);
            SQLExpression softDeleteValParam = exprFactory.newLiteralParameter(sqlStatement, softDeleteMapping, null, "SOFTDELETE");
            if (softDeleteValParam instanceof ParameterLiteral)
            {
                softDeleteValParam = exprFactory.replaceParameterLiteral((ParameterLiteral)softDeleteValParam, softDeleteExpr);
            }
            sqlStatement.whereAnd(softDeleteExpr.eq(softDeleteValParam), true);

            StatementMappingIndex softDeleteIdx = mappingDefinition.getMappingForMemberPosition(SurrogateColumnType.SOFTDELETE.getFieldNumber());
            if (softDeleteIdx == null)
            {
                softDeleteIdx = new StatementMappingIndex(softDeleteMapping);
                mappingDefinition.addMappingForMember(SurrogateColumnType.SOFTDELETE.getFieldNumber(), softDeleteIdx);
            }
            softDeleteIdx.addParameterOccurrence(new int[] {inputParamNum++});
        }

        // Generate convenience string for logging
        StringBuilder str = new StringBuilder();
        if (mmds != null)
        {
            for (int i=0;i<mmds.length;i++)
            {
                if (!mmds[i].isPrimaryKey())
                {
                    if (str.length() > 0)
                    {
                        str.append(',');
                    }
                    str.append(mmds[i].getName());
                }
            }
        }
        if (fetchingSurrogateVersion)
        {
            // Add on surrogate version column
            if (str.length() > 0)
            {
                str.append(",");
            }
            str.append("[VERSION]");
        }

        if (!fetchingSurrogateVersion && numberOfFieldsToFetch == 0)
        {
            fieldsToFetch = null;
            sqlStatement = null;
            mappingDefinition = null;
        }
        else
        {
            fieldsToFetch = str.toString();

            // Generate the unlocked and locked JDBC statements
            statementUnlocked = sqlStatement.getSQLText().toSQL();
            sqlStatement.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE, Boolean.TRUE);
            statementLocked = sqlStatement.getSQLText().toSQL();
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.request.Request#execute(org.datanucleus.state.DNStateManager)
     */
    public void execute(DNStateManager sm)
    {
        if (fieldsToFetch != null && NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            // Debug information about what we are retrieving
            NucleusLogger.PERSISTENCE.debug(Localiser.msg("052218", IdentityUtils.getPersistableIdentityForId(sm.getInternalObjectId()), fieldsToFetch, table));
        }

        if (((fetchingSurrogateVersion || versionFieldName != null) && numberOfFieldsToFetch == 0) && sm.isVersionLoaded())
        {
            // Fetching only the version and it is already loaded, so do nothing
        }
        else if (statementLocked != null)
        {
            ExecutionContext ec = sm.getExecutionContext();
            RDBMSStoreManager storeMgr = table.getStoreManager();

            // Override with pessimistic lock where specified
            LockMode lockType = ec.getLockManager().getLockMode(sm.getInternalObjectId());
            boolean locked = (lockType == LockMode.LOCK_PESSIMISTIC_READ || lockType == LockMode.LOCK_PESSIMISTIC_WRITE) ? 
                    true : ec.getSerializeReadForClass(sm.getClassMetaData().getFullClassName());

            String statement = (locked ? statementLocked : statementUnlocked);

            StatementClassMapping mappingDef = mappingDefinition;
          /*if ((sm.isDeleting() || sm.isDetaching()) && mappingDefinition.hasChildMappingDefinitions())
            {
                // Don't fetch any children since the object is being deleted
                mappingDef = mappingDefinition.cloneStatementMappingWithoutChildren();
            }*/
            try
            {
                ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
                SQLController sqlControl = storeMgr.getSQLController();

                try
                {
                    PreparedStatement ps = sqlControl.getStatementForQuery(mconn, statement);

                    AbstractClassMetaData cmd = sm.getClassMetaData();
                    try
                    {
                        // Provide the primary key field(s) to the JDBC statement
                        if (cmd.getIdentityType() == IdentityType.DATASTORE)
                        {
                            StatementMappingIndex datastoreIdx = mappingDef.getMappingForMemberPosition(SurrogateColumnType.DATASTORE_ID.getFieldNumber());
                            for (int i=0;i<datastoreIdx.getNumberOfParameterOccurrences();i++)
                            {
                                table.getSurrogateMapping(SurrogateColumnType.DATASTORE_ID, false).setObject(ec, ps, datastoreIdx.getParameterPositionsForOccurrence(i), sm.getInternalObjectId());
                            }
                        }
                        else if (cmd.getIdentityType() == IdentityType.APPLICATION)
                        {
                            sm.provideFields(cmd.getPKMemberPositions(), new ParameterSetter(sm, ps, mappingDef));
                        }

                        JavaTypeMapping multitenancyMapping = table.getSurrogateMapping(SurrogateColumnType.MULTITENANCY, false);
                        if (multitenancyMapping != null)
                        {
                            String[] tenantReadIds = storeMgr.getNucleusContext().getTenantReadIds(sm.getExecutionContext());
                            if (tenantReadIds != null && tenantReadIds.length > 0)
                            {
                                // Using IN clause so nothing to do since hardcoded
                            }
                            else
                            {
                                // Set MultiTenancy tenant id in statement
                                StatementMappingIndex multitenancyIdx = mappingDef.getMappingForMemberPosition(SurrogateColumnType.MULTITENANCY.getFieldNumber());
                                String tenantId = ec.getTenantId();
                                for (int i=0;i<multitenancyIdx.getNumberOfParameterOccurrences();i++)
                                {
                                    multitenancyMapping.setObject(ec, ps, multitenancyIdx.getParameterPositionsForOccurrence(i), tenantId);
                                }
                            }
                        }

                        JavaTypeMapping softDeleteMapping = table.getSurrogateMapping(SurrogateColumnType.SOFTDELETE, false);
                        if (softDeleteMapping != null)
                        {
                            // Set SoftDelete parameter in statement
                            StatementMappingIndex softDeleteIdx = mappingDefinition.getMappingForMemberPosition(SurrogateColumnType.SOFTDELETE.getFieldNumber());
                            for (int i=0;i<softDeleteIdx.getNumberOfParameterOccurrences();i++)
                            {
                                softDeleteMapping.setObject(ec, ps, softDeleteIdx.getParameterPositionsForOccurrence(i), Boolean.FALSE);
                            }
                        }

                        // Execute the statement
                        ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, statement, ps);
                        try
                        {
                            // Check for failure to find the object
                            if (!rs.next())
                            {
                                String msg = Localiser.msg("050018", IdentityUtils.getPersistableIdentityForId(sm.getInternalObjectId()));
                                if (NucleusLogger.DATASTORE_RETRIEVE.isInfoEnabled())
                                {
                                    NucleusLogger.DATASTORE_RETRIEVE.info(msg);
                                }
                                throw new NucleusObjectNotFoundException(msg);
                            }

                            // Copy the results into the object
                            ResultSetGetter rsGetter = new ResultSetGetter(ec, rs, mappingDef, sm.getClassMetaData());
                            rsGetter.setStateManager(sm);

                            // Make sure the version is set first
                            if (sm.getTransactionalVersion() == null)
                            {
                                // Object has no version set so update it from this fetch
                                Object datastoreVersion = null;
                                if (fetchingSurrogateVersion)
                                {
                                    // Surrogate version column - get from the result set using the version mapping
                                    StatementMappingIndex verIdx = mappingDef.getMappingForMemberPosition(SurrogateColumnType.VERSION.getFieldNumber());
                                    datastoreVersion = table.getSurrogateMapping(SurrogateColumnType.VERSION, true).getObject(ec, rs, verIdx.getColumnPositions());
                                }
                                else if (versionFieldName != null)
                                {
                                    // Version field - populate it in the object and access it from the field
                                    int verAbsFieldNum = cmd.getAbsolutePositionOfMember(versionFieldName);
                                    sm.replaceFields(new int[] {verAbsFieldNum}, rsGetter);
                                    datastoreVersion = sm.provideField(verAbsFieldNum);
                                }
                                sm.setVersion(datastoreVersion);
                            }

                            // Fetch requested fields
                            sm.replaceFields(memberNumbersToFetch, rsGetter);

                            // Store requested fields
                            if (memberNumbersToStore != null)
                            {
                                for (int i=0;i<memberNumbersToStore.length;i++)
                                {
                                    StatementMappingIndex mapIdx = mappingDefinition.getMappingForMemberPosition(memberNumbersToStore[i]);
                                    JavaTypeMapping m = mapIdx.getMapping();
                                    if (m instanceof PersistableMapping)
                                    {
                                        // FK, so create the identity of the related object
                                        AbstractClassMetaData memberCmd = ((PersistableMapping)m).getClassMetaData();

                                        Object memberId = null;
                                        if (memberCmd.getIdentityType() == IdentityType.DATASTORE)
                                        {
                                            memberId = MappingHelper.getDatastoreIdentityForResultSetRow(ec, m, rs, mapIdx.getColumnPositions(), memberCmd);
                                        }
                                        else if (memberCmd.getIdentityType() == IdentityType.APPLICATION)
                                        {
                                            memberId = MappingHelper.getApplicationIdentityForResultSetRow(ec, m, rs, mapIdx.getColumnPositions(), memberCmd);
                                        }
                                        else
                                        {
                                            break;
                                        }

                                        if (memberId == null)
                                        {
                                            // Just set the member and don't bother saving the value
                                            sm.replaceField(memberNumbersToStore[i], null);
                                        }
                                        else
                                        {
                                            // Store the "id" value in case the member is ever accessed
                                            sm.storeFieldValue(memberNumbersToStore[i], memberId);
                                        }
                                    }
                                }
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
            catch (SQLException sqle)
            {
                String msg = Localiser.msg("052219", IdentityUtils.getPersistableIdentityForId(sm.getInternalObjectId()), statement, sqle.getMessage());
                NucleusLogger.DATASTORE_RETRIEVE.warn(msg);
                List<Throwable> exceptions = new ArrayList<>();
                exceptions.add(sqle);
                while ((sqle = sqle.getNextException()) != null)
                {
                    exceptions.add(sqle);
                }
                throw new NucleusDataStoreException(msg, exceptions.toArray(new Throwable[exceptions.size()]));
            }
        }

        // Execute any mapping actions now that we have fetched the fields
        if (mappingCallbacks != null)
        {
            for (MappingCallbacks m : mappingCallbacks)
            {
                m.postFetch(sm);
            }
        }
    }

    /**
     * Method to process the supplied members of the class, adding to the SQLStatement as required.
     * Can recurse if some of the requested fields are persistent objects in their own right, so we take the opportunity to retrieve some of their fields.
     * @param sqlStatement Statement being built
     * @param fpClass FetchPlan for class
     * @param mmds MetaData for the members to fetch
     * @param mmdsToStore MetaData for the members to store
     * @param table The table to look for member mappings
     * @param sqlTbl The table in the SQL statement to use for selects
     * @param mappingDef Mapping definition for the result
     * @param fetchCallbacks Any additional required callbacks are added here
     * @param clr ClassLoader resolver
     * @param memberNumbersToStore Numbers of the members that need storing (populated by this call)
     * @return Number of members being selected in the statement
     */
    protected int processMembersOfClass(SelectStatement sqlStatement, FetchPlanForClass fpClass, AbstractMemberMetaData[] mmds, AbstractMemberMetaData[] mmdsToStore, 
            DatastoreClass table, SQLTable sqlTbl, StatementClassMapping mappingDef, Collection<MappingCallbacks> fetchCallbacks, ClassLoaderResolver clr, 
            List<Integer> memberNumbersToStore)
    {
        int number = 0;
        if (mmds != null)
        {
            // Process the members to fetch
            for (int i=0;i<mmds.length;i++)
            {
                if (processMemberToFetch(mmds[i], fpClass, clr, fetchCallbacks, sqlStatement, sqlTbl, mappingDef, memberNumbersToStore))
                {
                    number++;
                }
            }
        }

        JavaTypeMapping versionMapping = table.getSurrogateMapping(SurrogateColumnType.VERSION, true);
        if (versionMapping != null)
        {
            // Select version
            StatementMappingIndex verMapIdx = new StatementMappingIndex(versionMapping);
            SQLTable verSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStatement, sqlTbl, versionMapping);
            int[] cols = sqlStatement.select(verSqlTbl, versionMapping, null);
            verMapIdx.setColumnPositions(cols);
            mappingDef.addMappingForMember(SurrogateColumnType.VERSION.getFieldNumber(), verMapIdx);
        }

        if (mmdsToStore != null)
        {
            // Process the members to store
            for (int i=0;i<mmdsToStore.length;i++)
            {
                if (processMemberToStore(mmdsToStore[i], fpClass, clr, fetchCallbacks, sqlStatement, sqlTbl, mappingDef, memberNumbersToStore))
                {
                    number++;
                }
            }
        }

        return number;
    }

    /**
     * Method to process the specified member.
     * @param mmd MetaData for the member
     * @param fpClass FetchPlan for class
     * @param clr ClassLoader resolver
     * @param fetchCallbacks Any fetch callbacks to register the mapping with
     * @param sqlStmt The SELECT statement
     * @param sqlTbl The SQL table (of the candidate)
     * @param mappingDef The mapping definition for this statement
     * @param store Whether we are just selecting the value of this member for storing (rather than fetching)
     * @param memberNumbersToStore List of member numbers that are approved to be stored (any fetched ones will be added if recursionDepth=0)
     * @return Whether this member is selected in the statement
     */
    boolean processMemberToFetch(AbstractMemberMetaData mmd, FetchPlanForClass fpClass, ClassLoaderResolver clr, Collection<MappingCallbacks> fetchCallbacks,
            SelectStatement sqlStmt, SQLTable sqlTbl, StatementClassMapping mappingDef, List<Integer> memberNumbersToStore)
    {
        boolean selected = false;
        JavaTypeMapping mapping = table.getMemberMapping(mmd);
        if (mapping != null)
        {
            if (!mmd.isPrimaryKey() && mapping.includeInFetchStatement())
            {
                // The depth is the number of levels down to load in this statement : 0 is to load just this objects fields
                int depth = 0;

                AbstractMemberMetaData mmdToUse = mmd;
                JavaTypeMapping mappingToUse = mapping;
                if (mapping instanceof SingleCollectionMapping)
                {
                    // Check the wrapped type
                    mappingToUse = ((SingleCollectionMapping) mapping).getWrappedMapping();
                    mmdToUse = ((SingleCollectionMapping) mapping).getWrappedMapping().getMemberMetaData();
                }

                boolean fetchAndSaveFK = false;
                if (mappingToUse instanceof PersistableMapping)
                {
                    // Special cases : 1-1/N-1 (FK)
                    int recDepth = fpClass.getRecursionDepthForMember(mmd.getAbsoluteFieldNumber());
                    if (recDepth == FetchPlan.RECURSION_DEPTH_FK_ONLY)
                    {
                        // recursion-depth set as 0 (just retrieve the FK and don't instantiate the related object in the field)
                        depth = 0;
                        fetchAndSaveFK = true;
                    }
                    else
                    {
                        // Special case of 1-1/N-1 where we know the other side type so know what to join to and can load the related object
                        depth = 1;
                        if (Modifier.isAbstract(mmdToUse.getType().getModifiers()))
                        {
                            String typeName = mmdToUse.getTypeName();
                            DatastoreClass relTable = table.getStoreManager().getDatastoreClass(typeName, clr);
                            if (relTable != null && relTable.getSurrogateMapping(SurrogateColumnType.DISCRIMINATOR, false) == null)
                            {
                                // 1-1 relation to base class with no discriminator and has subclasses
                                // hence no way of determining the exact type, hence no point in fetching it
                                String[] subclasses = table.getStoreManager().getMetaDataManager().getSubclassesForClass(typeName, false);
                                if (subclasses != null && subclasses.length > 0)
                                {
                                    depth = 0;
                                }
                            }
                        }
                    }
                }
                else if (mappingToUse instanceof ReferenceMapping)
                {
                    ReferenceMapping refMapping = (ReferenceMapping)mappingToUse;
                    if (refMapping.getMappingStrategy() == ReferenceMapping.PER_IMPLEMENTATION_MAPPING)
                    {
                        JavaTypeMapping[] subMappings = refMapping.getJavaTypeMapping();
                        if (subMappings != null && subMappings.length == 1)
                        {
                            // Support special case of reference mapping with single implementation possible
                            depth = 1;
                        }
                    }
                }

                // TODO We should use the actual FetchPlan, and the max fetch depth, so then it can pull in all related objects within reach.
                // But this will mean we cannot cache the statement, since it is for a specific ExecutionContext
                // TODO If this field is a 1-1 and the other side has a discriminator or version then we really ought to fetch it
                SQLStatementHelper.selectMemberOfSourceInStatement(sqlStmt, mappingDef, null, sqlTbl, mmd, clr, depth, null);
                if (fetchAndSaveFK)
                {
                    memberNumbersToStore.add(mmd.getAbsoluteFieldNumber());
                }
                selected = true;
            }

            if (mapping instanceof MappingCallbacks)
            {
                // TODO Need to add that this mapping is for base object or base.field1, etc
                fetchCallbacks.add((MappingCallbacks) mapping);
            }
        }
        return selected;
    }

    /**
     * Method to process the specified member.
     * @param mmd MetaData for the member
     * @param fpClass FetchPlan for class
     * @param clr ClassLoader resolver
     * @param fetchCallbacks Any fetch callbacks to register the mapping with
     * @param sqlStmt The SELECT statement
     * @param sqlTbl The SQL table (of the candidate)
     * @param mappingDef The mapping definition for this statement
     * @param memberNumbersToStore List of member numbers that are approved to be stored
     * @return Whether this member is selected in the statement
     */
    boolean processMemberToStore(AbstractMemberMetaData mmd, FetchPlanForClass fpClass, ClassLoaderResolver clr, Collection<MappingCallbacks> fetchCallbacks,
            SelectStatement sqlStmt, SQLTable sqlTbl, StatementClassMapping mappingDef, List<Integer> memberNumbersToStore)
    {
        boolean selected = false;
        JavaTypeMapping mapping = table.getMemberMapping(mmd);
        if (mapping != null)
        {
            if (!mmd.isPrimaryKey() && mapping.includeInFetchStatement())
            {
                AbstractMemberMetaData mmdToUse = mmd;
                JavaTypeMapping mappingToUse = mapping;
                if (mapping instanceof SingleCollectionMapping)
                {
                    // Check the wrapped type
                    mappingToUse = ((SingleCollectionMapping) mapping).getWrappedMapping();
                    mmdToUse = ((SingleCollectionMapping) mapping).getWrappedMapping().getMemberMetaData();
                }

                if (mappingToUse instanceof PersistableMapping)
                {
                    // TODO Omit members that are compound identity
                    SQLStatementHelper.selectMemberOfSourceInStatement(sqlStmt, mappingDef, null, sqlTbl, mmdToUse, clr, 0, null);
                    memberNumbersToStore.add(mmd.getAbsoluteFieldNumber());
                    selected = true;
                }
            }

            if (mapping instanceof MappingCallbacks)
            {
                // TODO Need to add that this mapping is for base object or base.field1, etc
                fetchCallbacks.add((MappingCallbacks) mapping);
            }
        }
        return selected;
    }
}