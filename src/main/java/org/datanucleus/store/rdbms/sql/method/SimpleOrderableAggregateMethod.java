/**********************************************************************
Copyright (c) 2008 Andy Jefferson and others. All rights reserved.
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

import java.util.List;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.store.query.compiler.CompilationComponent;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.expression.AggregateNumericExpression;
import org.datanucleus.store.rdbms.sql.expression.AggregateStringExpression;
import org.datanucleus.store.rdbms.sql.expression.AggregateTemporalExpression;
import org.datanucleus.store.rdbms.sql.expression.NumericSubqueryExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.sql.expression.StringExpression;
import org.datanucleus.store.rdbms.sql.expression.StringLiteral;
import org.datanucleus.store.rdbms.sql.expression.StringSubqueryExpression;
import org.datanucleus.store.rdbms.sql.expression.TemporalExpression;
import org.datanucleus.store.rdbms.sql.expression.TemporalSubqueryExpression;
import org.datanucleus.util.Localiser;

/**
 * Expression handler to invoke an SQL aggregated function.
 * <ul>
 * <li>The expression should be null and will return an AggregateXXXExpression
 *     <pre>{functionName}({argExpr})</pre> when processing a result clause</li>
 * <li>If the compilation component is something else then will generate a subquery expression</li>
 * </ul>
 */
public abstract class SimpleOrderableAggregateMethod implements SQLMethod
{
    protected abstract String getFunctionName();

    @Override
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        if (expr != null)
        {
            throw new NucleusException(Localiser.msg("060002", getFunctionName(), expr));
        }
        else if (args == null || args.size() != 1)
        {
            throw new NucleusException(getFunctionName() + " is only supported with a single argument");
        }

        if (stmt.getQueryGenerator().getCompilationComponent() == CompilationComponent.RESULT ||
            stmt.getQueryGenerator().getCompilationComponent() == CompilationComponent.HAVING)
        {
            // FUNC(argExpr)
            // Use same java type as the argument
            SQLExpression argExpr = args.get(0);
            JavaTypeMapping m = argExpr.getJavaTypeMapping();
            if (argExpr instanceof TemporalExpression)
            {
                return new AggregateTemporalExpression(stmt, m, getFunctionName(), args);
            }
            else if (argExpr instanceof StringExpression)
            {
                return new AggregateStringExpression(stmt, m, getFunctionName(), args);
            }
            return new AggregateNumericExpression(stmt, m, getFunctionName(), args);
        }

        // Handle as Subquery "SELECT AVG(expr) FROM tbl"
        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        ClassLoaderResolver clr = stmt.getQueryGenerator().getClassLoaderResolver();
        SQLExpression argExpr = args.get(0);
        SelectStatement subStmt = new SelectStatement(stmt, stmt.getRDBMSManager(), argExpr.getSQLTable().getTable(), argExpr.getSQLTable().getAlias(), null);
        subStmt.setClassLoaderResolver(clr);

        JavaTypeMapping mapping = stmt.getRDBMSManager().getMappingManager().getMappingWithColumnMapping(String.class, false, false, clr);
        String aggregateString = getFunctionName() + "(" + argExpr.toSQLText() + ")";
        SQLExpression aggExpr = exprFactory.newLiteral(subStmt, mapping, aggregateString);
        ((StringLiteral)aggExpr).generateStatementWithoutQuotes();
        subStmt.select(aggExpr, null);

        JavaTypeMapping subqMapping = exprFactory.getMappingForType(Integer.class, false);
        SQLExpression subqExpr = null;
        if (argExpr instanceof TemporalExpression)
        {
            subqExpr = new TemporalSubqueryExpression(stmt, subStmt);
        }
        else if (argExpr instanceof StringExpression)
        {
            subqExpr = new StringSubqueryExpression(stmt, subStmt);
        }
        else
        {
            subqExpr = new NumericSubqueryExpression(stmt, subStmt);
        }
        subqExpr.setJavaTypeMapping(subqMapping);
        return subqExpr;
    }
}