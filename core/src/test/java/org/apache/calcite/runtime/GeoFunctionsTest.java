/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.calcite.runtime;
import org.apache.calcite.runtime.GeoFunctions;

import com.esri.core.geometry.Geometry;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Unit test for {@link GeoFunctions}.
 */
class GeoFunctionsTest {
  @Test
  void testHilbert() {
    long res0 = GeoFunctions.hilbert(GeoFunctions.ST_GeomFromText("POINT(-71.064544 42.28787)"));
    long res1 = GeoFunctions.hilbert(new BigDecimal("-71.064544"),
                                    new BigDecimal("42.28787"));
    System.out.print(res0);
    assert 0 <= res0;
    assert res0 <= Math.pow(2, 16) - 1;
    assert 0 <= res1;
    assert res1 <= Math.pow(2, 16) - 1;
    assert res0 == res1;
  }
}
