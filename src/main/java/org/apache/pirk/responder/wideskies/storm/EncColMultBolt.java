/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.pirk.responder.wideskies.storm;

import org.apache.log4j.Logger;
import org.apache.pirk.utils.LogUtils;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Bolt class to perform encrypted column multiplication
 * <p>
 * Takes {@code <columnIndex, columnValue>} tuples as input and aggregates (multiplies) the columnValues for a given columnIndex as they are received.
 * <p>
 * EncRowCalcBolts send flush signals to the EncColMultBolts indicating that they have finished sending all tuples for a session. Whenever a flush signal is
 * received from a EncRowCalcBolt, the num of received flush signals is tallied until each encrypted row has emitted a flush signal (there are 2^hashBitSize
 * rows).
 * <p>
 * Once a flush signal has been received from each row, all {@code <columnIndex, aggregate colVal product>} tuples are sent to the OutputBolt and a session_end
 * signal is sent back to each EncRowMultBolt.
 * <p>
 * The EncRowMultBolts buffer their output from the time that they send a flush signal to the EncColMultBolts until the time that they receive a session_end
 * signal from the EncColMultBolts.
 * 
 */
public class EncColMultBolt extends BaseRichBolt
{
  private static final long serialVersionUID = 1L;

  private static Logger logger = LogUtils.getLoggerForThisClass();

  private OutputCollector outputCollector;

  private BigInteger nSquared;
  private long numFlushSignals;
  private Long totalFlushSignals;

  // This is the main object here. It holds column Id -> aggregated product
  private HashMap<Long,BigInteger> resultsMap = new HashMap<Long,BigInteger>();
  private BigInteger colVal1;
  private BigInteger colMult;

  @Override
  public void prepare(Map map, TopologyContext topologyContext, OutputCollector collector)
  {
    outputCollector = collector;
    String nSquare = (String) map.get(StormConstants.N_SQUARED_KEY);
    nSquared = new BigInteger(nSquare);
    totalFlushSignals = (Long) map.get(StormConstants.ENCROWCALCBOLT_PARALLELISM_KEY);

    logger.info("Initialized EncColMultBolt. ");
  }

  @Override
  public void execute(Tuple tuple)
  {
    if (tuple.getSourceStreamId().equals(StormConstants.ENCROWCALCBOLT_FLUSH_SIG))
    {
      numFlushSignals += 1;
      logger.debug("Received  " + numFlushSignals + " flush signals out of " + totalFlushSignals);

      if (numFlushSignals == totalFlushSignals)
      {
        logger.debug("Received signal to flush in EncColMultBolt. Outputting " + resultsMap.keySet().size() + " results.");
        for (Long key : resultsMap.keySet())
          // key = column Id
          outputCollector.emit(StormConstants.ENCCOLMULTBOLT_ID, new Values(key, resultsMap.get(key)));
        resultsMap.clear();

        outputCollector.emit(StormConstants.ENCCOLMULTBOLT_ID, new Values(new Long(-1), BigInteger.valueOf(0)));
        outputCollector.emit(StormConstants.ENCCOLMULTBOLT_SESSION_END, new Values(1));
        numFlushSignals = 0;
      }
    }
    else
    {
      Long colIndex = tuple.getLongByField(StormConstants.COLUMN_INDEX_ERC_FIELD);
      colVal1 = (BigInteger) tuple.getValueByField(StormConstants.ENCRYPTED_VALUE_FIELD);

      logger.debug("Received tuple in ECM, multiplying " + colVal1 + " to col " + colIndex);

      if (resultsMap.containsKey(colIndex))
      {
        colMult = (colVal1.multiply(resultsMap.get(colIndex))).mod(nSquared);
        resultsMap.put(colIndex, colMult);
      }
      else
      {
        resultsMap.put(colIndex, colVal1);
      }
    }
    outputCollector.ack(tuple);
  }

  @Override
  public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer)
  {
    outputFieldsDeclarer
        .declareStream(StormConstants.ENCCOLMULTBOLT_ID, new Fields(StormConstants.COLUMN_INDEX_ECM_FIELD, StormConstants.COLUMN_PRODUCT_FIELD));
    outputFieldsDeclarer.declareStream(StormConstants.ENCCOLMULTBOLT_SESSION_END, new Fields("finished"));
  }
}
