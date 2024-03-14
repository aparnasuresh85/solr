/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.client.solrj;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.LBHttp2SolrClient;
import org.apache.solr.client.solrj.impl.LBSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrResponseBase;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.embedded.JettyConfig;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.util.LogLevel;
import org.apache.solr.util.LogListener;
import org.apache.solr.util.TimeOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test for LBHttp2SolrClient
 *
 * @since solr 1.4
 */
public class TestLBHttp2SolrClient extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  SolrInstance[] solr = new SolrInstance[3];
  Http2SolrClient httpClient;

  // TODO: fix this test to not require FSDirectory
  static String savedFactory;

  @BeforeClass
  public static void beforeClass() {
    savedFactory = System.getProperty("solr.DirectoryFactory");
    System.setProperty("solr.directoryFactory", "org.apache.solr.core.MockFSDirectoryFactory");
    System.setProperty("tests.shardhandler.randomSeed", Long.toString(random().nextLong()));
  }

  @AfterClass
  public static void afterClass() {
    if (savedFactory == null) {
      System.clearProperty("solr.directoryFactory");
    } else {
      System.setProperty("solr.directoryFactory", savedFactory);
    }
    System.clearProperty("tests.shardhandler.randomSeed");
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    httpClient =
        new Http2SolrClient.Builder()
            .withConnectionTimeout(1000, TimeUnit.MILLISECONDS)
            .withIdleTimeout(2000, TimeUnit.MILLISECONDS)
            .build();

    for (int i = 0; i < solr.length; i++) {
      solr[i] =
          new SolrInstance("solr/collection1" + i, createTempDir("instance-" + i).toFile(), 0);
      solr[i].setUp();
      solr[i].startJetty();
      addDocs(solr[i]);
    }
  }

  private void addDocs(SolrInstance solrInstance) throws IOException, SolrServerException {
    List<SolrInputDocument> docs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", i);
      doc.addField("name", solrInstance.name);
      docs.add(doc);
    }
    SolrResponseBase resp;
    try (SolrClient client = getHttpSolrClient(solrInstance.getUrl())) {
      resp = client.add(docs);
      assertEquals(0, resp.getStatus());
      resp = client.commit();
      assertEquals(0, resp.getStatus());
    }
  }

  @Override
  public void tearDown() throws Exception {
    for (SolrInstance aSolr : solr) {
      if (aSolr != null) {
        aSolr.tearDown();
      }
    }
    httpClient.close();
    super.tearDown();
  }

  public void testSimple() throws Exception {
    String[] solrUrls = new String[solr.length];
    for (int i = 0; i < solr.length; i++) {
      solrUrls[i] = solr[i].getUrl();
    }
    try (LBHttp2SolrClient client = createTestClient(solrUrls, 0)) {
      SolrQuery solrQuery = new SolrQuery("*:*");
      Set<String> names = new HashSet<>();
      QueryResponse resp = null;
      for (String ignored : solrUrls) {
        resp = client.query(solrQuery);
        assertEquals(10, resp.getResults().getNumFound());
        names.add(resp.getResults().get(0).getFieldValue("name").toString());
      }
      assertEquals(3, names.size());

      // Kill a server and test again
      solr[1].jetty.stop();
      solr[1].jetty = null;
      names.clear();
      for (String ignored : solrUrls) {
        resp = client.query(solrQuery);
        assertEquals(10, resp.getResults().getNumFound());
        names.add(resp.getResults().get(0).getFieldValue("name").toString());
      }
      assertEquals(2, names.size());
      assertFalse(names.contains("solr1"));

      // Start the killed server once again
      solr[1].startJetty();
      // Wait for the alive check to complete
      Thread.sleep(1200);
      names.clear();
      for (String ignored : solrUrls) {
        resp = client.query(solrQuery);
        assertEquals(10, resp.getResults().getNumFound());
        names.add(resp.getResults().get(0).getFieldValue("name").toString());
      }
      assertEquals(3, names.size());
    }
  }

  private LBHttp2SolrClient getLBHttp2SolrClient(Http2SolrClient httpClient, String... s) {
    return new LBHttp2SolrClient.Builder(httpClient, s).build();
  }

  public void testTwoServers() throws Exception {
    String[] solrUrls = new String[2];
    for (int i = 0; i < 2; i++) {
      solrUrls[i] = solr[i].getUrl();
    }
    try (LBHttp2SolrClient client = createTestClient(solrUrls, 0)) {
      SolrQuery solrQuery = new SolrQuery("*:*");
      QueryResponse resp = null;
      solr[0].jetty.stop();
      solr[0].jetty = null;
      resp = client.query(solrQuery);
      String name = resp.getResults().get(0).getFieldValue("name").toString();
      assertEquals("solr/collection11", name);
      resp = client.query(solrQuery);
      name = resp.getResults().get(0).getFieldValue("name").toString();
      assertEquals("solr/collection11", name);
      solr[1].jetty.stop();
      solr[1].jetty = null;
      solr[0].startJetty();
      Thread.sleep(1200);
      try {
        resp = client.query(solrQuery);
      } catch (SolrServerException e) {
        // try again after a pause in case the error is lack of time to start server
        Thread.sleep(3000);
        resp = client.query(solrQuery);
      }
      name = resp.getResults().get(0).getFieldValue("name").toString();
      assertEquals("solr/collection10", name);
    }
  }

  @LogLevel("org.apache.solr.client.solrj.impl.LBSolrClient=DEBUG")
  public void testReliabilityWithLivenessChecks() throws Exception {
    LogReliabilityTestSetup logSetup = new LogReliabilityTestSetup(solr[1].getUrl());
    testReliabilityCommon(0);
    assertTrue(logSetup.getPingChecksOnServer().pollMessage().length() > 0);
    assertTrue(logSetup.getSkippedCheckLogs().pollMessage() == null);
    assertTrue(logSetup.getSuccessfulPingChecks().pollMessage().length() > 0);
  }

  @LogLevel("org.apache.solr.client.solrj.impl.LBHttp2SolrClient=DEBUG")
  public void testReliabilityWithDelayedLivenessChecks() throws Exception {
    LogReliabilityTestSetup logSetup = new LogReliabilityTestSetup(solr[1].getUrl());
    testReliabilityCommon(3);

    assertTrue(logSetup.getSkippedCheckLogs().pollMessage().length() > 0);
    assertTrue(logSetup.getPingChecksOnServer().pollMessage().length() > 0);
    assertTrue(logSetup.getSuccessfulPingChecks().pollMessage().length() > 0);
  }

  private void testReliabilityCommon(int aliveCheckSkipIters) throws Exception {
    String[] solrUrls = new String[solr.length];
    for (int i = 0; i < solr.length; i++) {
      solrUrls[i] = solr[i].getUrl();
    }
    try (LBHttp2SolrClient client = createTestClient(solrUrls, aliveCheckSkipIters)) {

      // Kill a server and test again
      solr[1].jetty.stop();
      solr[1].jetty = null;

      // query the servers
      for (String ignored : solrUrls) client.query(new SolrQuery("*:*"));

      // Start the killed server once again
      solr[1].startJetty();

      // Wait for the alive check to complete
      waitForServer(30, client, 3, solr[1].name);
    }
  }

  private LBHttp2SolrClient createTestClient(String[] solrUrls, int aliveCheckSkipIters) {
    LBHttp2SolrClient.Builder builder =
        new LBHttp2SolrClient.Builder(httpClient, solrUrls)
            .setAliveCheckInterval(100, TimeUnit.MILLISECONDS)
            .setAliveCheckSkipIters(aliveCheckSkipIters);
    if (aliveCheckSkipIters > 0) {
      builder = builder.setAliveCheckSkipIters(aliveCheckSkipIters);
    }

    return builder.build();
  }

  // wait maximum ms for serverName to come back up
  private void waitForServer(
      int maxSeconds, LBHttp2SolrClient client, int nServers, String serverName) throws Exception {
    final TimeOut timeout = new TimeOut(maxSeconds, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    while (!timeout.hasTimedOut()) {
      QueryResponse resp;
      try {
        resp = client.query(new SolrQuery("*:*"));
      } catch (Exception e) {
        log.warn("", e);
        continue;
      }
      String name = resp.getResults().get(0).getFieldValue("name").toString();
      if (name.equals(serverName)) return;

      Thread.sleep(500);
    }
  }

  public class LogReliabilityTestSetup {

    private final LogListener pingChecksOnServer;
    private final LogListener skippedCheckLogs;
    private final LogListener successfulPingChecks;

    public LogReliabilityTestSetup(String serverUrl) {
      this.pingChecksOnServer =
          LogListener.debug(LBSolrClient.class)
              .substring("Running ping check on server " + serverUrl);
      this.skippedCheckLogs =
          LogListener.debug(LBSolrClient.class)
              .substring("Skipping liveness check for server " + serverUrl);
      this.successfulPingChecks =
          LogListener.debug(LBSolrClient.class)
              .substring("Successfully pinged server " + serverUrl);
    }

    public LogListener getPingChecksOnServer() {
      return pingChecksOnServer;
    }

    public LogListener getSkippedCheckLogs() {
      return skippedCheckLogs;
    }

    public LogListener getSuccessfulPingChecks() {
      return successfulPingChecks;
    }
  }

  private static class SolrInstance {
    String name;
    File homeDir;
    File dataDir;
    File confDir;
    int port;
    JettySolrRunner jetty;

    public SolrInstance(String name, File homeDir, int port) {
      this.name = name;
      this.homeDir = homeDir;
      this.port = port;

      dataDir = new File(homeDir + "/collection1", "data");
      confDir = new File(homeDir + "/collection1", "conf");
    }

    public String getHomeDir() {
      return homeDir.toString();
    }

    public String getUrl() {
      return buildUrl(port) + "/collection1";
    }

    public String getSchemaFile() {
      return "solrj/solr/collection1/conf/schema-replication1.xml";
    }

    public String getConfDir() {
      return confDir.toString();
    }

    public String getDataDir() {
      return dataDir.toString();
    }

    public String getSolrConfigFile() {
      return "solrj/solr/collection1/conf/solrconfig-follower1.xml";
    }

    public String getSolrXmlFile() {
      return "solrj/solr/solr.xml";
    }

    public void setUp() throws Exception {
      homeDir.mkdirs();
      dataDir.mkdirs();
      confDir.mkdirs();

      Files.copy(
          SolrTestCaseJ4.getFile(getSolrXmlFile()).toPath(), homeDir.toPath().resolve("solr.xml"));

      Path f = confDir.toPath().resolve("solrconfig.xml");
      Files.copy(SolrTestCaseJ4.getFile(getSolrConfigFile()).toPath(), f);
      f = confDir.toPath().resolve("schema.xml");
      Files.copy(SolrTestCaseJ4.getFile(getSchemaFile()).toPath(), f);
      Files.createFile(homeDir.toPath().resolve("collection1/core.properties"));
    }

    public void tearDown() throws Exception {
      if (jetty != null) jetty.stop();
      IOUtils.rm(homeDir.toPath());
    }

    public void startJetty() throws Exception {

      Properties props = new Properties();
      props.setProperty("solrconfig", "bad_solrconfig.xml");
      props.setProperty("solr.data.dir", getDataDir());

      JettyConfig jettyConfig = JettyConfig.builder(buildJettyConfig()).setPort(port).build();

      jetty = new JettySolrRunner(getHomeDir(), props, jettyConfig);
      jetty.start();
      int newPort = jetty.getLocalPort();
      if (port != 0 && newPort != port) {
        fail("TESTING FAILURE: could not grab requested port.");
      }
      this.port = newPort;
      //      System.out.println("waiting.........");
      //      Thread.sleep(5000);
    }
  }
}
