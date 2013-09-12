/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.compile;

import java.sql.SQLException;
import java.util.*;

import org.apache.hadoop.hbase.client.Scan;

import com.google.common.collect.*;
import com.salesforce.phoenix.compile.GroupByCompiler.GroupBy;
import com.salesforce.phoenix.coprocessor.GroupedAggregateRegionObserver;
import com.salesforce.phoenix.expression.CoerceExpression;
import com.salesforce.phoenix.expression.Expression;
import com.salesforce.phoenix.expression.aggregator.ClientAggregators;
import com.salesforce.phoenix.expression.aggregator.ServerAggregators;
import com.salesforce.phoenix.expression.function.SingleAggregateFunction;
import com.salesforce.phoenix.expression.visitor.SingleAggregateFunctionVisitor;
import com.salesforce.phoenix.parse.*;
import com.salesforce.phoenix.schema.*;
import com.salesforce.phoenix.util.SizedUtil;


/**
 * 
 * Class that iterates through expressions in SELECT clause and adds projected
 * columns to scan.
 *
 * @author jtaylor
 * @since 0.1
 */
public class ProjectionCompiler {
    
    private ProjectionCompiler() {
    }
    
    private static void projectAllColumnFamilies(PTable table, Scan scan) {
        // Will project all known/declared column families
        scan.getFamilyMap().clear();
        for (PColumnFamily family : table.getColumnFamilies()) {
            scan.addFamily(family.getName().getBytes());
        }
    }

    private static void projectColumnFamily(PTable table, Scan scan, byte[] family) {
        // Will project all colmuns for given CF
        scan.addFamily(family);
    }
    
    public static Map<String, ParseNode> buildAliasMap(StatementContext context, SelectStatement statement) {
        List<AliasedNode> aliasedNodes = statement.getSelect();
        Map<String, ParseNode> aliasParseNodeMap = Maps.newHashMapWithExpectedSize(aliasedNodes.size());
        for (AliasedNode aliasedNode : aliasedNodes) {
            String alias = aliasedNode.getAlias();
            if (alias != null) {
                aliasParseNodeMap.put(alias, aliasedNode.getNode());
            }
        }
        return aliasParseNodeMap;
    }
    
    public static RowProjector compile(StatementContext context, SelectStatement statement, GroupBy groupBy) throws SQLException  {
        return compile(context, statement, groupBy, null);
    }
    
    /**
     * Builds the projection for the scan
     * @param context query context kept between compilation of different query clauses
     * @param statement TODO
     * @param groupBy compiled GROUP BY clause
     * @param targetColumns list of columns, parallel to aliasedNodes, that are being set for an
     * UPSERT SELECT statement. Used to coerce expression types to the expected target type.
     * @return projector used to access row values during scan
     * @throws SQLException 
     */
    public static RowProjector compile(StatementContext context, SelectStatement statement, GroupBy groupBy, PColumn[] targetColumns) throws SQLException {
        List<AliasedNode> aliasedNodes = statement.getSelect();
        // Setup projected columns in Scan
        SelectClauseVisitor selectVisitor = new SelectClauseVisitor(context, groupBy);
        List<ExpressionProjector> projectedColumns = new ArrayList<ExpressionProjector>();
        TableRef tableRef = context.getResolver().getTables().get(0);
        PTable table = tableRef.getTable();
        boolean isWildcard = false;
        Scan scan = context.getScan();
        int index = 0;
        List<Expression> projectedExpressions = Lists.newArrayListWithExpectedSize(aliasedNodes.size());
        List<byte[]> projectedFamilies = Lists.newArrayListWithExpectedSize(aliasedNodes.size());
        // TODO: support cf.* expressions in projection to project all columns in a  CF
        for (AliasedNode aliasedNode : aliasedNodes) {
            ParseNode node = aliasedNode.getNode();
            if (node == WildcardParseNode.INSTANCE) {
                if (statement.isAggregate()) {
                    ExpressionCompiler.throwNonAggExpressionInAggException(node.toString());
                }
                isWildcard = true;
                for (int i = table.getBucketNum() == null ? 0 : 1; i < table.getColumns().size(); i++) {
                    ColumnRef ref = new ColumnRef(tableRef,i);
                    Expression expression = ref.newColumnExpression();
                    projectedExpressions.add(expression);
                    projectedColumns.add(new ExpressionProjector(ref.getColumn().getName().getString(), table.getName().getString(), expression, false));
                }
            } else if (node instanceof  FamilyParseNode){
                // Project everything for SELECT cf.*
        		PColumnFamily pfamily = table.getColumnFamily(((FamilyParseNode) node).getFamilyName());
        		// Delay projecting to scan, as when any other column in the column family gets
        		// added to the scan, it overwrites that we want to project the entire column
        		// family. Instead, we do the projection at the end.
        		// TODO: consider having a ScanUtil.addColumn and ScanUtil.addFamily to work
        		// around this, as this code depends on this function being the last place where
        		// columns are projected (which is currently true, but could change).
        		projectedFamilies.add(pfamily.getName().getBytes());
        		for (PColumn column : pfamily.getColumns()) {
        			ColumnRef ref = new ColumnRef(tableRef,column.getPosition());
                    Expression expression = ref.newColumnExpression();
                    projectedExpressions.add(expression);
        		 	projectedColumns.add(new ExpressionProjector(column.getName().toString(), table.getName().getString(),expression, false));
        		}
            } else {
                Expression expression = node.accept(selectVisitor);
                projectedExpressions.add(expression);
                if (targetColumns != null && index < targetColumns.length && targetColumns[index].getDataType() != expression.getDataType()) {
                    PDataType targetType = targetColumns[index].getDataType();
                    // Check if coerce allowed using more relaxed isComparable check, since we promote INTEGER to LONG 
                    // during expression evaluation and then convert back to INTEGER on UPSERT SELECT (and we don't have
                    // (an actual value we can specifically check against).
                    if (expression.getDataType() != null && !expression.getDataType().isComparableTo(targetType)) {
                        throw new ArgumentTypeMismatchException(targetType, expression.getDataType(), "column: " + targetColumns[index]);
                    }
                    expression = CoerceExpression.create(expression, targetType);
                }
                if (node instanceof BindParseNode) {
                    context.getBindManager().addParamMetaData((BindParseNode)node, expression);
                }
                if (!node.isConstant()) {
                    if (!selectVisitor.isAggregate() && statement.isAggregate()) {
                        ExpressionCompiler.throwNonAggExpressionInAggException(expression.toString());
                    }
                }
                String columnAlias = aliasedNode.getAlias();
                boolean isCaseSensitive = aliasedNode.isCaseSensitve() || selectVisitor.isCaseSensitive;
                String name = columnAlias == null ? node.toString() : columnAlias;
                projectedColumns.add(new ExpressionProjector(name, table.getName().getString(), expression, isCaseSensitive));
            }
            selectVisitor.reset();
            index++;
        }

        int estimatedKeySize = table.getRowKeySchema().getEstimatedValueLength();
        int estimatedByteSize = 0;
        for (Map.Entry<byte[],NavigableSet<byte[]>> entry : scan.getFamilyMap().entrySet()) {
            PColumnFamily family = table.getColumnFamily(entry.getKey());
            if (entry.getValue() == null) {
                for (PColumn column : family.getColumns()) {
                    Integer byteSize = column.getByteSize();
                    estimatedByteSize += SizedUtil.KEY_VALUE_SIZE + estimatedKeySize + (byteSize == null ? RowKeySchema.ESTIMATED_VARIABLE_LENGTH_SIZE : byteSize);
                }
            } else {
                for (byte[] cq : entry.getValue()) {
                    PColumn column = family.getColumn(cq);
                    Integer byteSize = column.getByteSize();
                    estimatedByteSize += SizedUtil.KEY_VALUE_SIZE + estimatedKeySize + (byteSize == null ? RowKeySchema.ESTIMATED_VARIABLE_LENGTH_SIZE : byteSize);
                }
            }
        }
        
        selectVisitor.compile();
        // Since we don't have the empty key value in read-only tables,
        // we must project everything.
        boolean isProjectEmptyKeyValue = table.getType() != PTableType.VIEW && !isWildcard;
        if (isProjectEmptyKeyValue) {
            for (byte[] family : projectedFamilies) {
                projectColumnFamily(table, scan, family);       
            }
        } else {
            /* 
             * TODO: this could be optimized by detecting:
             * - if a column is projected that's not in the where clause
             * - if a column is grouped by that's not in the where clause
             * - if we're not using IS NULL or CASE WHEN expressions
             */
             projectAllColumnFamilies(table,scan);
        }
        return new RowProjector(projectedColumns, estimatedByteSize, isProjectEmptyKeyValue);
    }
        
    private static class SelectClauseVisitor extends ExpressionCompiler {
        private static int getMinNullableIndex(List<SingleAggregateFunction> aggFuncs, boolean isUngroupedAggregation) {
            int minNullableIndex = aggFuncs.size();
            for (int i = 0; i < aggFuncs.size(); i++) {
                SingleAggregateFunction aggFunc = aggFuncs.get(i);
                if (isUngroupedAggregation ? aggFunc.getAggregator().isNullable() : aggFunc.getAggregatorExpression().isNullable()) {
                    minNullableIndex = i;
                    break;
                }
            }
            return minNullableIndex;
        }
        
        /**
         * Track whether or not the projection expression is case sensitive. We use this
         * information to determine whether or not we normalize the column name passed
         */
        private boolean isCaseSensitive;
        private int elementCount;
        
        private SelectClauseVisitor(StatementContext context, GroupBy groupBy) {
            super(context, groupBy);
            reset();
        }


        /**
         * Compiles projection by:
         * 1) Adding RowCount aggregate function if not present when limiting rows. We need this
         *    to track how many rows have been scanned.
         * 2) Reordering aggregation functions (by putting fixed length aggregates first) to
         *    optimize the positional access of the aggregated value.
         */
        private void compile() throws SQLException {
            final Set<SingleAggregateFunction> aggFuncSet = Sets.newHashSetWithExpectedSize(context.getExpressionManager().getExpressionCount());
    
            Iterator<Expression> expressions = context.getExpressionManager().getExpressions();
            while (expressions.hasNext()) {
                Expression expression = expressions.next();
                expression.accept(new SingleAggregateFunctionVisitor() {
                    @Override
                    public Iterator<Expression> visitEnter(SingleAggregateFunction function) {
                        aggFuncSet.add(function);
                        return Iterators.emptyIterator();
                    }
                });
            }
            if (aggFuncSet.isEmpty() && groupBy.isEmpty()) {
                return;
            }
            List<SingleAggregateFunction> aggFuncs = new ArrayList<SingleAggregateFunction>(aggFuncSet);
            Collections.sort(aggFuncs, SingleAggregateFunction.SCHEMA_COMPARATOR);
    
            int minNullableIndex = getMinNullableIndex(aggFuncs,groupBy.isEmpty());
            context.getScan().setAttribute(GroupedAggregateRegionObserver.AGGREGATORS, ServerAggregators.serialize(aggFuncs, minNullableIndex));
            ClientAggregators clientAggregators = new ClientAggregators(aggFuncs, minNullableIndex);
            context.getAggregationManager().setAggregators(clientAggregators);
        }
        
        @Override
        public void reset() {
            super.reset();
            elementCount = 0;
            isCaseSensitive = true;
        }
        
        @Override
        protected ColumnRef resolveColumn(ColumnParseNode node) throws SQLException {
            ColumnRef ref = super.resolveColumn(node);
            isCaseSensitive = isCaseSensitive && node.isCaseSensitive();
            return ref;
        }
        
        @Override
        public void addElement(List<Expression> l, Expression element) {
            elementCount++;
            isCaseSensitive &= elementCount == 1;
            super.addElement(l, element);
        }
    }
}
