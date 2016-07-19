/*******************************************************************************
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
 *******************************************************************************/
package org.apache.pirk.test.utils;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.log4j.Logger;
import org.apache.pirk.encryption.Paillier;
import org.apache.pirk.querier.wideskies.Querier;
import org.apache.pirk.querier.wideskies.QuerierConst;
import org.apache.pirk.querier.wideskies.decrypt.DecryptResponse;
import org.apache.pirk.querier.wideskies.encrypt.EncryptQuery;
import org.apache.pirk.query.wideskies.Query;
import org.apache.pirk.query.wideskies.QueryInfo;
import org.apache.pirk.query.wideskies.QueryUtils;
import org.apache.pirk.responder.wideskies.standalone.Responder;
import org.apache.pirk.response.wideskies.Response;
import org.apache.pirk.schema.response.QueryResponseJSON;
import org.apache.pirk.utils.LogUtils;
import org.apache.pirk.utils.PIRException;
import org.apache.pirk.utils.SystemConfiguration;
import org.json.simple.JSONObject;

public class StandaloneQuery
{
  private static Logger logger = LogUtils.getLoggerForThisClass();

  static String queryFileDomain = "qfDomain";
  static String queryFileIP = "qfIP";
  static String querySideOuputFilePrefix = "querySideOut"; // the file pre-fix for the query side output files
  static String finalResultsFile = "finalResultFile"; // file to hold the final results

  String testDataSchemaName = "testDataSchema";
  String testQuerySchemaName = "testQuerySchema";

  static String responseFile = "encryptedResponse"; // the PIR response file from the responder

  // Base method to perform the query
  public static ArrayList<QueryResponseJSON> performStandaloneQuery(ArrayList<JSONObject> dataElements, String queryType, ArrayList<String> selectors,
      int numThreads, boolean testFalsePositive) throws IOException, InterruptedException, PIRException
  {
    logger.info("Performing watchlisting: ");

    ArrayList<QueryResponseJSON> results = null;

    // Create the necessary files
    File fileQuerier = File.createTempFile(querySideOuputFilePrefix + "-" + QuerierConst.QUERIER_FILETAG, ".txt");
    File fileQuery = File.createTempFile(querySideOuputFilePrefix + "-" + QuerierConst.QUERY_FILETAG, ".txt");
    File fileResponse = File.createTempFile(responseFile, ".txt");
    File fileFinalResults = File.createTempFile(finalResultsFile, ".txt");

    logger.info("fileQuerier = " + fileQuerier.getAbsolutePath() + " fileQuery  = " + fileQuery.getAbsolutePath() + " responseFile = "
        + fileResponse.getAbsolutePath() + " fileFinalResults = " + fileFinalResults.getAbsolutePath());

    boolean embedSelector = SystemConfiguration.getProperty("pirTest.embedSelector", "false").equals("true");
    boolean useExpLookupTable = SystemConfiguration.getProperty("pirTest.useExpLookupTable", "false").equals("true");
    boolean useHDFSExpLookupTable = SystemConfiguration.getProperty("pirTest.useHDFSExpLookupTable", "false").equals("true");

    // Set the necessary objects
    QueryInfo queryInfo = new QueryInfo(BaseTests.queryNum, selectors.size(), BaseTests.hashBitSize, BaseTests.hashKey, BaseTests.dataPartitionBitSize,
        queryType, queryType + "_" + BaseTests.queryNum, BaseTests.paillierBitSize, useExpLookupTable, embedSelector, useHDFSExpLookupTable);

    Paillier paillier = new Paillier(BaseTests.paillierBitSize, BaseTests.certainty);

    // Perform the encryption
    logger.info("Performing encryption of the selectors - forming encrypted query vectors:");
    EncryptQuery encryptQuery = new EncryptQuery(queryInfo, selectors, paillier);
    encryptQuery.encrypt(numThreads);
    logger.info("Completed encryption of the selectors - completed formation of the encrypted query vectors:");

    // Dork with the embedSelectorMap to generate a false positive for the last valid selector in selectors
    if (testFalsePositive)
    {
      Querier querier = encryptQuery.getQuerier();
      HashMap<Integer,String> embedSelectorMap = querier.getEmbedSelectorMap();
      logger.info("embedSelectorMap((embedSelectorMap.size()-2)) = " + embedSelectorMap.get((embedSelectorMap.size() - 2)) + " selector = "
          + selectors.get((embedSelectorMap.size() - 2)));
      embedSelectorMap.put((embedSelectorMap.size() - 2), "fakeEmbeddedSelector");
    }

    // Write necessary output files
    encryptQuery.writeOutputFiles(fileQuerier, fileQuery);

    // Perform the PIR query and build the response elements
    logger.info("Performing the PIR Query and constructing the response elements:");
    Query query = Query.readFromFile(fileQuery);
    Responder pirResponder = new Responder(query);
    logger.info("Query and Responder elements constructed");
    for (JSONObject jsonData : dataElements)
    {
      String selector = QueryUtils.getSelectorByQueryTypeJSON(queryType, jsonData);
      logger.info("selector = " + selector + " numDataElements = " + jsonData.size());
      try
      {
        pirResponder.addDataElement(selector, jsonData);
      } catch (Exception e)
      {
        fail(e.toString());
      }
    }
    logger.info("Completed the PIR Query and construction of the response elements:");

    // Set the response object, extract, write to file
    logger.info("Forming response from response elements; writing to a file");
    pirResponder.setResponseElements();
    Response responseOut = pirResponder.getResponse();
    responseOut.writeToFile(fileResponse);
    logger.info("Completed forming response from response elements and writing to a file");

    // Perform decryption
    // Reconstruct the necessary objects from the files
    logger.info("Performing decryption; writing final results file");
    Response responseIn = Response.readFromFile(fileResponse);
    Querier querier = Querier.readFromFile(fileQuerier);

    // Perform decryption and output the result file
    DecryptResponse decryptResponse = new DecryptResponse(responseIn, querier);
    decryptResponse.decrypt(numThreads);
    decryptResponse.writeResultFile(fileFinalResults);
    logger.info("Completed performing decryption and writing final results file");

    // Read in results
    logger.info("Reading in and checking results");
    results = TestUtils.readResultsFile(fileFinalResults);

    // Clean up
    fileQuerier.delete();
    fileQuery.delete();
    fileResponse.delete();
    fileFinalResults.delete();

    return results;
  }
}
