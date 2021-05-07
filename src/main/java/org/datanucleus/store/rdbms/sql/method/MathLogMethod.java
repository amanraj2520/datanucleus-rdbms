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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.expression.ByteLiteral;
import org.datanucleus.store.rdbms.sql.expression.FloatingPointLiteral;
import org.datanucleus.store.rdbms.sql.expression.IllegalExpressionOperationException;
import org.datanucleus.store.rdbms.sql.expression.IntegerLiteral;
import org.datanucleus.store.rdbms.sql.expression.NullLiteral;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.sql.expression.SQLLiteral;

/**
 * Expression handler to evaluate Math.log({expression}).
 * Returns a NumericExpression.
 */
public class MathLogMethod implements SQLMethod
{
    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.sql.method.SQLMethod#getExpression(org.datanucleus.store.rdbms.sql.expression.SQLExpression, java.util.List)
     */
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression ignore, List<SQLExpression> args)
    {
        if (args == null || args.size() == 0)
        {
            throw new NucleusUserException("Cannot invoke Math.log without an argument");
        }

        SQLExpression expr = args.get(0);
        if (expr == null)
        {
            return new NullLiteral(stmt, null, null, null);
        }
        else if (expr instanceof SQLLiteral)
        {
            if (expr instanceof ByteLiteral)
            {
                int originalValue = ((BigInteger) ((ByteLiteral) expr).getValue()).intValue();
                BigInteger absValue = new BigInteger(String.valueOf(Math.log(originalValue)));
                return new ByteLiteral(stmt, expr.getJavaTypeMapping(), absValue, null);
            }
            else if (expr instanceof IntegerLiteral)
            {
                int originalValue = ((Number) ((IntegerLiteral) expr).getValue()).intValue();
                Double absValue = Double.valueOf(Math.log(originalValue));
                return new FloatingPointLiteral(stmt, expr.getJavaTypeMapping(), absValue, null);
            }
            else if (expr instanceof FloatingPointLiteral)
            {
                double originalValue = ((BigDecimal) ((FloatingPointLiteral) expr).getValue()).doubleValue();
                Double absValue = Double.valueOf(Math.log(originalValue));
                return new FloatingPointLiteral(stmt, expr.getJavaTypeMapping(), absValue, null);
            }
            throw new IllegalExpressionOperationException("Math.log()", expr);
        }
        else
        {
            // Relay to the equivalent "log(expr)" function
            SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
            return exprFactory.invokeMethod(stmt, null, "log", null, args);
        }
    }
}