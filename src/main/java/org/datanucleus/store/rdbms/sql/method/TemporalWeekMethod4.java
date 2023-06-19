/**********************************************************************
Copyright (c) 2021 Andy Jefferson and others. All rights reserved.
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

import java.util.ArrayList;
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
 * Method for evaluating WEEK({dateExpr}) using SQLServer.
 * Returns a NumericExpression that equates to <pre>DATEPART(wk, CAST(expr AS 'DATETIME'))</pre>
 */
public class TemporalWeekMethod4 extends TemporalBaseMethod
{
    @Override
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        SQLExpression invokedExpr = getInvokedExpression(expr, args, "WEEK");

        RDBMSStoreManager storeMgr = stmt.getRDBMSManager();
        JavaTypeMapping mapping = storeMgr.getMappingManager().getMapping(String.class);
        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        SQLExpression wk = exprFactory.newLiteral(stmt, mapping, "wk");
        ((StringLiteral)wk).generateStatementWithoutQuotes();

        // CAST {invokedExpr} AS DATETIME
        List<SQLExpression> castArgs = List.of(invokedExpr);

        List<SQLExpression> funcArgs = new ArrayList<>();
        funcArgs.add(wk);
        funcArgs.add(new TemporalExpression(stmt, stmt.getSQLExpressionFactory().getMappingForType(Date.class), "CAST", castArgs, asList("DATETIME")));
        return new NumericExpression(stmt, stmt.getSQLExpressionFactory().getMappingForType(int.class), "DATEPART", funcArgs);
    }
}