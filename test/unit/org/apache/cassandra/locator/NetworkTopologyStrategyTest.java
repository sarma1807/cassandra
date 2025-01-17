/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.locator;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.stream.Collectors;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import org.junit.*;
import org.junit.rules.ExpectedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.ServerTestUtils;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.*;
import org.apache.cassandra.dht.Murmur3Partitioner.LongToken;
import org.apache.cassandra.dht.OrderPreservingPartitioner.StringToken;
import org.apache.cassandra.distributed.test.log.ClusterMetadataTestHelper;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.tcm.ClusterMetadataService;
import org.apache.cassandra.service.ClientWarn;
import org.apache.cassandra.tcm.ClusterMetadata;
import org.apache.cassandra.tcm.RegistrationStatus;
import org.apache.cassandra.tcm.compatibility.TokenRingUtils;
import org.apache.cassandra.tcm.membership.Directory;
import org.apache.cassandra.tcm.membership.Location;
import org.apache.cassandra.tcm.membership.NodeId;
import org.apache.cassandra.tcm.ownership.VersionedEndpoints;
import org.apache.cassandra.utils.FBUtilities;

import static org.apache.cassandra.locator.NetworkTopologyStrategy.REPLICATION_FACTOR;
import static org.apache.cassandra.locator.Replica.fullReplica;
import static org.apache.cassandra.locator.Replica.transientReplica;
import static org.apache.cassandra.locator.SimpleLocationProvider.LOCATION;
import static org.junit.Assert.assertTrue;

public class NetworkTopologyStrategyTest
{
    private static final String KEYSPACE = "ks1";
    private static final Logger logger = LoggerFactory.getLogger(NetworkTopologyStrategyTest.class);

    @BeforeClass
    public static void setupDD()
    {
        DatabaseDescriptor.daemonInitialization();
        DatabaseDescriptor.setPartitionerUnsafe(OrderPreservingPartitioner.instance);
        DatabaseDescriptor.setTransientReplicationEnabledUnsafe(true);
        ClusterMetadataService.setInstance(ClusterMetadataTestHelper.instanceForTest());
    }

    @After
    public void teardown()
    {
        ServerTestUtils.resetCMS();
    }

    @Test
    public void testProperties() throws IOException, ConfigurationException
    {
        createDummyTokens(true);

        ClusterMetadataTestHelper.createKeyspace("CREATE KEYSPACE " + KEYSPACE + " WITH REPLICATION = {" +
                                                 "   'class' : 'NetworkTopologyStrategy'," +
                                                 "   'DC1': 3," +
                                                 "   'DC2': 2," +
                                                 "   'DC3': 1" +
                                                 "  } ;");

        NetworkTopologyStrategy strategy = (NetworkTopologyStrategy) ClusterMetadata.current().schema.getKeyspaces().getNullable(KEYSPACE).replicationStrategy;
        Assert.assertEquals(strategy.getReplicationFactor("DC1").allReplicas, 3);
        Assert.assertEquals(strategy.getReplicationFactor("DC2").allReplicas, 2);
        Assert.assertEquals(strategy.getReplicationFactor("DC3").allReplicas, 1);

        // Query for the natural hosts
        VersionedEndpoints.ForToken replicas = ClusterMetadataTestHelper.getNaturalReplicasForToken(KEYSPACE, new StringToken("123"));
        Assert.assertEquals(6, replicas.get().size());
        Assert.assertEquals(6, replicas.get().endpoints().size()); // ensure uniqueness
        Assert.assertEquals(6, new HashSet<>(replicas.get().byEndpoint().values()).size()); // ensure uniqueness
    }

    @Test
    public void testPropertiesWithEmptyDC() throws IOException, ConfigurationException
    {
        createDummyTokens(false);

        Map<String, String> configOptions = new HashMap<>();
        configOptions.put("DC1", "3");
        configOptions.put("DC2", "3");
        configOptions.put("DC3", "0");
        NetworkTopologyStrategy strategy = new NetworkTopologyStrategy(KEYSPACE, configOptions);

        Assert.assertEquals(strategy.getReplicationFactor("DC1").allReplicas, 3);
        Assert.assertEquals(strategy.getReplicationFactor("DC2").allReplicas, 3);
        Assert.assertEquals(strategy.getReplicationFactor("DC3").allReplicas, 0);
        // Query for the natural hosts
        Token token = new StringToken("123");
        EndpointsForToken replicas = strategy.calculateNaturalReplicas(token, ClusterMetadata.current()).forToken(token);
        Assert.assertEquals(6, replicas.size());
        Assert.assertEquals(6, replicas.endpoints().size()); // ensure uniqueness
        Assert.assertEquals(6, new HashSet<>(replicas.byEndpoint().values()).size()); // ensure uniqueness
    }

    @Test
    public void testLargeCluster() throws UnknownHostException, ConfigurationException
    {
        int[] dcRacks = new int[]{2, 4, 8};
        int[] dcEndpoints = new int[]{128, 256, 512};
        int[] dcReplication = new int[]{2, 6, 6};

        Map<String, String> configOptions = new HashMap<String, String>();
        Multimap<InetAddressAndPort, Token> tokens = HashMultimap.create();

        int totalRF = 0;
        for (int dc = 0; dc < dcRacks.length; ++dc)
        {
            totalRF += dcReplication[dc];
            configOptions.put(Integer.toString(dc), Integer.toString(dcReplication[dc]));
            for (int rack = 0; rack < dcRacks[dc]; ++rack)
            {
                for (int ep = 1; ep <= dcEndpoints[dc]/dcRacks[dc]; ++ep)
                {
                    byte[] ipBytes = new byte[]{10, (byte)dc, (byte)rack, (byte)ep};
                    InetAddressAndPort address = InetAddressAndPort.getByAddress(ipBytes);
                    StringToken token = new StringToken(String.format("%02x%02x%02x", ep, rack, dc));
                    logger.debug("adding node {} at {}", address, token);
                    tokens.put(address, token);
                    Location location = RackInferringSnitch.inferLocation(address);
                    ClusterMetadataTestHelper.addEndpoint(address, token, location.datacenter, location.rack);
                }
            }
        }

        NetworkTopologyStrategy strategy = new NetworkTopologyStrategy(KEYSPACE, configOptions);

        for (String testToken : new String[]{"123456", "200000", "000402", "ffffff", "400200"})
        {
            EndpointsForRange replicas = strategy.calculateNaturalReplicas(new StringToken(testToken), ClusterMetadata.current());
            Set<InetAddressAndPort> endpointSet = replicas.endpoints();

            Assert.assertEquals(totalRF, replicas.size());
            Assert.assertEquals(totalRF, new HashSet<>(replicas.byEndpoint().values()).size());
            Assert.assertEquals(totalRF, endpointSet.size());
            logger.debug("{}: {}", testToken, replicas);
        }
    }

    public void createDummyTokens(boolean populateDC3) throws UnknownHostException
    {
        Location l1 = new Location("DC1", "Rack1");
        Location l2 = new Location("DC2", "Rack1");
        Location l3 = new Location("DC3", "Rack1");
        // DC 1
        tokenFactory("123", new byte[]{ 10, 0, 0, 10 }, l1);
        tokenFactory("234", new byte[]{ 10, 0, 0, 11 }, l1);
        tokenFactory("345", new byte[]{ 10, 0, 0, 12 }, l1);
        // Tokens for DC 2
        tokenFactory("789", new byte[]{ 10, 20, 114, 10 }, l2);
        tokenFactory("890", new byte[]{ 10, 20, 114, 11 }, l2);
        //tokens for DC3
        if (populateDC3)
        {
            tokenFactory("456", new byte[]{ 10, 21, 119, 13 }, l3);
            tokenFactory("567", new byte[]{ 10, 21, 119, 10 }, l3);
        }
        // Extra Tokens
        tokenFactory("90A", new byte[]{ 10, 0, 0, 13 }, l1);
        if (populateDC3)
            tokenFactory("0AB", new byte[]{ 10, 21, 119, 14 }, l3);
        tokenFactory("ABC", new byte[]{ 10, 20, 114, 15 }, l2);
    }

    public void tokenFactory(String token, byte[] bytes, Location location) throws UnknownHostException
    {
        InetAddressAndPort addr = InetAddressAndPort.getByAddress(bytes);
        ClusterMetadataTestHelper.addEndpoint(addr, new StringToken(token), location);
    }

    @Test
    public void testCalculateEndpoints() throws UnknownHostException
    {
        final int NODES = 100;
        final int VNODES = 64;
        final int RUNS = 10;
        try (WithPartitioner m3p = new WithPartitioner(Murmur3Partitioner.instance))
        {
            Map<String, Integer> datacenters = ImmutableMap.of("rf1", 1, "rf3", 3, "rf5_1", 5, "rf5_2", 5, "rf5_3", 5);
            List<InetAddressAndPort> nodes = new ArrayList<>(NODES);
            for (byte i = 0; i < NODES; ++i)
                nodes.add(InetAddressAndPort.getByAddress(new byte[]{ 127, 0, 0, i }));
            for (int run = 0; run < RUNS; ++run)
            {
                ServerTestUtils.resetCMS();
                Random rand = new Random(run);
                Locator locator = generateLocator(datacenters, nodes, rand);

                for (int i = 0; i < NODES; ++i)  // Nodes
                {
                    Set<Token> tokens = new HashSet<>();
                    while (tokens.size() < VNODES) // tokens/vnodes per node
                    {
                        tokens.add(Murmur3Partitioner.instance.getRandomToken(rand));
                    }
                    // Here we fake the registration status because we want all the nodes to be registered in cluster
                    // metadata using the locations we setup in generateLocator. This registration occurs as a part of
                    // the addEndpoint call here and behaves as expected for all nodes _except_ the one with the address
                    // which matches the local broadcast address (i.e. 127.0.0.1, which is #2 in the list of nodes).
                    // The location we want this to be registered with is {DC: rf5_1, rack: 3}, but while
                    // RegistrationStatus.instance indicates that the node is yet to be registered, the Locator will
                    // correctly return the initialization location obtained from
                    // DatabaseDescriptor::getInitialLocationProvider, which ultimately resolves to
                    // SimpleLocationProvider (because test/conf/cassandra.yaml specifies use of SimpleSnitch) and so
                    // we register that one node with the location {DC: datacenter1, rack: rack1}.
                    // This is purely an artefact of the contrived testing setup and in more realistic scenarios,
                    // including the majority of tests, isn't an issue.
                    RegistrationStatus.instance.onRegistration();
                    ClusterMetadataTestHelper.addEndpoint(nodes.get(i), tokens, locator.location(nodes.get(i)));
                }
                testEquivalence(ClusterMetadata.current(), locator, datacenters, rand);
            }
        }
    }

    void testEquivalence(ClusterMetadata metadata, Locator locator, Map<String, Integer> datacenters, Random rand)
    {
        NetworkTopologyStrategy nts = new NetworkTopologyStrategy("ks",
                                                                  datacenters.entrySet()
                                                                             .stream()
                                                                             .collect(Collectors.toMap(x -> x.getKey(), x -> Integer.toString(x.getValue()))));
        for (int i=0; i<1000; ++i)
        {
            Token token = Murmur3Partitioner.instance.getRandomToken(rand);
            List<InetAddressAndPort> expected = calculateNaturalEndpoints(token, metadata, datacenters, locator);
            List<InetAddressAndPort> actual = new ArrayList<>(nts.calculateNaturalReplicas(token, metadata).endpoints());
            if (endpointsDiffer(expected, actual))
            {
                System.err.println("Endpoints mismatch for token " + token);
                System.err.println(" expected: " + expected);
                System.err.println(" actual  : " + actual);
                Assert.assertEquals("Endpoints for token " + token + " mismatch.", expected, actual);
            }
        }
    }

    private boolean endpointsDiffer(List<InetAddressAndPort> ep1, List<InetAddressAndPort> ep2)
    {
        // Because the old algorithm does not put the nodes in the correct order in the case where more replicas
        // are required than there are racks in a dc, we accept different order as long as the primary
        // replica is the same.
        if (ep1.equals(ep2))
            return false;
        if (!ep1.get(0).equals(ep2.get(0)))
            return true;
        Set<InetAddressAndPort> s1 = new HashSet<>(ep1);
        Set<InetAddressAndPort> s2 = new HashSet<>(ep2);
        return !s1.equals(s2);
    }

    Locator generateLocator(Map<String, Integer> datacenters, Collection<InetAddressAndPort> nodes, Random rand)
    {
        final Map<NodeId, String> nodeToRack = new HashMap<>();
        final Map<NodeId, String> nodeToDC = new HashMap<>();
        final Map<InetAddressAndPort, NodeId> epToId = new HashMap<>();
        Map<String, List<String>> racksPerDC = new HashMap<>();
        datacenters.forEach((dc, rf) -> racksPerDC.put(dc, randomRacks(rf, rand)));
        int rf = datacenters.values().stream().mapToInt(x -> x).sum();
        String[] dcs = new String[rf];
        int pos = 0;
        for (Map.Entry<String, Integer> dce : datacenters.entrySet())
        {
            for (int i = 0; i < dce.getValue(); ++i)
                dcs[pos++] = dce.getKey();
        }

        int id = 0;
        for (InetAddressAndPort node : nodes)
        {
            String dc = dcs[rand.nextInt(rf)];
            List<String> racks = racksPerDC.get(dc);
            String rack = racks.get(rand.nextInt(racks.size()));
            NodeId nodeId = new NodeId(++id);
            nodeToRack.put(nodeId, rack);
            nodeToDC.put(nodeId, dc);
            epToId.put(node, nodeId);
        }

        Directory dir = new Directory()
        {
            @Override
            public NodeId peerId(InetAddressAndPort endpoint)
            {
                return epToId.get(endpoint);
            }

            @Override
            public Location location(NodeId id)
            {
                return new Location(nodeToDC.get(id), nodeToRack.get(id));
            }
        };
        return Locator.usingDirectory(dir);
    }

    private List<String> randomRacks(int rf, Random rand)
    {
        int rc = rand.nextInt(rf * 3 - 1) + 1;
        List<String> racks = new ArrayList<>(rc);
        for (int i=0; i<rc; ++i)
            racks.add(Integer.toString(i));
        return racks;
    }

    // Copy of older endpoints calculation algorithm for comparison
    public static List<InetAddressAndPort> calculateNaturalEndpoints(Token searchToken, ClusterMetadata metadata, Map<String, Integer> datacenters, Locator locator)
    {
        // we want to preserve insertion order so that the first added endpoint becomes primary
        Set<InetAddressAndPort> replicas = new LinkedHashSet<>();
        // replicas we have found in each DC
        Map<String, Set<InetAddressAndPort>> dcReplicas = new HashMap<>(datacenters.size());
        for (Map.Entry<String, Integer> dc : datacenters.entrySet())
            dcReplicas.put(dc.getKey(), new HashSet<InetAddressAndPort>(dc.getValue()));

        // all endpoints in each DC, so we can check when we have exhausted all the members of a DC
        Multimap<String, InetAddressAndPort> allEndpoints = metadata.directory.allDatacenterEndpoints();
        // all racks in a DC so we can check when we have exhausted all racks in a DC
        Map<String, Multimap<String, InetAddressAndPort>> racks = metadata.directory.allDatacenterRacks();
        assert !allEndpoints.isEmpty() && !racks.isEmpty() : "not aware of any cluster members";

        // tracks the racks we have already placed replicas in
        Map<String, Set<String>> seenRacks = new HashMap<>(datacenters.size());
        for (Map.Entry<String, Integer> dc : datacenters.entrySet())
            seenRacks.put(dc.getKey(), new HashSet<String>());

        // tracks the endpoints that we skipped over while looking for unique racks
        // when we relax the rack uniqueness we can append this to the current result so we don't have to wind back the iterator
        Map<String, Set<InetAddressAndPort>> skippedDcEndpoints = new HashMap<>(datacenters.size());
        for (Map.Entry<String, Integer> dc : datacenters.entrySet())
            skippedDcEndpoints.put(dc.getKey(), new LinkedHashSet<InetAddressAndPort>());

        Iterator<Token> tokenIter = TokenRingUtils.ringIterator(metadata.tokenMap.tokens(), searchToken, false);
        while (tokenIter.hasNext() && !hasSufficientReplicas(dcReplicas, allEndpoints, datacenters))
        {
            Token next = tokenIter.next();
            InetAddressAndPort ep = metadata.directory.endpoint(metadata.tokenMap.owner(next));
            Location location = locator.location(ep);
            String dc = location.datacenter;
            // have we already found all replicas for this dc?
            if (!datacenters.containsKey(dc) || hasSufficientReplicas(dc, dcReplicas, allEndpoints, datacenters))
                continue;
            // can we skip checking the rack?
            if (seenRacks.get(dc).size() == racks.get(dc).keySet().size())
            {
                dcReplicas.get(dc).add(ep);
                replicas.add(ep);
            }
            else
            {
                String rack = location.rack;
                // is this a new rack?
                if (seenRacks.get(dc).contains(rack))
                {
                    skippedDcEndpoints.get(dc).add(ep);
                }
                else
                {
                    dcReplicas.get(dc).add(ep);
                    replicas.add(ep);
                    seenRacks.get(dc).add(rack);
                    // if we've run out of distinct racks, add the hosts we skipped past already (up to RF)
                    if (seenRacks.get(dc).size() == racks.get(dc).keySet().size())
                    {
                        Iterator<InetAddressAndPort> skippedIt = skippedDcEndpoints.get(dc).iterator();
                        while (skippedIt.hasNext() && !hasSufficientReplicas(dc, dcReplicas, allEndpoints, datacenters))
                        {
                            InetAddressAndPort nextSkipped = skippedIt.next();
                            dcReplicas.get(dc).add(nextSkipped);
                            replicas.add(nextSkipped);
                        }
                    }
                }
            }
        }

        return new ArrayList<>(replicas);
    }

    private static boolean hasSufficientReplicas(String dc, Map<String, Set<InetAddressAndPort>> dcReplicas, Multimap<String, InetAddressAndPort> allEndpoints, Map<String, Integer> datacenters)
    {
        return dcReplicas.get(dc).size() >= Math.min(allEndpoints.get(dc).size(), getReplicationFactor(dc, datacenters));
    }

    private static boolean hasSufficientReplicas(Map<String, Set<InetAddressAndPort>> dcReplicas, Multimap<String, InetAddressAndPort> allEndpoints, Map<String, Integer> datacenters)
    {
        for (String dc : datacenters.keySet())
            if (!hasSufficientReplicas(dc, dcReplicas, allEndpoints, datacenters))
                return false;
        return true;
    }

    public static int getReplicationFactor(String dc, Map<String, Integer> datacenters)
    {
        Integer replicas = datacenters.get(dc);
        return replicas == null ? 0 : replicas;
    }

    private static Token tk(long t)
    {
        return new LongToken(t);
    }

    private static Range<Token> range(long l, long r)
    {
        return new Range<>(tk(l), tk(r));
    }

    @Test
    public void testTransientReplica() throws Exception
    {
        try (WithPartitioner m3p = new WithPartitioner(Murmur3Partitioner.instance))
        {
            List<InetAddressAndPort> endpoints = Lists.newArrayList(InetAddressAndPort.getByName("127.0.0.1"),
                                                                    InetAddressAndPort.getByName("127.0.0.2"),
                                                                    InetAddressAndPort.getByName("127.0.0.3"),
                                                                    InetAddressAndPort.getByName("127.0.0.4"));

            ClusterMetadataTestHelper.addEndpoint(endpoints.get(0), tk(100), LOCATION);
            ClusterMetadataTestHelper.addEndpoint(endpoints.get(1), tk(200), LOCATION);
            ClusterMetadataTestHelper.addEndpoint(endpoints.get(2), tk(300), LOCATION);
            ClusterMetadataTestHelper.addEndpoint(endpoints.get(3), tk(400), LOCATION);

            Map<String, String> configOptions = new HashMap<>();
            configOptions.put(LOCATION.datacenter, "3/1");
            NetworkTopologyStrategy strategy = new NetworkTopologyStrategy(KEYSPACE, configOptions);

            Util.assertRCEquals(EndpointsForRange.of(fullReplica(endpoints.get(0), range(400, 100)),
                                                     fullReplica(endpoints.get(1), range(400, 100)),
                                                     transientReplica(endpoints.get(2), range(400, 100))),
                                strategy.calculateNaturalReplicas(tk(99), ClusterMetadata.current()));


            Util.assertRCEquals(EndpointsForRange.of(fullReplica(endpoints.get(1), range(100, 200)),
                                                     fullReplica(endpoints.get(2), range(100, 200)),
                                                     transientReplica(endpoints.get(3), range(100, 200))),
                                strategy.calculateNaturalReplicas(tk(101), ClusterMetadata.current()));
        }
    }

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    @Test
    public void shouldRejectReplicationFactorOption() throws ConfigurationException
    {
        expectedEx.expect(ConfigurationException.class);
        expectedEx.expectMessage(REPLICATION_FACTOR + " should not appear");

        Map<String, String> configOptions = new HashMap<>();
        configOptions.put(REPLICATION_FACTOR, "1");

        @SuppressWarnings("unused") 
        NetworkTopologyStrategy strategy = new NetworkTopologyStrategy("ks", configOptions);
    }

    @Test
    public void shouldWarnOnHigherReplicationFactorThanNodesInDC()
    {
        HashMap<String, String> configOptions = new HashMap<>();
        configOptions.put("DC1", "2");
        NetworkTopologyStrategy strategy = new NetworkTopologyStrategy("ks", configOptions);
        ClusterMetadataTestHelper.addEndpoint(FBUtilities.getBroadcastAddressAndPort(), new StringToken("123"), "DC1", "RACK1");
        ClientWarn.instance.captureWarnings();
        strategy.maybeWarnOnOptions(null);
        assertTrue(ClientWarn.instance.getWarnings().stream().anyMatch(s -> s.contains("Your replication factor")));
    }
}
