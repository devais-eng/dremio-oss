/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.plugins.jdbc.planning;

import com.dremio.exec.planner.physical.ExchangePrel;
import com.dremio.exec.planner.physical.ProjectPrel;
import com.dremio.exec.planner.physical.TopNPrel;
import com.dremio.plugins.jdbc.planning.JdbcScanPrel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL-specific HEP-phase pushdown rule for pgvector nearest-neighbor queries.
 *
 * <p>Matches the topology produced by Dremio when the user writes:
 * <pre>
 *   SELECT ... FROM pg_source.schema.table
 *   ORDER BY l2_distance(embedding, ARRAY[0.5, 1.0, 1.5]) LIMIT K
 * </pre>
 *
 * <p>Dremio's planner decomposes {@code ARRAY[...]} into a constant pipeline:
 * <pre>
 *   TopNPrel(limit=K)
 *     ProjectPrel(L2_DISTANCE($1, CASE(IS NULL($2), $3, $2)))
 *       ProjectPrel(identity + EMPTY_ARRAY fallback)
 *         NestedLoopJoin(condition=true, joinType=left)
 *           JdbcScanPrel(table, [id, embedding])
 *           HashAgg(ARRAY_AGG($0))
 *             Values([[0.5], [1.0], [1.5]])
 * </pre>
 *
 * <p>This rule detects that topology, extracts the constant float values from the Values node,
 * and pushes the distance + ORDER BY + LIMIT directly onto the JdbcScanPrel via
 * {@code cloneWithSortKeyExpressions()} + {@code cloneWithLimit()}.
 *
 * <p>The rule is <strong>best-effort</strong>: if the topology doesn't match (cross-source,
 * subquery, complex expression), the rule silently declines and Dremio computes the distance
 * in-engine using the Phase 41 {@link VectorDistanceFunctions}.
 *
 * <p>Registered in {@code PHYSICAL_HEP} via {@link com.dremio.plugins.jdbc.planning.JdbcRulesFactory}
 * for PostgreSQL sources only.
 */
public final class PgvectorKnnPushdownRule extends RelOptRule {

  private static final Logger logger = LoggerFactory.getLogger(PgvectorKnnPushdownRule.class);

  private static final Set<String> DISTANCE_FUNCTIONS = Set.of(
      "L2_DISTANCE", "COSINE_DISTANCE", "INNER_PRODUCT");

  public static final PgvectorKnnPushdownRule INSTANCE = new PgvectorKnnPushdownRule();

  private PgvectorKnnPushdownRule() {
    super(operand(TopNPrel.class, any()), "PgvectorKnnPushdownRule");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    TopNPrel topN = call.rel(0);
    logger.info("[PGVEC-KNN] matches() called for TopN with {} collations, input={}",
        topN.getCollation().getFieldCollations().size(),
        unwrap(topN.getInput()).getClass().getSimpleName());
    if (topN.getCollation().getFieldCollations().isEmpty()) {
      logger.info("[PGVEC-KNN] No collations — skip");
      return false;
    }

    // Walk down to find the distance function call and the JdbcScanPrel.
    MatchResult match = analyzeTopology(topN);
    logger.info("[PGVEC-KNN] analyzeTopology result: {}", match != null ? "MATCHED" : "no match");
    return match != null;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    TopNPrel topN = call.rel(0);
    MatchResult match = analyzeTopology(topN);
    if (match == null) {
      return;
    }

    logger.info("[PGVEC-KNN] Pushing {} ORDER BY {} LIMIT {} to source",
        match.functionName, match.scan.getTableName(), topN.getLimit());

    RexBuilder rexBuilder = match.scan.getCluster().getRexBuilder();

    // Build the sort key expression: distance_func($scanColIdx, LITERAL_ARRAY)
    // The literal array is rendered as a RexLiteral containing the pgvector text format.
    // We encode the constant vector as a VARCHAR literal '[0.5,1.0,1.5]' that
    // DremioPostgresDialect will pass through to pgvector.
    // Build pgvector text format WITHOUT outer quotes — Calcite's VARCHAR literal
    // rendering adds the quotes automatically via SqlDialect.
    StringBuilder vecStr = new StringBuilder("[");
    for (int i = 0; i < match.constantVector.size(); i++) {
      if (i > 0) {
        vecStr.append(',');
      }
      vecStr.append(match.constantVector.get(i).toPlainString());
    }
    vecStr.append("]");

    // Build a RexCall for the distance function referencing the scan's column index.
    // The sort key expression references the scan's row type (projected columns).
    // We need to find which projected column index corresponds to the embedding column.
    int embeddingIdx = match.scanColumnIndex;

    // Create the sort key as a RexCall with the distance function operator and two operands:
    //   operand 0: RexInputRef pointing to the embedding column in the scan
    //   operand 1: RexLiteral with the constant vector as VARCHAR (pgvector text format)
    RexNode embeddingRef = rexBuilder.makeInputRef(
        match.scan.getRowType().getFieldList().get(embeddingIdx).getType(), embeddingIdx);
    RexNode vectorLiteral = rexBuilder.makeLiteral(vecStr.toString());

    // Build the RexCall using the original operator from the plan
    RexNode distanceCall = rexBuilder.makeCall(
        match.distanceCall.getType(),
        match.distanceCall.getOperator(),
        List.of(embeddingRef, vectorLiteral));

    List<RexNode> sortKeyExprs = Collections.singletonList(distanceCall);

    // Push sort key expressions + collation + limit onto the scan.
    JdbcScanPrel withSort = match.scan.cloneWithSortKeyExpressions(
        topN.getCollation(), sortKeyExprs);
    JdbcScanPrel withSortAndLimit = withSort.cloneWithLimit(topN.getLimit());

    // The replacement must have the same row type as the TopNPrel.
    // TopNPrel's row type is [id, EXPR$1(distance)] but the scan only has [id, embedding].
    // We need to wrap in a ProjectPrel that produces the TopN's output row type.
    // Use identity projection for all scan columns — the distance column is dropped
    // since it was only used for sorting (the original SELECT only has 'id').
    // Actually: the TopN output includes the distance column. We need to project it.
    // But we can't compute the distance again after pushdown — the scan already orders by it.
    // The trick: after pushdown, the scan returns rows in the correct order. The outer
    // query only needs the SELECT columns (e.g., 'id'). The TopN/Limit above will be
    // absorbed. We replace the entire TopN subtree with just the scan + projection to
    // select the original output columns.

    // Find what the TopN outputs — we need to match its row type.
    // The TopN row type typically includes the distance as EXPR$1.
    // Walk up to find the outermost Project that trims to just 'id'.
    // Actually, the simplest approach: call transformTo with the scan wrapped in a
    // project that matches the TopN's row type. For the distance column, compute it
    // from the (now ordered) scan output. But that defeats the purpose...
    //
    // Better approach: replace the TopN with the scan that has ORDER BY + LIMIT pushed.
    // The scan's row type is [id, embedding]. The TopN's row type is [id, EXPR$1(double)].
    // We can project [id, null::double] since the distance value is only needed for ordering.
    // But actually Dremio might need the distance value if the user selected it...
    //
    // Let's check: the original query is "SELECT id FROM ... ORDER BY l2_distance(...) LIMIT 5"
    // The TopN output is [id, EXPR$1]. The Project above it trims to just [id].
    // So EXPR$1 is only used for sorting, not returned to the user.
    //
    // Produce a scan with the right row type by projecting the scan's columns to match
    // TopN's output. For the distance column, emit the distanceCall RexCall so the
    // distance value is computed locally from the pushed-down sorted rows.

    List<RexNode> wrapperProjects = new ArrayList<>();
    List<String> wrapperNames = new ArrayList<>();
    var topNFields = topN.getRowType().getFieldList();
    var scanFields = withSortAndLimit.getRowType().getFieldList();

    for (int i = 0; i < topNFields.size(); i++) {
      var topNField = topNFields.get(i);
      // Find scan field with matching name (case-insensitive for robustness).
      // This avoids silent data corruption when scan column order differs from TopN's
      // expected output order (e.g., scan=[id, name, category_id, price, embedding]
      // but TopN expects [id, name, price, category_id, distance]).
      int scanIdx = -1;
      for (int j = 0; j < scanFields.size(); j++) {
        if (scanFields.get(j).getName().equalsIgnoreCase(topNField.getName())) {
          scanIdx = j;
          break;
        }
      }
      if (scanIdx >= 0) {
        // Matched by name — use the scan field's actual type.
        wrapperProjects.add(rexBuilder.makeInputRef(scanFields.get(scanIdx).getType(), scanIdx));
      } else {
        // No matching scan field — this is the distance column (e.g. EXPR$1).
        // Emit the actual distance RexCall so the distance value is computed from
        // the pushed-down sorted rows, rather than returning null.
        // distanceCall was built in onMatch() referencing the scan's embedding column index.
        wrapperProjects.add(distanceCall);
      }
      wrapperNames.add(topNField.getName());
    }

    var wrapperRowType = withSortAndLimit.getCluster().getTypeFactory().createStructType(
        wrapperProjects.stream()
            .map(RexNode::getType)
            .collect(java.util.stream.Collectors.toList()),
        wrapperNames);
    ProjectPrel wrapper = ProjectPrel.create(
        withSortAndLimit.getCluster(),
        withSortAndLimit.getTraitSet(),
        withSortAndLimit,
        wrapperProjects,
        wrapperRowType);

    call.transformTo(wrapper);
  }

  // ---------------------------------------------------------------------------
  // Topology analysis
  // ---------------------------------------------------------------------------

  /** Result of successfully matching the pgvector KNN topology. */
  private static final class MatchResult {
    final JdbcScanPrel scan;
    final RexCall distanceCall;
    final String functionName;
    final int scanColumnIndex; // embedding column index in scan's projected row type
    final List<BigDecimal> constantVector;

    MatchResult(JdbcScanPrel scan, RexCall distanceCall, String functionName,
        int scanColumnIndex, List<BigDecimal> constantVector) {
      this.scan = scan;
      this.distanceCall = distanceCall;
      this.functionName = functionName;
      this.scanColumnIndex = scanColumnIndex;
      this.constantVector = constantVector;
    }
  }

  /**
   * Analyzes the plan tree below a TopNPrel to detect the pgvector KNN topology.
   *
   * @return a MatchResult if the topology matches, null otherwise
   */
  private static MatchResult analyzeTopology(TopNPrel topN) {
    if (topN.getCollation().getFieldCollations().size() != 1) {
      logger.info("[PGVEC-KNN] collations != 1, skip");
      return null; // KNN sorts by exactly one distance expression
    }

    RelFieldCollation fc = topN.getCollation().getFieldCollations().get(0);
    logger.info("[PGVEC-KNN] Analyzing: collation fieldIdx={}", fc.getFieldIndex());

    // Walk down through Projects to find the distance function call.
    RelNode current = unwrap(topN.getInput());
    logger.info("[PGVEC-KNN] TopN input (unwrapped): {}", current.getClass().getSimpleName());
    RexCall distanceCall = null;
    int distanceFieldIdx = fc.getFieldIndex();

    // Walk through Exchange and ProjectPrel layers.
    // At HEP time, exchanges (RoundRobinExchangePrel, etc.) may sit between TopN and Project.
    while (current instanceof ExchangePrel) {
      current = unwrap(((ExchangePrel) current).getInput());
      logger.info("[PGVEC-KNN] Traversed exchange, now: {}", current.getClass().getSimpleName());
    }
    while (current instanceof ProjectPrel) {
      ProjectPrel proj = (ProjectPrel) current;
      List<RexNode> projects = proj.getProjects();
      if (distanceFieldIdx < 0 || distanceFieldIdx >= projects.size()) {
        return null;
      }
      RexNode expr = projects.get(distanceFieldIdx);
      logger.info("[PGVEC-KNN] Project expr at idx {}: {} (class={})",
          distanceFieldIdx, expr, expr.getClass().getSimpleName());

      // Check if this is the distance function call
      RexCall found = extractDistanceCall(expr);
      if (found != null) {
        distanceCall = found;
        logger.info("[PGVEC-KNN] Found distance call: {}", found.getOperator().getName());
        break;
      }

      // If it's a simple ref, follow it down (through exchanges)
      if (expr instanceof RexInputRef) {
        distanceFieldIdx = ((RexInputRef) expr).getIndex();
        current = unwrap(proj.getInput());
        while (current instanceof ExchangePrel) {
          current = unwrap(((ExchangePrel) current).getInput());
        }
        continue;
      }

      // Unknown expression — can't match
      return null;
    }

    if (distanceCall == null) {
      return null;
    }

    String funcName = distanceCall.getOperator().getName().toUpperCase(Locale.ROOT);
    if (!DISTANCE_FUNCTIONS.contains(funcName)) {
      return null;
    }

    // The distance function has 2 operands. One should reference the scan column,
    // the other should be the constant vector (possibly wrapped in CASE/IS NULL).
    // Find the NestedLoopJoin below the current ProjectPrel.
    ProjectPrel distanceProject = (ProjectPrel) current;
    RelNode belowProject = unwrap(distanceProject.getInput());
    logger.info("[PGVEC-KNN] Below distance project: {}", belowProject.getClass().getSimpleName());

    // May have another Project, Exchange, or other nodes between distance project and NLJ
    while (belowProject instanceof ProjectPrel || belowProject instanceof ExchangePrel) {
      if (belowProject instanceof ProjectPrel) {
        belowProject = unwrap(((ProjectPrel) belowProject).getInput());
      } else {
        belowProject = unwrap(((ExchangePrel) belowProject).getInput());
      }
      logger.info("[PGVEC-KNN] Traversed to: {}", belowProject.getClass().getSimpleName());
    }

    // Expect NestedLoopJoin or directly JdbcScanPrel
    JdbcScanPrel scan = null;
    List<BigDecimal> constantVector = null;

    logger.info("[PGVEC-KNN] Looking for NLJ/JdbcScan at: {}", belowProject.getClass().getSimpleName());
    if (belowProject instanceof Join) {
      Join nlj = (Join) belowProject;
      RelNode left = unwrap(nlj.getLeft());
      RelNode right = unwrap(nlj.getRight());
      logger.info("[PGVEC-KNN] NLJ left: {}, right: {}",
          left.getClass().getSimpleName(), right.getClass().getSimpleName());

      // Left should be JdbcScanPrel (possibly through exchanges)
      scan = findJdbcScan(left);

      // Right should be the constant pipeline: HashAgg(ARRAY_AGG) -> Values
      constantVector = extractConstantVector(right);
      if (constantVector == null) {
        logger.info("[PGVEC-KNN] extractConstantVector failed for right: {}",
            right.getClass().getSimpleName());
        // Try walking through exchanges on the right side too
        RelNode rightUnwrapped = right;
        while (rightUnwrapped instanceof ExchangePrel) {
          rightUnwrapped = unwrap(((ExchangePrel) rightUnwrapped).getInput());
        }
        if (rightUnwrapped != right) {
          logger.info("[PGVEC-KNN] Right after exchange traversal: {}",
              rightUnwrapped.getClass().getSimpleName());
          constantVector = extractConstantVector(rightUnwrapped);
        }
      }
    } else {
      // Direct JdbcScanPrel without NLJ — constant might be a RexLiteral in the call
      scan = findJdbcScan(belowProject);
      // Try to extract constant from the distance call's operands directly
      constantVector = extractConstantFromCall(distanceCall);
    }

    logger.info("[PGVEC-KNN] scan={}, constantVector={}",
        scan != null ? scan.getTableName() : "null",
        constantVector != null ? constantVector.size() + " elements" : "null");
    if (scan == null || constantVector == null || constantVector.isEmpty()) {
      return null;
    }

    // Don't push if scan already has ORDER BY
    if (scan.hasOrderBy()) {
      return null;
    }

    // Find which operand of the distance call references the scan column
    int scanColIdx = findScanColumnIndex(distanceCall, scan);
    if (scanColIdx < 0) {
      return null;
    }

    logger.debug("[PGVEC-KNN] Matched: func={}, scan={}.{}, embeddingCol={}, vectorDim={}",
        funcName, scan.getSchemaName(), scan.getTableName(), scanColIdx, constantVector.size());

    return new MatchResult(scan, distanceCall, funcName, scanColIdx, constantVector);
  }

  /**
   * Extracts a distance function RexCall from an expression, unwrapping CASE/IS NULL wrappers.
   */
  private static RexCall extractDistanceCall(RexNode expr) {
    if (expr instanceof RexCall) {
      RexCall call = (RexCall) expr;
      String name = call.getOperator().getName().toUpperCase(Locale.ROOT);
      if (DISTANCE_FUNCTIONS.contains(name)) {
        return call;
      }
      // Check if it's CASE(IS NULL($x), $y, $x) wrapping a distance call
      // Walk operands looking for the distance call
      for (RexNode operand : call.getOperands()) {
        RexCall inner = extractDistanceCall(operand);
        if (inner != null) {
          return inner;
        }
      }
    }
    return null;
  }

  /**
   * Extracts constant float values from the Values → HashAgg(ARRAY_AGG) pipeline.
   */
  private static List<BigDecimal> extractConstantVector(RelNode node) {
    node = unwrap(node);

    // Traverse through exchanges
    while (node instanceof ExchangePrel) {
      node = unwrap(((ExchangePrel) node).getInput());
    }

    // Expect Aggregate(ARRAY_AGG) wrapping Values (HashAggPrel extends Aggregate)
    if (node instanceof Aggregate) {
      Aggregate agg = (Aggregate) node;
      RelNode aggInput = unwrap(agg.getInput());
      return extractConstantVector(aggInput);
    }

    // Walk through single-input nodes (ProjectPrel, etc.) to find Values
    if (!(node instanceof Values) && node.getInputs().size() == 1) {
      return extractConstantVector(node.getInput(0));
    }

    // Values node: each row has one column with a numeric value.
    // Handle both logical Values and physical ValuesPrel.
    List<? extends List<RexLiteral>> tuples = null;
    if (node instanceof Values) {
      tuples = ((Values) node).getTuples();
    } else if (node instanceof com.dremio.exec.planner.physical.ValuesPrel) {
      tuples = ((com.dremio.exec.planner.physical.ValuesPrel) node).getTuples();
    }
    if (tuples != null) {
      List<BigDecimal> result = new ArrayList<>();
      for (var tuple : tuples) {
        if (tuple.size() != 1) {
          return null;
        }
        RexLiteral lit = tuple.get(0);
        BigDecimal val = lit.getValueAs(BigDecimal.class);
        if (val == null) {
          return null;
        }
        result.add(val);
      }
      return result;
    }

    return null;
  }

  /**
   * Tries to extract a constant vector from the distance call's operands directly
   * (when no NestedLoopJoin is present — e.g., if Dremio someday optimizes the literal path).
   */
  private static List<BigDecimal> extractConstantFromCall(RexCall call) {
    for (RexNode operand : call.getOperands()) {
      if (operand instanceof RexLiteral) {
        // Scalar literal — can't be a vector
        continue;
      }
      if (operand instanceof RexCall) {
        RexCall innerCall = (RexCall) operand;
        if (innerCall.getOperator().getName().equals("ARRAY_VALUE_CONSTRUCTOR")) {
          List<BigDecimal> result = new ArrayList<>();
          for (RexNode elem : innerCall.getOperands()) {
            if (elem instanceof RexLiteral) {
              BigDecimal val = ((RexLiteral) elem).getValueAs(BigDecimal.class);
              if (val == null) {
                return null;
              }
              result.add(val);
            } else {
              return null;
            }
          }
          return result;
        }
      }
    }
    return null;
  }

  /**
   * Finds the embedding column index in the scan's projected row type that the distance
   * function's first RexInputRef operand points to.
   */
  private static int findScanColumnIndex(RexCall distanceCall, JdbcScanPrel scan) {
    // The first operand is typically the scan column ref (directly or through projections).
    // We look for a RexInputRef in the distance call's operands.
    for (RexNode operand : distanceCall.getOperands()) {
      RexInputRef ref = findInputRef(operand);
      if (ref != null) {
        int idx = ref.getIndex();
        // Validate it's within scan bounds
        if (idx >= 0 && idx < scan.getRowType().getFieldCount()) {
          return idx;
        }
      }
    }
    return -1;
  }

  /** Recursively finds the first RexInputRef in an expression. */
  private static RexInputRef findInputRef(RexNode node) {
    if (node instanceof RexInputRef) {
      return (RexInputRef) node;
    }
    if (node instanceof RexCall) {
      for (RexNode operand : ((RexCall) node).getOperands()) {
        RexInputRef found = findInputRef(operand);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** Finds a JdbcScanPrel by walking through exchanges and HepRelVertex wrappers. */
  private static JdbcScanPrel findJdbcScan(RelNode node) {
    node = unwrap(node);
    if (node instanceof JdbcScanPrel) {
      return (JdbcScanPrel) node;
    }
    if (node instanceof ExchangePrel) {
      return findJdbcScan(((ExchangePrel) node).getInput());
    }
    // Walk through single-input nodes
    if (node.getInputs().size() == 1) {
      return findJdbcScan(node.getInput(0));
    }
    return null;
  }

  /** Unwraps HepRelVertex to get the underlying RelNode. */
  private static RelNode unwrap(RelNode node) {
    if (node instanceof HepRelVertex) {
      return ((HepRelVertex) node).getCurrentRel();
    }
    return node;
  }
}
