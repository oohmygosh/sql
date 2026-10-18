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
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("avg", 0));
    assertFalse(PplFunctionMetadata.canAcceptMoreArguments("avg", 1));
    assertFalse(PplFunctionMetadata.canAcceptMoreArguments("AVG", 2));
  }

  @Test
  public void overloadedRoundAllowsItsOptionalSecondArgument() {
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("round", 1));
    assertFalse(PplFunctionMetadata.canAcceptMoreArguments("round", 2));
  }

  @Test
  public void requiredLaterArgumentsDoNotPrematurelyCloseTheCall() {
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("replace", 1));
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("replace", 2));
    assertFalse(PplFunctionMetadata.canAcceptMoreArguments("replace", 3));
  }

  @Test
  public void pplSpecificAggregateUsesItsRealOptionalArgument() {
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("take", 1));
    assertFalse(PplFunctionMetadata.canAcceptMoreArguments("take", 2));
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("percentile_approx", 2));
    assertFalse(PplFunctionMetadata.canAcceptMoreArguments("percentile_approx", 3));
  }

  @Test
  public void variadicOperatorsKeepOfferingArguments() {
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("coalesce", 2));
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("coalesce", 20));
  }

  @Test
  public void unknownAndUndeclaredSignaturesAreNotRestricted() {
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("future_function", 20));
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("scalar_min", 2));
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("cidrmatch", 2));
    // CONCAT is supplied by SqlLibraryOperators through PPLFuncImpTable, rather than the two
    // operator tables indexed here. Absence of reliable metadata must not close its argument list.
    assertTrue(PplFunctionMetadata.canAcceptMoreArguments("concat", 3));
  }

  @Test(expected = IllegalArgumentException.class)
  public void negativeArgumentCountsAreProgrammingErrors() {
    PplFunctionMetadata.canAcceptMoreArguments("avg", -1);
  }
}
