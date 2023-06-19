/**********************************************************************
Copyright (c) 2016 Andy Jefferson and others. All rights reserved.
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
package org.datanucleus.store.rdbms.sql.method;

import static java.util.Arrays.asList;

import java.util.Date;
import java.util.List;

import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.expression.NumericExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.sql.expression.StringLiteral;
import org.datanucleus.store.rdbms.sql.expression.TemporalExpression;

/**
 * Method for evaluating MINUTE({dateExpr}) using SQLServer.
 * Returns a NumericExpression that equates to <pre>DATEPART(mi, CAST(expr AS 'DATETIME'))</pre>
 */
public class TemporalMinuteMethod4 extends TemporalBaseMethod
{
    @Override
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        SQLExpression invokedExpr = getInvokedExpression(expr, args, "MINUTE");

        RDBMSStoreManager storeMgr = stmt.getRDBMSManager();
        JavaTypeMapping mapping = storeMgr.getMappingManager().getMapping(String.class);
        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        SQLExpression mi = exprFactory.newLiteral(stmt, mapping, "mi");
        ((StringLiteral)mi).generateStatementWithoutQuotes();

        // CAST {invokedExpr} AS DATETIME
        List<SQLExpression> castArgs = List.of(invokedExpr);

        List<SQLExpression> funcArgs = List.of(mi, new TemporalExpression(stmt, exprFactory.getMappingForType(Date.class, true), "CAST", castArgs, asList("DATETIME")));
        return new NumericExpression(stmt, exprFactory.getMappingForType(int.class, true), "DATEPART", funcArgs);
    }
}
