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
package org.apache.pirk.responder.wideskies.spark.streaming;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Iterator;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.pirk.query.wideskies.QueryInfo;
import org.apache.pirk.responder.wideskies.spark.Accumulators;
import org.apache.pirk.responder.wideskies.spark.BroadcastVars;
import org.apache.pirk.response.wideskies.Response;
import org.apache.pirk.serialization.HadoopFileSystemStore;
import org.apache.spark.api.java.function.VoidFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;

public class FinalResponseFunction implements VoidFunction<Iterator<Tuple2<Long,BigInteger>>>
{
  private static final long serialVersionUID = 1L;

  private static final Logger logger = LoggerFactory.getLogger(FinalResponseFunction.class);

  private BroadcastVars bVars = null;
  private Accumulators accum = null;

  public FinalResponseFunction(Accumulators accumIn, BroadcastVars bbVarsIn)
  {
    bVars = bbVarsIn;
    accum = accumIn;
  }

  public void call(Iterator<Tuple2<Long,BigInteger>> iter) throws Exception
  {
    // Form the response object
    QueryInfo queryInfo = bVars.getQueryInfo();
    Response response = new Response(queryInfo);
    while (iter.hasNext())
    {
      Tuple2<Long,BigInteger> input = iter.next();
      response.addElement(input._1().intValue(), input._2());
      logger.debug("colNum = " + input._1().intValue() + " column = " + input._2().toString());
    }

    // Write out the response
    FileSystem fs = FileSystem.get(new Configuration());
    HadoopFileSystemStore storage = new HadoopFileSystemStore(fs);
    String outputFile = bVars.getOutput();
    logger.debug("outputFile = " + outputFile);
    try
    {
      storage.store(outputFile, response);
    } catch (IOException e)
    {
      throw new RuntimeException(e);
    }
    accum.incNumBatches(1);
  }
}
