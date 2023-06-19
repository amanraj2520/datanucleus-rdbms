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
package org.datanucleus.store.rdbms.sql.method;

import java.util.List;

import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.metadata.QueryLanguage;
import org.datanucleus.store.query.expression.Expression;
import org.datanucleus.store.rdbms.adapter.BaseDatastoreAdapter;
import org.datanucleus.store.rdbms.adapter.DatastoreAdapter;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLText;
import org.datanucleus.store.rdbms.sql.expression.BooleanExpression;
import org.datanucleus.store.rdbms.sql.expression.BooleanLiteral;
import org.datanucleus.store.rdbms.sql.expression.CharacterExpression;
import org.datanucleus.store.rdbms.sql.expression.CharacterLiteral;
import org.datanucleus.store.rdbms.sql.expression.ParameterLiteral;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.sql.expression.SQLLiteral;
import org.datanucleus.store.rdbms.sql.expression.StringExpression;
import org.datanucleus.store.rdbms.sql.expression.StringLiteral;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.RegularExpressionConverter;

/**
 * Expression handler to evaluate {stringExpression}.matches(StringExpression).
 * Returns a BooleanExpression using LIKE.
 */
public class StringMatchesMethod implements SQLMethod
{
    @Override
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        if (args == null || args.size() > 2)
        {
            throw new NucleusException("Incorrect arguments for String.matches(StringExpression)");
        }
        else if (!(args.get(0) instanceof StringExpression) &&
            !(args.get(0) instanceof ParameterLiteral))
        {
            throw new NucleusException("Incorrect arguments for String.matches(StringExpression)");
        }

        SQLExpression likeExpr = args.get(0);
        if (!(likeExpr instanceof StringExpression) &&
            !(likeExpr instanceof CharacterExpression) &&
            !(likeExpr instanceof ParameterLiteral))
        {
            throw new NucleusException(Localiser.msg("060003", "like/matches", "StringExpression", 0,
                "StringExpression/CharacterExpression/ParameterLiteral"));
        }
        SQLExpression escapeExpr = null;
        if (args.size() > 1)
        {
            escapeExpr = args.get(1);
        }

        if ((likeExpr instanceof StringLiteral || likeExpr instanceof ParameterLiteral) && likeExpr.isParameter())
        {
            // Argument as parameter needs translation to use SQL "LIKE" syntax, so has to be embedded as literal
            stmt.getQueryGenerator().useParameterExpressionAsLiteral((SQLLiteral) likeExpr);
        }

        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        if (expr instanceof StringLiteral && likeExpr instanceof StringLiteral)
        {
            // String.matches(String) so evaluate in-memory
            String primary = (String)((StringLiteral)expr).getValue();
            String pattern = (String)((StringLiteral)likeExpr).getValue();
            return new BooleanLiteral(stmt, exprFactory.getMappingForType(boolean.class, false), primary.matches(pattern));
        }
        else if (expr instanceof StringLiteral)
        {
            return getBooleanLikeExpression(stmt, expr, likeExpr, escapeExpr);
        }
        else if (expr instanceof StringExpression && likeExpr instanceof StringLiteral)
        {
            // Convert the pattern to use the regex constructs suitable for the datastore
            String pattern = (String)((StringLiteral)likeExpr).getValue();

            if (stmt.getQueryGenerator().getQueryLanguage().equals(QueryLanguage.JDOQL.name()))
            {
                // JDOQL input is in java.lang.String regular expression format, so convert to SQL like
                boolean caseSensitive = false;
                if (pattern.startsWith("(?i)"))
                {
                    caseSensitive = true;
                    pattern = pattern.substring(4);
                }
                DatastoreAdapter dba = stmt.getDatastoreAdapter();
                RegularExpressionConverter converter = new RegularExpressionConverter(
                    dba.getPatternExpressionZeroMoreCharacters().charAt(0),
                    dba.getPatternExpressionAnyCharacter().charAt(0),
                    dba.getEscapeCharacter().charAt(0));
                if (caseSensitive)
                {
                    SQLExpression patternExpr = exprFactory.newLiteral(stmt, likeExpr.getJavaTypeMapping(), converter.convert(pattern).toLowerCase());
                    return getBooleanLikeExpression(stmt, expr.invoke("toLowerCase", null), patternExpr, escapeExpr);
                }

                SQLExpression patternExpr = exprFactory.newLiteral(stmt, likeExpr.getJavaTypeMapping(), converter.convert(pattern));
                return getBooleanLikeExpression(stmt, expr, patternExpr, escapeExpr);
            }

            SQLExpression patternExpr = exprFactory.newLiteral(stmt, likeExpr.getJavaTypeMapping(), pattern);
            return getBooleanLikeExpression(stmt, expr, patternExpr, escapeExpr);
        }
        else if (expr instanceof StringExpression)
        {
            return getExpressionForStringExpressionInput(stmt, expr, likeExpr, escapeExpr);
        }
        else
        {
            throw new NucleusException(Localiser.msg("060001", "matches", expr));
        }
    }

    protected BooleanExpression getExpressionForStringExpressionInput(SQLStatement stmt, SQLExpression expr, SQLExpression regExpr, SQLExpression escapeExpr)
    {
        BooleanExpression likeExpr = getBooleanLikeExpression(stmt, expr, regExpr, escapeExpr);
        return likeExpr;
    }

    protected BooleanExpression getBooleanLikeExpression(SQLStatement stmt, SQLExpression expr, SQLExpression regExpr, SQLExpression escapeExpr)
    {
        if(stmt.getDatastoreAdapter().supportsOption(DatastoreAdapter.RAW_PREFIX_LIKE_STATEMENTS)) 
        {
            // for spanner escape of Like statements should be done by double backslash '\\' or we should use raw strings r''
            // we choose raw strings approach otherwise we have to change the escape logic in datanucleus-core
            // see https://cloud.google.com/spanner/docs/operators#comparison_operators
            regExpr.toSQLText().prepend("r");
        }
        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        BooleanExpression likeExpr = new BooleanExpression(stmt, exprFactory.getMappingForType(boolean.class, false));
        SQLText sql= likeExpr.toSQLText();
        sql.clearStatement();
        if (Expression.OP_LIKE.isHigherThanLeftSide(expr.getLowestOperator()))
        {
            sql.append("(").append(expr).append(")");
        }
        else
        {
            sql.append(expr);
        }

        sql.append(" LIKE ");

        if (Expression.OP_LIKE.isHigherThanRightSide(regExpr.getLowestOperator()))
        {
            sql.append("(").append(regExpr).append(")");
        }
        else
        {
            sql.append(regExpr);
        }

        BaseDatastoreAdapter dba = (BaseDatastoreAdapter) stmt.getRDBMSManager().getDatastoreAdapter();
        if (escapeExpr != null)
        {
            if (escapeExpr instanceof CharacterLiteral)
            {
                String chr = "" + ((CharacterLiteral)escapeExpr).getValue();
                if (chr.equals(dba.getEscapeCharacter()))
                {
                    // If the escape character specified matches the Java character then apply the known working ESCAPE
                    // This is because some datastore JDBC drivers require additional "\" characters to allow
                    // for Java usage
                    sql.append(dba.getEscapePatternExpression());
                }
                else
                {
                    sql.append(" ESCAPE " + escapeExpr);
                }
            }
            else
            {
                sql.append(" ESCAPE " + escapeExpr);
            }
        }
        else
        {
            sql.append(" " + dba.getEscapePatternExpression());
        }

        return likeExpr;
    }
}