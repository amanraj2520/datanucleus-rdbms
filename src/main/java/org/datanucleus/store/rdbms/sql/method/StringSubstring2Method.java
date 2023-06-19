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

import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.expression.ExpressionUtils;
import org.datanucleus.store.rdbms.sql.expression.NumericExpression;
import org.datanucleus.store.rdbms.sql.expression.ParameterLiteral;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.StringExpression;
import org.datanucleus.util.Localiser;

/**
 * Method for evaluating {strExpr}.substring(numExpr1 [,numExpr2]).
 * Returns a StrignExpression that equates to
 * <ul>
 * <li><pre>SUBSTRING(strExpr, numExpr1+1)</pre> when no end position provided.</li>
 * <li><pre>SUBSTRING(strExpr, numExpr1+1, numExpr2-numExpr1)</pre> when end position provided.</li>
 * </ul>
 */
public class StringSubstring2Method implements SQLMethod
{
    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.sql.method.SQLMethod#getExpression(org.datanucleus.store.rdbms.sql.expression.SQLExpression, java.util.List)
     */
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        if (args == null || args.size() == 0 || args.size() > 2)
        {
            throw new NucleusException(Localiser.msg("060003", "substring", "StringExpression", 0, "NumericExpression/IntegerLiteral/ParameterLiteral"));
        }
        else if (args.size() == 1)
        {
            // {stringExpr}.substring(numExpr1)
            SQLExpression startExpr = args.get(0);
            if (!(startExpr instanceof NumericExpression) && !(startExpr instanceof ParameterLiteral))
            {
                throw new NucleusException(Localiser.msg("060003", "substring", "StringExpression", 0, "NumericExpression/IntegerLiteral/ParameterLiteral"));
            }

            SQLExpression one = ExpressionUtils.getLiteralForOne(stmt);

            List<SQLExpression> funcArgs = List.of(expr, startExpr.add(one));
            return new StringExpression(stmt, stmt.getSQLExpressionFactory().getMappingForType(String.class), "SUBSTRING", funcArgs);
        }
        else
        {
            // {stringExpr}.substring(numExpr1, numExpr2)
            SQLExpression startExpr = args.get(0);
            if (!(startExpr instanceof NumericExpression))
            {
                throw new NucleusException(Localiser.msg("060003", "substring", "StringExpression", 0, "NumericExpression"));
            }
            SQLExpression endExpr = args.get(1);
            if (!(endExpr instanceof NumericExpression))
            {
                throw new NucleusException(Localiser.msg("060003", "substring", "StringExpression", 1, "NumericExpression"));
            }

            SQLExpression one = ExpressionUtils.getLiteralForOne(stmt);

            List<SQLExpression> funcArgs = List.of(expr, startExpr.add(one), endExpr.sub(startExpr));
            return new StringExpression(stmt, stmt.getSQLExpressionFactory().getMappingForType(String.class), "SUBSTRING", funcArgs);
        }
    }
}