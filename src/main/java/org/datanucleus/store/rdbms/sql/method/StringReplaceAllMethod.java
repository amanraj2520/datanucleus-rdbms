/**********************************************************************
Copyright (c) 2010 Andy Jefferson and others. All rights reserved.
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

import java.util.ArrayList;
import java.util.List;

import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.expression.CharacterExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.sql.expression.StringExpression;
import org.datanucleus.util.Localiser;

/**
 * Method for evaluating {strExpr}.replaceAll(strExpr1, strExpr2).
 * Returns a StringExpression that equates to
 * <pre>REPLACE(strExpr, strExp1, strExpr2)</pre>
 */
public class StringReplaceAllMethod implements SQLMethod
{
    @Override
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        if (args == null || args.size() != 2)
        {
            throw new NucleusException(Localiser.msg("060003", "replaceAll", "StringExpression", 2,
                "StringExpression/CharacterExpression"));
        }

        // {strExpr}.translate(strExpr1, strExpr2)
        SQLExpression strExpr1 = args.get(0);
        SQLExpression strExpr2 = args.get(1);
        if (!(strExpr1 instanceof StringExpression) &&
                !(strExpr1 instanceof CharacterExpression))
        {
            throw new NucleusException(Localiser.msg("060003", "replaceAll", "StringExpression", 1,
                    "StringExpression/CharacterExpression"));
        }
        if (!(strExpr2 instanceof StringExpression) &&
                !(strExpr2 instanceof CharacterExpression))
        {
            throw new NucleusException(Localiser.msg("060003", "replaceAll", "StringExpression", 2,
                    "StringExpression/CharacterExpression"));
        }

        // Invoke substring(startExpr, endExpr)
        List<SQLExpression> newArgs = new ArrayList<>(3);
        newArgs.add(expr);
        newArgs.add(strExpr1);
        newArgs.add(strExpr2);
        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        JavaTypeMapping mapping = exprFactory.getMappingForType(String.class, false);
        return new StringExpression(stmt, mapping, "replace", newArgs);
    }
}