/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import static org.junit.Assert.*;

import org.junit.Test;

public class PplFunctionMetadataTest {
  @Test
  public void singleArgumentAggregateStopsAfterItsArgument() {
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("avg", 0));
    assertFalse(CompletionFunctionMetadata.canAcceptMoreArguments("avg", 1));
    assertFalse(CompletionFunctionMetadata.canAcceptMoreArguments("AVG", 2));
  }

  @Test
  public void overloadedRoundAllowsItsOptionalSecondArgument() {
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("round", 1));
    assertFalse(CompletionFunctionMetadata.canAcceptMoreArguments("round", 2));
  }

  @Test
  public void requiredLaterArgumentsDoNotPrematurelyCloseTheCall() {
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("replace", 1));
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("replace", 2));
    assertFalse(CompletionFunctionMetadata.canAcceptMoreArguments("replace", 3));
  }

  @Test
  public void pplSpecificAggregateUsesItsRealOptionalArgument() {
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("take", 1));
    assertFalse(CompletionFunctionMetadata.canAcceptMoreArguments("take", 2));
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("percentile_approx", 2));
    assertFalse(CompletionFunctionMetadata.canAcceptMoreArguments("percentile_approx", 3));
  }

  @Test
  public void variadicOperatorsKeepOfferingArguments() {
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("coalesce", 2));
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("coalesce", 20));
  }

  @Test
  public void unknownAndUndeclaredSignaturesAreNotRestricted() {
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("future_function", 20));
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("scalar_min", 2));
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("cidrmatch", 2));
    // CONCAT is supplied by SqlLibraryOperators through PPLFuncImpTable, rather than the two
    // operator tables indexed here. Absence of reliable metadata must not close its argument list.
    assertTrue(CompletionFunctionMetadata.canAcceptMoreArguments("concat", 3));
  }

  @Test(expected = IllegalArgumentException.class)
  public void negativeArgumentCountsAreProgrammingErrors() {
    CompletionFunctionMetadata.canAcceptMoreArguments("avg", -1);
  }
}
