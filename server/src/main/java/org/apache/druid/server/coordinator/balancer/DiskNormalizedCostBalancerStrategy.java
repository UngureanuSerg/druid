/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.coordinator.balancer;

import com.google.common.util.concurrent.ListeningExecutorService;
import org.apache.druid.server.coordinator.ServerHolder;
import org.apache.druid.timeline.DataSegment;

public class DiskNormalizedCostBalancerStrategy extends CostBalancerStrategy
{
  public DiskNormalizedCostBalancerStrategy(ListeningExecutorService exec)
  {
    super(exec);
  }

  /**
   * Averages the cost obtained from CostBalancerStrategy. Also the costs are weighted according to their usage ratios.
   * This ensures that all the hosts will have the same % disk utilization.
   */
  @Override
  protected double computeCost(
      final DataSegment proposalSegment,
      final ServerHolder server,
      final boolean includeCurrentServer
  )
  {
    double cost = super.computeCost(proposalSegment, server, includeCurrentServer);

    if (cost == Double.POSITIVE_INFINITY) {
      return cost;
    }

    int nSegments = 1;
    if (server.getServer().getNumSegments() > 0) {
      nSegments = server.getServer().getNumSegments();
    }

    double normalizedCost = cost / nSegments;
    double usageRatio = (double) server.getSizeUsed() / (double) server.getServer().getMaxSize();

    return normalizedCost * usageRatio;
  }
}

