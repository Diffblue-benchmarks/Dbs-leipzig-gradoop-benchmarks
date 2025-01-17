/*
 * Copyright © 2014 - 2019 Leipzig University (Database Research Group)
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
package org.gradoop.benchmarks.biiig;

import org.apache.commons.io.IOUtils;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.gradoop.common.model.api.entities.EPGMElement;
import org.gradoop.common.model.impl.properties.PropertyValue;
import org.gradoop.flink.algorithms.btgs.BusinessTransactionGraphs;
import org.gradoop.flink.algorithms.fsm.transactional.CategoryCharacteristicSubgraphs;
import org.gradoop.flink.algorithms.fsm.transactional.common.FSMConfig;
import org.gradoop.flink.model.api.functions.VertexAggregateFunction;
import org.gradoop.flink.model.impl.epgm.GraphCollection;
import org.gradoop.flink.model.impl.epgm.LogicalGraph;
import org.gradoop.flink.model.impl.operators.aggregation.ApplyAggregation;
import org.gradoop.flink.model.impl.operators.aggregation.functions.bool.Or;
import org.gradoop.flink.model.impl.operators.aggregation.functions.count.VertexCount;
import org.gradoop.flink.model.impl.operators.transformation.ApplyTransformation;
import org.gradoop.flink.util.FlinkAsciiGraphLoader;
import org.gradoop.flink.util.GradoopFlinkConfig;

import java.io.IOException;

import static org.gradoop.flink.algorithms.btgs.BusinessTransactionGraphs.*;
import static org.gradoop.flink.algorithms.fsm.transactional.CategoryCharacteristicSubgraphs.CATEGORY_KEY;

/**
 * Example workflow of paper "Scalable Business Intelligence with Graph Collections" submitted
 * to IT special issue on Big Data Analytics.
 */
public class CategoryCharacteristicPatterns {

  /**
   * main method
   *
   * @param args arguments (none required)
   * @throws Exception on failure
   */
  public static void main(String[] args) throws Exception {

    LogicalGraph iig = getIntegratedInstanceGraph();

    System.out.println("Integrated Instance Graph");
    iig.print();

    // extract business transaction graphs (BTGs)
    GraphCollection btgs = iig
      .callForCollection(new BusinessTransactionGraphs());

    btgs = btgs
      // determine closed/open BTGs
      .apply(new ApplyAggregation(new IsClosedAggregateFunction()))
      // select closed BTGs
      .select(g -> g.getPropertyValue("isClosed").getBoolean())
      // count number of sales orders per BTG
      .apply(new ApplyAggregation(new CountSalesOrdersAggregateFunction()));

    System.out.println("Business Transaction Graphs with Measures");
    btgs.print();

    btgs = btgs.apply(new ApplyTransformation((graph, copy) -> {
      // Transformation function to categorize graphs
      String category = graph.getPropertyValue("soCount").getInt() > 0 ? "won" : "lost";
      copy.setProperty(CATEGORY_KEY, PropertyValue.create(category));
      return copy;
    }, (vertex, copy) -> {
      // Transformation function to relabel vertices and to drop properties
      String superType = vertex.getPropertyValue(SUPERTYPE_KEY).toString();

      if (superType.equals(SUPERCLASS_VALUE_TRANSACTIONAL)) {
        copy.setLabel(vertex.getLabel());
      } else { // master data
        copy.setLabel(vertex.getPropertyValue(SOURCEID_KEY).toString());
      }
      return copy;
    }, (edge, copy) -> {
      copy.setLabel(edge.getLabel());
      return copy;
    })
    );

    System.out.println("Business Transaction Graphs after Transformation");
    btgs.print();

    FSMConfig fsmConfig = new FSMConfig(0.8f, true, 1, 3);

    GraphCollection patterns = btgs
      .callForCollection(new CategoryCharacteristicSubgraphs(fsmConfig, 2.0f));

    System.out.println("Category characteristic graph patters");
    patterns.print();
  }

  /**
   * Returns example integrated instance graph from GDL input.
   *
   * @return integrated instance graph
   * @throws IOException on failure
   */
  private static LogicalGraph getIntegratedInstanceGraph() throws IOException {

    ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

    GradoopFlinkConfig gradoopConf = GradoopFlinkConfig.createConfig(env);

    FlinkAsciiGraphLoader loader = new FlinkAsciiGraphLoader(gradoopConf);

    String gdl = IOUtils.toString(
      CategoryCharacteristicPatterns.class.getResourceAsStream("/data/gdl/itbda.gdl"));

    gdl = gdl
      .replaceAll("SOURCEID_KEY",
        SOURCEID_KEY)
      .replaceAll("SUPERTYPE_KEY",
        SUPERTYPE_KEY)
      .replaceAll("SUPERCLASS_VALUE_MASTER",
        SUPERCLASS_VALUE_MASTER)
      .replaceAll("SUPERCLASS_VALUE_TRANSACTIONAL",
        SUPERCLASS_VALUE_TRANSACTIONAL);

    loader.initDatabaseFromString(gdl);

    return loader.getLogicalGraphByVariable("iig");
  }

  /**
   * Aggregate function to determine "isClosed" measure.
   */
  private static class IsClosedAggregateFunction implements Or, VertexAggregateFunction {

    @Override
    public String getAggregatePropertyKey() {
      return "isClosed";
    }

    @Override
    public PropertyValue getIncrement(EPGMElement vertex) {
      boolean isClosedQuotation =
        vertex.getLabel().equals("Quotation") &&
          !vertex.getPropertyValue("status").toString().equals("open");

      return PropertyValue.create(isClosedQuotation);
    }
  }

  /**
   * Aggregate function to count sales orders per graph.
   */
  private static class CountSalesOrdersAggregateFunction extends VertexCount {

    /**
     * Creates a new instance of a CountSalesOrderAggregateFunction aggregate function.
     */
    CountSalesOrdersAggregateFunction() {
      super("soCount");
    }

    @Override
    public PropertyValue getIncrement(EPGMElement vertex) {
      return PropertyValue.create(vertex.getLabel().equals("SalesOrder") ? 1 : 0);
    }
  }
}
