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

import java.util.ArrayList;
import java.util.List;

import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.store.rdbms.adapter.DatastoreAdapter;
import org.datanucleus.store.rdbms.adapter.FirebirdAdapter;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.expression.IntegerLiteral;
import org.datanucleus.store.rdbms.sql.expression.NumericExpression;
import org.datanucleus.store.rdbms.sql.expression.ParameterLiteral;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.sql.expression.StringExpression;
import org.datanucleus.store.rdbms.sql.expression.StringLiteral;
import org.datanucleus.util.Localiser;

/**
 * Expression handler to evaluate {stringExpression}.length() with Firebird.
 * Firebird v1 uses STRLEN, whereas Firebird v2 has CHAR_LENGTH.
 * Returns a NumericExpression <pre>STRLEN({stringExpr})</pre> or NumericExpression <pre>CHAR_LENGTH({stringExpr})</pre>.
 */
public class StringLength2Method implements SQLMethod
{
    @Override
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        DatastoreAdapter dba = stmt.getDatastoreAdapter();
        if (!(dba instanceof FirebirdAdapter))
        {
            throw new NucleusException("StringLength2Method being used for evaluation of String.length yet this is for Firebird ONLY. Please report this");
        }
        FirebirdAdapter fba = (FirebirdAdapter)dba;
        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        if (fba.supportsCharLengthFunction())
        {
            // Firebird v2+ support CHAR_LENGTH
            if (!expr.isParameter() && expr instanceof StringLiteral)
            {
                JavaTypeMapping m = exprFactory.getMappingForType(int.class, false);
                String val = (String)((StringLiteral)expr).getValue();
                return new IntegerLiteral(stmt, m, Integer.valueOf(val.length()), null);
            }
            else if (expr instanceof StringExpression || expr instanceof ParameterLiteral)
            {
                ArrayList funcArgs = new ArrayList();
                funcArgs.add(expr);
                return new NumericExpression(stmt, stmt.getSQLExpressionFactory().getMappingForType(int.class), "CHAR_LENGTH", funcArgs);
            }
            else
            {
                throw new NucleusException(Localiser.msg("060001", "length", expr));
            }
        }

        if (expr instanceof StringLiteral)
        {
            // Firebird v1 requires STRLEN
            JavaTypeMapping m = exprFactory.getMappingForType(int.class, false);
            String val = (String)((StringLiteral)expr).getValue();
            return new IntegerLiteral(stmt, m, Integer.valueOf(val.length()), null);
        }
        else if (expr instanceof StringExpression || expr instanceof ParameterLiteral)
        {
            List<SQLExpression> funcArgs = new ArrayList<>();
            funcArgs.add(expr);
            return new NumericExpression(stmt, stmt.getSQLExpressionFactory().getMappingForType(int.class), "STRLEN", funcArgs);
        }
        else
        {
            throw new NucleusException(Localiser.msg("060001", "length", expr));
        }
    }
}