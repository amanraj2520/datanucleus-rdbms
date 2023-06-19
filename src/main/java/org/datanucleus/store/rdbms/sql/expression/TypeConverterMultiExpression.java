/**********************************************************************
Copyright (c) 2014 Andy Jefferson and others. All rights reserved.
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
package org.datanucleus.store.rdbms.sql.expression;

import java.util.List;

import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLTable;

/**
 * Expression representing a TypeConverterMultiMapping where we have a java type mapped to multiple columns.
 */
public class TypeConverterMultiExpression extends ObjectExpression
{
    public TypeConverterMultiExpression(SQLStatement stmt, SQLTable table, JavaTypeMapping mapping)
    {
        super(stmt, table, mapping);
    }

    @Override
    public SQLExpression invoke(String methodName, List<SQLExpression> args)
    {
        return stmt.getRDBMSManager().getSQLExpressionFactory().invokeMethod(stmt, mapping.getJavaType().getName(), methodName, this, args);
    }
}