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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusObjectNotFoundException;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.IdentityType;
import org.datanucleus.state.LockMode;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.query.StatementClassMapping;
import org.datanucleus.store.rdbms.query.StatementMappingIndex;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.rdbms.fieldmanager.ParameterSetter;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.expression.InExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.schema.table.SurrogateColumnType;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * Request to locate a record in the data store. Performs an SQL statement like
 * <pre>
 * SELECT 1 FROM CANDIDATE_TABLE WHERE ID = ?
 * </pre>
 * and checks if the ResultSet is empty
 */
public class LocateRequest extends Request
{
    /** JDBC locate statement without locking. */
    private String statementUnlocked;

    /** JDBC locate statement with locking. */
    private String statementLocked;

    /** Definition of mappings in the SQL statement. */
    private StatementClassMapping mappingDefinition;

    /**
     * Constructor, taking the table. Uses the structure of the datastore table to build a basic query.
     * @param table The Class Table representing the datastore table to retrieve
     * @param cmd Metadata for the class we are locating an instance of
     * @param clr ClassLoader resolver
     */
    public LocateRequest(DatastoreClass table, AbstractClassMetaData cmd, ClassLoaderResolver clr)
    {
        super(table);

        RDBMSStoreManager storeMgr = table.getStoreManager();
        SelectStatement sqlStatement = new SelectStatement(storeMgr, table, null, null);
        mappingDefinition = new StatementClassMapping();
        SQLExpressionFactory exprFactory = storeMgr.getSQLExpressionFactory();
        JavaTypeMapping m = storeMgr.getMappingManager().getMapping(Integer.class);
        sqlStatement.select(exprFactory.newLiteral(sqlStatement, m, 1), null);

        // Add WHERE clause restricting to the identity of an object
        int inputParamNum = 1;
        if (table.getIdentityType() == IdentityType.DATASTORE)
        {
            // Datastore identity value for input
            JavaTypeMapping datastoreIdMapping = table.getSurrogateMapping(SurrogateColumnType.DATASTORE_ID, false);
            SQLExpression expr = exprFactory.newExpression(sqlStatement, sqlStatement.getPrimaryTable(), datastoreIdMapping);
            SQLExpression val = exprFactory.newLiteralParameter(sqlStatement, datastoreIdMapping, null, "ID");
            sqlStatement.whereAnd(expr.eq(val), true);

            StatementMappingIndex datastoreIdx = mappingDefinition.getMappingForMemberPosition(SurrogateColumnType.DATASTORE_ID.getFieldNumber());
            if (datastoreIdx == null)
            {
                datastoreIdx = new StatementMappingIndex(datastoreIdMapping);
                mappingDefinition.addMappingForMember(SurrogateColumnType.DATASTORE_ID.getFieldNumber(), datastoreIdx);
            }
            datastoreIdx.addParameterOccurrence(new int[] {inputParamNum++});
        }
        else if (table.getIdentityType() == IdentityType.APPLICATION)
        {
            // Application identity value(s) for input
            int[] pkNums = cmd.getPKMemberPositions();
            for (int i=0;i<pkNums.length;i++)
            {
                AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(pkNums[i]);
                JavaTypeMapping pkMapping = table.getMemberMappingInDatastoreClass(mmd);
                if (pkMapping == null)
                {
                    pkMapping = table.getMemberMapping(mmd);
                }
                SQLExpression expr = exprFactory.newExpression(sqlStatement, sqlStatement.getPrimaryTable(), pkMapping);
                SQLExpression val = exprFactory.newLiteralParameter(sqlStatement, pkMapping, null, "PK" + i);
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
            
            String[] tenantReadIds = storeMgr.getNucleusContext().getTenantReadIds(null);
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
                SQLExpression tenantValParam = exprFactory.newLiteralParameter(sqlStatement, multitenancyMapping, null, "TENANT");
                sqlStatement.whereAnd(tenantExpr.eq(tenantValParam), true);

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
            sqlStatement.whereAnd(softDeleteExpr.eq(softDeleteValParam), true);

            StatementMappingIndex softDeleteIdx = mappingDefinition.getMappingForMemberPosition(SurrogateColumnType.SOFTDELETE.getFieldNumber());
            if (softDeleteIdx == null)
            {
                softDeleteIdx = new StatementMappingIndex(softDeleteMapping);
                mappingDefinition.addMappingForMember(SurrogateColumnType.SOFTDELETE.getFieldNumber(), softDeleteIdx);
            }
            softDeleteIdx.addParameterOccurrence(new int[] {inputParamNum++});
        }

        // Generate the unlocked and locked JDBC statements
        statementUnlocked = sqlStatement.getSQLText().toSQL();
        sqlStatement.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE, Boolean.TRUE);
        statementLocked = sqlStatement.getSQLText().toSQL();
    }

    /**
     * Method performing the retrieval of the record from the datastore. 
     * Takes the constructed retrieval query and populates with the specific record information.
     * @param sm StateManager for the record to be retrieved
     */
    public void execute(DNStateManager sm)
    {
        if (statementLocked != null)
        {
            ExecutionContext ec = sm.getExecutionContext();
            RDBMSStoreManager storeMgr = table.getStoreManager();

            // Override with pessimistic lock where specified
            LockMode lockType = ec.getLockManager().getLockMode(sm.getInternalObjectId());
            boolean locked = (lockType == LockMode.LOCK_PESSIMISTIC_READ || lockType == LockMode.LOCK_PESSIMISTIC_WRITE) ? 
                    true : ec.getSerializeReadForClass(sm.getClassMetaData().getFullClassName());

            String statement = (locked ? statementLocked : statementUnlocked);

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
                        // Provide the primary key field(s)
                        if (cmd.getIdentityType() == IdentityType.DATASTORE)
                        {
                            StatementMappingIndex datastoreIdx = mappingDefinition.getMappingForMemberPosition(SurrogateColumnType.DATASTORE_ID.getFieldNumber());
                            for (int i=0;i<datastoreIdx.getNumberOfParameterOccurrences();i++)
                            {
                                table.getSurrogateMapping(SurrogateColumnType.DATASTORE_ID, false).setObject(ec, ps, datastoreIdx.getParameterPositionsForOccurrence(i), sm.getInternalObjectId());
                            }
                        }
                        else if (cmd.getIdentityType() == IdentityType.APPLICATION)
                        {
                            sm.provideFields(cmd.getPKMemberPositions(), new ParameterSetter(sm, ps, mappingDefinition));
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
                                StatementMappingIndex multitenancyIdx = mappingDefinition.getMappingForMemberPosition(SurrogateColumnType.MULTITENANCY.getFieldNumber());
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
                            if (!rs.next())
                            {
                                String msg = Localiser.msg("050018", IdentityUtils.getPersistableIdentityForId(sm.getInternalObjectId()));
                                if (NucleusLogger.DATASTORE_RETRIEVE.isInfoEnabled())
                                {
                                    NucleusLogger.DATASTORE_RETRIEVE.info(msg);
                                }
                                throw new NucleusObjectNotFoundException(msg);
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
                String msg = Localiser.msg("052220", IdentityUtils.getPersistableIdentityForId(sm.getInternalObjectId()), statement, sqle.getMessage());
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
    }
}