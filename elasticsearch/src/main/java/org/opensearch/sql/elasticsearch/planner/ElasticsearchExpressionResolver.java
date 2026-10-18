/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.elasticsearch.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.elasticsearch.ElasticsearchQueryBackend.Field;
import org.opensearch.sql.elasticsearch.ElasticsearchScan;

/**
 * Resolves source-object paths without confusing output aliases or computed maps with ES fields.
 */
public final class ElasticsearchExpressionResolver {
  private ElasticsearchExpressionResolver() {}

  public record Resolution(ElasticsearchScan scan, List<RexNode> expressions) {}

  /**
   * Materialization is a correctness transformation, independent of native query pushdown. The
   * original expression's type is retained so parent RelNodes keep their input/output contract.
   */
  public static Resolution resolve(ElasticsearchScan scan, List<RexNode> expressions) {
    if (scan.queryPlan().isAggregate()) return new Resolution(scan, expressions);
    List<Field> fields = new ArrayList<>(scan.queryPlan().fields());
    int originalSize = fields.size();
    RexShuttle shuttle =
        new RexShuttle() {
          @Override
          public RexNode visitCall(RexCall call) {
            Path path = path(call);
            if (path != null && path.input() < originalSize) {
              Field root = fields.get(path.input());
              if (Set.of("object", "any").contains(root.mappingType())
                  && root.hasObjectSourcePath()
                  && scan.sourceFields().stream().anyMatch(f -> f.name().equals(root.name()))
                  && !crossesArray(scan.sourceFields(), root.name(), path.suffix())) {
                Field leaf = leaf(root, path.suffix(), scan.sourceFields());
                if (!leaf.hasObjectSourcePath()) return super.visitCall(call);
                int index = fields.indexOf(leaf);
                if (index < 0) {
                  index = fields.size();
                  fields.add(leaf);
                }
                var types = scan.getCluster().getTypeFactory();
                RexNode ref =
                    new RexInputRef(index, types.createTypeWithNullability(leaf.type(types), true));
                return scan.getCluster().getRexBuilder().ensureType(call.getType(), ref, true);
              }
            }
            return super.visitCall(call);
          }
        };
    List<RexNode> resolved = expressions.stream().map(e -> e.accept(shuttle)).toList();
    return new Resolution(scan.appendFields(fields.subList(originalSize, fields.size())), resolved);
  }

  /** Only representation-preserving casts introduced by path materialization may expose a field. */
  public static RexInputRef inputRef(RexNode node) {
    if (node instanceof RexInputRef ref) return ref;
    if (node instanceof RexCall call
        && call.getKind() == SqlKind.CAST
        && call.getOperands().size() == 1
        && call.getType().getSqlTypeName() == SqlTypeName.ANY)
      return inputRef(call.getOperands().getFirst());
    return null;
  }

  private record Path(int input, String suffix) {}

  private static Path path(RexNode node) {
    if (!(node instanceof RexCall call)
        || call.getKind() != SqlKind.ITEM
        || call.getOperands().size() != 2
        || !(call.getOperands().get(1) instanceof RexLiteral key)
        || key.isNull()
        || !SqlTypeName.CHAR_TYPES.contains(key.getType().getSqlTypeName())) return null;
    String suffix = key.getValueAs(String.class);
    if (suffix == null || suffix.isEmpty()) return null;
    RexNode base = call.getOperands().getFirst();
    if (base instanceof RexInputRef ref) return new Path(ref.getIndex(), suffix);
    Path parent = path(base);
    return parent == null ? null : new Path(parent.input(), parent.suffix() + "." + suffix);
  }

  private static boolean crossesArray(List<Field> schema, String root, String suffix) {
    String name = root + "." + suffix;
    return schema.stream()
        .anyMatch(
            f ->
                (f.mappingType().equals("nested") || f.mappingType().startsWith("array<"))
                    && (name.equals(f.name()) || name.startsWith(f.name() + ".")));
  }

  private static Field leaf(Field root, String suffix, List<Field> schema) {
    String name = root.name() + "." + suffix;
    for (Field field : schema) {
      if (!field.name().equals(name)) continue;
      Set<String> indices = new LinkedHashSet<>(root.sources().keySet());
      indices.addAll(field.sources().keySet());
      boolean samePath =
          indices.isEmpty()
              ? field.source().equals(root.source() + "." + suffix)
              : indices.stream()
                  .allMatch(i -> field.source(i).equals(root.source(i) + "." + suffix));
      if (samePath) return field;
    }
    // An unmapped path or a multi-field with a different _source route is still readable, but
    // cannot be translated to an indexed field merely because its display name happens to match.
    Map<String, String> sources = new LinkedHashMap<>();
    root.sources().forEach((index, source) -> sources.put(index, source + "." + suffix));
    return new Field(name, root.source() + "." + suffix, "any", false, sources);
  }
}
