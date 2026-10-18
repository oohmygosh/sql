/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ppl.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.Set;
import org.junit.Test;
import org.opensearch.sql.ast.tree.Convert;
import org.opensearch.sql.ast.tree.Foreach;
import org.opensearch.sql.ppl.AstPlanningTestBase;

/** Language tooling reads the same dynamic vocabulary that the AST builder accepts. */
public class AstBuilderVocabularyTest extends AstPlanningTestBase {
  @Test
  public void exposedConversionFunctionsAreAcceptedAndImmutable() {
    Set<String> functions = AstBuilder.supportedConversionFunctions();
    assertFalse(functions.isEmpty());
    for (String function : functions) {
      assertTrue(
          plan("source=logs | convert " + function + "(value) as converted") instanceof Convert);
    }
    assertThrows(UnsupportedOperationException.class, () -> functions.add("unsupported"));
  }

  @Test
  public void foreachModeVocabularyIsAcceptedCaseInsensitively() {
    for (Foreach.Mode mode : Foreach.Mode.values()) {
      Foreach foreach =
          (Foreach)
              plan(
                  "source=logs | foreach "
                      + Foreach.Option.MODE.toString().toUpperCase(Locale.ROOT)
                      + "="
                      + mode.toString().toUpperCase(Locale.ROOT)
                      + " value [ eval result = 1 ]");
      assertEquals(mode, foreach.getMode());
      assertTrue(foreach.getOptions().containsKey(Foreach.Option.MODE.toString()));
    }
  }

  @Test
  public void foreachPlaceholderOptionKeysSurviveParsing() {
    for (Foreach.Option option : Foreach.Option.values()) {
      if (option == Foreach.Option.MODE) continue;
      String key = option.toString() + (option == Foreach.Option.MATCHSEG ? "1" : "");
      Foreach foreach =
          (Foreach) plan("source=logs | foreach " + key + "=custom value [ eval result = 1 ]");
      assertEquals("custom", foreach.getOptions().get(key));
    }
  }
}
