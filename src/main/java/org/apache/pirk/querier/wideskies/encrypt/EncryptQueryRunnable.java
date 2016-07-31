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
package org.apache.pirk.querier.wideskies.encrypt;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.TreeMap;

import org.apache.pirk.encryption.Paillier;
import org.apache.pirk.utils.PIRException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runnable class for multithreaded PIR encryption
 *
 */
public class EncryptQueryRunnable implements Runnable
{
  private static final Logger logger = LoggerFactory.getLogger(EncryptQueryRunnable.class);

  private int dataPartitionBitSize = 0;
  private int start = 0; // start of computing range for the runnable
  private int stop = 0; // stop, inclusive, of the computing range for the runnable

  private Paillier paillier = null;
  private HashMap<Integer,Integer> selectorQueryVecMapping = null;

  private TreeMap<Integer,BigInteger> encryptedValues = null; // holds the ordered encrypted values to pull after thread computation is complete

  public EncryptQueryRunnable(int dataPartitionBitSizeInput, Paillier paillierInput, HashMap<Integer,Integer> selectorQueryVecMappingInput, int startInput,
      int stopInput)
  {
    dataPartitionBitSize = dataPartitionBitSizeInput;

    paillier = paillierInput;
    selectorQueryVecMapping = selectorQueryVecMappingInput;

    start = startInput;
    stop = stopInput;

    encryptedValues = new TreeMap<Integer,BigInteger>();
  }

  /**
   * Method to get this runnables encrypted values
   * <p>
   * To be called once the thread computation is complete
   */
  public TreeMap<Integer,BigInteger> getEncryptedValues()
  {
    return encryptedValues;
  }

  @Override
  public void run()
  {
    for (int i = start; i <= stop; i++)
    {
      Integer selectorNum = selectorQueryVecMapping.get(i);
      BigInteger valToEnc = (selectorNum == null) ? BigInteger.ZERO : (BigInteger.valueOf(2)).pow(selectorNum * dataPartitionBitSize);
      BigInteger encVal;
      try
      {
        encVal = paillier.encrypt(valToEnc);
      } catch (PIRException e)
      {
        throw new RuntimeException(e);
      }
      encryptedValues.put(i, encVal);
      logger.debug("selectorNum = " + selectorNum + " valToEnc = " + valToEnc + " envVal = " + encVal);
    }
  }
}
