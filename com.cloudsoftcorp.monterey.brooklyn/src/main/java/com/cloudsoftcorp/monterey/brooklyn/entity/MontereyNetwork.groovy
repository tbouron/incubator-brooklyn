package com.cloudsoftcorp.monterey.brooklyn.entity

import com.cloudsoftcorp.util.Loggers;

import java.util.Map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

import java.io.File
import java.io.IOException
import java.net.URL
import java.util.Collection
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Map
import java.util.Set
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

import brooklyn.entity.basic.AbstractEntity
import brooklyn.entity.trait.Startable
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.location.Location
import brooklyn.location.MachineProvisioningLocation
import brooklyn.location.NoMachinesAvailableException
import brooklyn.location.basic.SshMachineLocation
import brooklyn.util.internal.BrooklynSystemProperties

import com.cloudsoftcorp.monterey.clouds.NetworkId
import com.cloudsoftcorp.monterey.clouds.basic.DeploymentUtils
import com.cloudsoftcorp.monterey.clouds.dto.CloudEnvironmentDto
import com.cloudsoftcorp.monterey.control.api.SegmentSummary
import com.cloudsoftcorp.monterey.control.workrate.api.WorkrateReport
import com.cloudsoftcorp.monterey.location.api.MontereyActiveLocation
import com.cloudsoftcorp.monterey.network.control.api.Dmn1NetworkInfo
import com.cloudsoftcorp.monterey.network.control.api.Dmn1NodeType
import com.cloudsoftcorp.monterey.network.control.api.NodeSummary
import com.cloudsoftcorp.monterey.network.control.deployment.DescriptorLoader
import com.cloudsoftcorp.monterey.network.control.plane.GsonSerializer
import com.cloudsoftcorp.monterey.network.control.plane.web.DeploymentWebProxy
import com.cloudsoftcorp.monterey.network.control.plane.web.Dmn1NetworkInfoWebProxy
import com.cloudsoftcorp.monterey.network.control.plane.web.PingWebProxy
import com.cloudsoftcorp.monterey.network.control.plane.web.PlumberWebProxy
import com.cloudsoftcorp.monterey.network.control.plane.web.UserCredentialsConfig
import com.cloudsoftcorp.monterey.network.deployment.MontereyDeploymentDescriptor
import com.cloudsoftcorp.monterey.network.m.MediationWorkrateItem.MediationWorkrateItemNames
import com.cloudsoftcorp.monterey.node.api.NodeId
import com.cloudsoftcorp.util.Loggers
import com.cloudsoftcorp.util.TimeUtils
import com.cloudsoftcorp.util.exception.ExceptionUtils
import com.cloudsoftcorp.util.exception.RuntimeWrappedException
import com.cloudsoftcorp.util.javalang.ClassLoadingContext
import com.cloudsoftcorp.util.osgi.BundleSet
import com.cloudsoftcorp.util.proc.ProcessExecutionFailureException
import com.cloudsoftcorp.util.web.client.CredentialsConfig
import com.cloudsoftcorp.util.web.server.WebConfig
import com.cloudsoftcorp.util.web.server.WebServer
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.gson.Gson

/**
 * Represents a Monterey network.
 * 
 * @author aled
 */
public class MontereyNetwork extends AbstractEntity implements Startable { // FIXME , AbstractGroup

    /*
     * FIXME Deal with converting from monterey location to Brooklyn location properly
     * FIXME Declare things as effectors
     * FIXME How will this entity be moved? How will its sub-entities be wired back up?
     * TODO  Should this be called MontereyManagementPlane?
     *       Currently starting in a list of locations is confusing - what if some locations 
     *       are machines and others are ProvisioningLocatinos?
     * FIXME Should the provisioning script create and add the cluster? Rather than creating one
     *       per known location?
     */
    
    private final Logger LOG = Loggers.getLogger(MontereyNetwork.class);

    private static final Logger logger = Loggers.getLogger(MontereyNetwork.class);

    public static final BasicAttributeSensor<URL> MANAGEMENT_URL = [ URL.class, "monterey.management-url", "Management URL" ]
    public static final BasicAttributeSensor<String> NETWORK_ID = [ String.class, "monterey.network-id", "Network id" ]
    public static final BasicAttributeSensor<String> APPLICTION_NAME = [ String.class, "monterey.application-name", "Application name" ]

    /** up, down, etc? */
    public static final BasicAttributeSensor<String> STATUS = [ String, "monterey.status", "Status" ]

    private static final int POLL_PERIOD = 1000;
    
    private final Gson gson;

    private String managementNodeInstallDir;
    private String name;
    private Collection<URL> appBundles;
    private URL appDescriptorUrl;
    private MontereyDeploymentDescriptor appDescriptor;
    private CloudEnvironmentDto cloudEnvironmentDto = CloudEnvironmentDto.EMPTY;

    private MontereyNetworkConfig config = new MontereyNetworkConfig();
    private Collection<UserCredentialsConfig> webUsersCredentials;
    private CredentialsConfig webAdminCredential;
    private NetworkId networkId = NetworkId.Factory.newId();

    private SshMachineLocation host;
    private URL managementUrl;
    private MontereyNetworkConnectionDetails connectionDetails;
    private String applicationName;

    private final LocationRegistry locationRegistry = new LocationRegistry();
    private final Map<String,MontereyContainerNode> nodesByCreationId = new ConcurrentHashMap<String,MontereyContainerNode>();
    private final Map<String,Segment> segments = new ConcurrentHashMap<String,Segment>();
    private final Map<Location,Map<Dmn1NodeType,MontereyTypedGroup>> clustersByLocationAndType = new ConcurrentHashMap<Location,Map<Dmn1NodeType,MontereyTypedGroup>>();
    private final Map<Dmn1NodeType,MontereyTypedGroup> typedFabrics = [:];
    private ScheduledFuture<?> monitoringTask;
    
    public MontereyNetwork() {
        ClassLoadingContext classloadingContext = ClassLoadingContext.Defaults.getDefaultClassLoadingContext();
        GsonSerializer gsonSerializer = new GsonSerializer(classloadingContext);
        gson = gsonSerializer.getGson();
    }

    public void setName(String val) {
        this.name = name
    }
    
    public void setAppBundles(Collection<URL> val) {
        this.appBundles = new ArrayList<String>(val)
    }
    
    public void setAppDescriptorUrl(URL val) {
        this.appDescriptorUrl = val
    }
    
    public void setAppDescriptor(MontereyDeploymentDescriptor val) {
        this.appDescriptor = val
    }
    
    public void setCloudEnvironment(CloudEnvironmentDto val) {
        cloudEnvironmentDto = val
    }
        
    public void setManagementNodeInstallDir(String val) {
        this.managementNodeInstallDir = val;
    }

    public void setConfig(MontereyNetworkConfig val) {
        this.config = val;
    }

    public void setWebUsersCredentials(Collection<UserCredentialsConfig> val) {
        this.webUsersCredentials = val;
        this.webAdminCredential = DeploymentUtils.findWebApiAdminCredential(webUsersCredentials);
    }

    public void setWebAdminCredential(CredentialsConfig val) {
        this.webAdminCredential = val;
    }

    public void setNetworkId(NetworkId val) {
        networkId = val;
    }

    // FIXME Use attributes instead of getters
    public String getManagementUrl() {
        return managementUrl;
    }

    public Collection<MontereyContainerNode> getContainerNodes() {
        return ImmutableSet.copyOf(nodesByCreationId.values());
    }

    public Map<NodeId,AbstractMontereyNode> getMontereyNodes() {
        Map<NodeId,AbstractMontereyNode> result = [:]
        nodesByCreationId.values().each {
            result.put(it.getNodeId(), it.getContainedMontereyNode());
        }
        return Collections.unmodifiableMap(result);
    }

    public MontereyTypedGroup getFabric(Dmn1NodeType nodeType) {
        return typedFabrics.get(nodeType);
    }

    public Map<Location, MontereyTypedGroup> getClusters(Dmn1NodeType nodeType) {
        Map<Location, MontereyTypedGroup> result = [:]
        clustersByLocationAndType.each {
            MontereyTypedGroup cluster = it.getValue().getAt(nodeType)
            if (cluster != null) {
                result.put(it.getKey(), cluster)
            }
        }
        return result
    }
    
    public Map<Dmn1NodeType, MontereyTypedGroup> getClusters(Location loc) {
        return clustersByLocationAndType.get(loc)?.asImmutable() ?: [:];
    }
    
    public MediatorGroup getMediatorFabric() {
        return typedFabrics.get(Dmn1NodeType.M);
    }
    
    public Map<Location, MediatorGroup> getMediatorClusters() {
        return getClusters(Dmn1NodeType.M);
    }
    
    public Map<String,Segment> getSegments() {
        return ImmutableMap.copyOf(this.@segments);
    }

    // TODO Use attribute? Method is used to guard call to stop. Or should stop be idempotent?
    public boolean isRunning() {
        return host != null;
    }
    
    @VisibleForTesting    
    LocationRegistry getLocationRegistry() {
        return locationRegistry;
    }

    public void dispose() {
        if (monitoringTask != null) monitoringTask.cancel(true);
    }
    
    @Override
    public void restart() {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public void start(Collection<? extends Location> locs) {
        startInLocation locs
    }

    public void startInLocation(Collection<Location> locs) {
        // TODO how do we deal with different types of location?
        
        SshMachineLocation machineLoc = locs.find({ it instanceof SshMachineLocation });
        MachineProvisioningLocation provisioningLoc = locs.find({ it instanceof MachineProvisioningLocation });
        if (machineLoc) {
            startInLocation(machineLoc)
        } else if (provisioningLoc) {
            startInLocation(provisioningLoc)
        } else {
            throw new IllegalArgumentException("Unsupported location types creating monterey network, $locations")
        }
    }

    public void startInLocation(MachineProvisioningLocation loc) {
        SshMachineLocation machine = loc.obtain()
        if (machine == null) throw new NoMachinesAvailableException(loc)
        startInLocation(machine)
    }

    public void startInLocation(SshMachineLocation host) {
        /*
         * TODO: Assumes the following are already set on SshMachine:
         * sshAddress
         * sshPort
         * sshUsername
         * sshKey/sshKeyFile
         * HostKeyChecking hostKeyChecking = HostKeyChecking.NO;
         */

        LOG.info("Creating new monterey network "+networkId+" on "+host);

        File webUsersConfFile = DeploymentUtils.toEncryptedWebUsersConfFile(webUsersCredentials);
        String username = System.getenv("USER");

        WebConfig web = new WebConfig(true, config.getMontereyWebApiPort(), config.getMontereyWebApiProtocol(), null);
        web.setSslKeystore(managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_SSL_KEYSTORE_RELATIVE_PATH);
        web.setSslKeystorePassword(config.getMontereyWebApiSslKeystorePassword());
        web.setSslKeyPassword(config.getMontereyWebApiSslKeyPassword());
        File webConf = DeploymentUtils.toWebConfFile(web);

        try {
            host.copyTo(webUsersConfFile, managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_WEBUSERS_FILE_RELATIVE_PATH);

            if (config.getLoggingFileOverride() != null) {
                host.copyTo(config.getLoggingFileOverride(), managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_LOGGING_FILE_OVERRIDE_RELATIVE_PATH);
                host.copyTo(config.getLoggingFileOverride(), managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_LOGGING_FILE_RELATIVE_PATH);
            }

            host.copyTo(webConf, managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_WEB_CONF_FILE_RELATIVE_PATH);
            if (config.getMontereyWebApiProtocol().equals(WebServer.HTTPS)) {
                host.copyTo(config.getMontereyWebApiSslKeystore(), managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_SSL_KEYSTORE_RELATIVE_PATH);
            }

            this.managementUrl = new URL(config.getMontereyWebApiProtocol()+"://"+host.getAddress().getHostName()+":"+config.getMontereyWebApiPort());
            this.host = host;

            // Convenient for testing: create the management-node directly in-memory, rather than starting it in a separate process
            // Please leave this commented out code here, to make subsequent debugging easier!
            // Or you could refactor to have a private static final constant that switches the behaviour?
            //            MainArguments mainArgs = new MainArguments(new File(managementNodeInstallDir), null, null, null, null, null, networkId.getId());
            //            new ManagementNodeStarter(mainArgs).start();

            host.run(out: System.out,
                    managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_START_SCRIPT_RELATIVE_PATH+
                    " -address "+host.getAddress().getHostName()+
                    " -port "+Integer.toString(config.getMontereyNodePort())+
                    " -networkId "+networkId.getId()+
                    " -key "+networkId.getId()+
                    " -webConfig "+managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_WEB_CONF_FILE_RELATIVE_PATH+";"+
                    "exit");

            PingWebProxy pingWebProxy = new PingWebProxy(managementUrl.toString(), webAdminCredential,
                    (config.getMontereyWebApiSslKeystore() != null ? config.getMontereyWebApiSslKeystore().getPath() : null),
                    config.getMontereyWebApiSslKeystorePassword());
            boolean reachable = pingWebProxy.waitForReachable(MontereyNetworkConfig.TIMEOUT_FOR_NEW_NETWORK_ON_HOST);
            if (!reachable) {
                throw new IllegalStateException("Management plane not reachable via web-api within "+TimeUtils.makeTimeString(MontereyNetworkConfig.TIMEOUT_FOR_NEW_NETWORK_ON_HOST)+": url="+managementUrl);
            }

            PlumberWebProxy plumberProxy = new PlumberWebProxy(managementUrl, gson, webAdminCredential);
            NodeId controlNodeId = plumberProxy.getControlNodeId();
            
            this.connectionDetails = new MontereyNetworkConnectionDetails(networkId, managementUrl, webAdminCredential, controlNodeId, controlNodeId);
            
            setAttribute MANAGEMENT_URL, managementUrl
            setAttribute NETWORK_ID, networkId.getId()

            // TODO want to call executionContext.scheduleAtFixedRate or some such
            monitoringTask = Executors.newScheduledThreadPool(1).scheduleAtFixedRate({ updateAll() }, POLL_PERIOD, POLL_PERIOD, TimeUnit.MILLISECONDS)

            LOG.info("Created new monterey network: "+connectionDetails);

            if (!appDescriptor) {
                if (appDescriptorUrl) {
                    appDescriptor = DescriptorLoader.loadDescriptor(appDescriptorUrl);
                }
            }
            
            if (appDescriptor) {
                deployCloudEnvironment(cloudEnvironmentDto);
                
                BundleSet bundleSet = (appBundles) ? BundleSet.fromUrls(appBundles) : BundleSet.EMPTY;
                deployApplication(appDescriptor, bundleSet);
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error creating monterey network", e);

            if (BrooklynSystemProperties.DEBUG.isEnabled()) {
                // Not releasing failed instance, because that would make debugging hard!
                LOG.log(Level.WARNING, "Error creating monterey network; leaving failed instance "+host, e);
            } else {
                LOG.log(Level.WARNING, "Error creating monterey network; terminating failed instance "+host, e);
                try {
                    shutdownManagementNodeProcess(config, host, networkId);
                } catch (ProcessExecutionFailureException e2) {
                    LOG.log(Level.WARNING, "Error cleaning up monterey network after failure to start: machine="+host, e2);
                }
            }

            throw new RuntimeWrappedException("Error creating monterey network on "+host, e);
        }
    }

    public void deployCloudEnvironment(CloudEnvironmentDto cloudEnvironmentDto) {
        int DEPLOY_TIMEOUT = 5*60*1000;
        DeploymentWebProxy deployer = new DeploymentWebProxy(managementUrl, gson, webAdminCredential, DEPLOY_TIMEOUT);
        deployer.deployCloudEnvironment(cloudEnvironmentDto);
    }

    public void deployApplication(MontereyDeploymentDescriptor descriptor, BundleSet bundles) {
        int DEPLOY_TIMEOUT = 5*60*1000;
        DeploymentWebProxy deployer = new DeploymentWebProxy(managementUrl, gson, webAdminCredential, DEPLOY_TIMEOUT);
        boolean result = deployer.deployApplication(descriptor, bundles);
    }

    public MontereyContainerNode provisionNode(Collection<Location> locs) {
        if (!locs) {
            throw new IllegalArgumentException("No locations supplied when provisioning nodes, locs=$locs")
        }
        provisionNode(locs.iterator().next())
    }
    
    public MontereyContainerNode provisionNode(Location loc) {
        MontereyContainerNode node = new MontereyContainerNode(connectionDetails);
        nodesByCreationId.put(node.creationId, node);
        addOwnedChild(node);
        node.start([loc]);
        node
    }
    
    public void releaseAllNodes() {
        // TODO Releasing in the right order; but what if revert/rollout is happening concurrently?
        //      Can we delegate to management node, or have brooklyn more aware of what's going on?
        // TODO Release is currently being done sequentially...
        
        List<MontereyContainerNode> torelease = []
        findNodesOfType(Dmn1NodeType.SATELLITE_BOT).each { torelease.add(it.owner) }
        findNodesOfType(Dmn1NodeType.LPP).each { torelease.add(it.owner) }
        findNodesOfType(Dmn1NodeType.M).each { torelease.add(it.owner) }
        findNodesOfType(Dmn1NodeType.MR).each { torelease.add(it.owner) }
        findNodesOfType(Dmn1NodeType.TP).each { torelease.add(it.owner) }
        findNodesOfType(Dmn1NodeType.SPARE).each { torelease.add(it.owner) }
        relativeComplement(nodesByCreationId.values(), torelease).each { torelease.add(torelease) }
        
        for (MontereyContainerNode node : torelease) {
            node.release();
        }
    }

    @Override
    public void stop() {
        // TODO Guard so can only shutdown if network nodes are not running?
        if (host == null) {
            throw new IllegalStateException("Monterey network is not running; cannot stop");
        }
        shutdownManagementNodeProcess(this.config, host, networkId)
        
        // TODO Race: monitoringTask could still be executing, and could get NPE when it tries to get connectionDetails
        if (monitoringTask != null) monitoringTask.cancel(true);
        
        host = null;
        managementUrl = null;
        connectionDetails = null;
        applicationName = null;
    }

    private void shutdownManagementNodeProcess(MontereyNetworkConfig config, SshMachineLocation host, NetworkId networkId) {
        String killScript = managementNodeInstallDir+"/"+MontereyNetworkConfig.MANAGER_SIDE_KILL_SCRIPT_RELATIVE_PATH;
        try {
            LOG.info("Releasing management node on "+toString());
            host.run(out: System.out,
                    killScript+" -key "+networkId.getId()+";"+
                    "exit");

        } catch (IllegalStateException e) {
            if (e.toString().contains("No such process")) {
                // the process hadn't started or was killed externally? Our work is done.
                LOG.info("Management node process not running; termination is a no-op: networkId="+networkId+"; machine="+host);
            } else {
                LOG.log(Level.WARNING, "Error termining monterey management node process: networkId="+networkId+"; machine="+host, e);
            }
        } catch (ProcessExecutionFailureException e) {
            LOG.log(Level.WARNING, "Error termining monterey management node process: networkId="+networkId+"; machine="+host, e);

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error termining monterey management node process: networkId="+networkId+"; machine="+host, e);
        }
    }

    private void updateAll() {
        try {
            boolean isup = updateStatus();
            if (isup) {
                updateAppName();
                updateTopology();
                updateWorkrates();
            }
        } catch (Throwable t) {
            LOG.log Level.WARNING, "Error updating brooklyn entities of Monterey Network "+managementUrl, t
            ExceptionUtils.throwRuntime t
        }
    }

    private boolean updateStatus() {
        PingWebProxy pinger = new PingWebProxy(connectionDetails.getManagementUrl(), connectionDetails.getWebApiAdminCredential());
        boolean isup = pinger.ping();
        String status = (isup) ? "UP" : "DOWN";
        setAttribute(STATUS, status);
        return isup;
    }
    
    private void updateAppName() {
        DeploymentWebProxy deployer = new DeploymentWebProxy(connectionDetails.getManagementUrl(), gson, connectionDetails.getWebApiAdminCredential());
        MontereyDeploymentDescriptor currentApp = deployer.getApplicationDeploymentDescriptor();
        String currentAppName = currentApp?.getName();
        if (!(applicationName != null ? applicationName.equals(currentAppName) : currentAppName == null)) {
            applicationName = currentAppName;
            setAttribute(APPLICTION_NAME, applicationName);
        }
    }
    
//    private NodeSummary 
    private void updateTopology() {
        Dmn1NetworkInfo networkInfo = new Dmn1NetworkInfoWebProxy(connectionDetails.getManagementUrl(), gson, connectionDetails.getWebApiAdminCredential());
        Collection<MontereyActiveLocation> montereyLocations = networkInfo.getActiveLocations();
        Collection<Location> locations = montereyLocations.collect { locationRegistry.getConvertedLocation(it) }
        
        // Update locations, if they've changed
        if (!this.locations.equals(locations)) {
            this.locations.clear();
            this.locations.addAll(locations);
        }
        
        updateFabricTopologies();
        updateClusterTopologies();
        updateNodeTopologies();
        updateSegmentTopologies();
    }

    private void updateFabricTopologies() {
        // Create fabrics (if have not already done so)
        if (typedFabrics.isEmpty()) {
            typedFabrics.put(Dmn1NodeType.LPP, MontereyTypedGroup.newAllLocationsInstance(connectionDetails, Dmn1NodeType.LPP, locations));
            typedFabrics.put(Dmn1NodeType.MR, MontereyTypedGroup.newAllLocationsInstance(connectionDetails, Dmn1NodeType.MR, locations));
            typedFabrics.put(Dmn1NodeType.M, MediatorGroup.newAllLocationsInstance(connectionDetails, locations));
            typedFabrics.put(Dmn1NodeType.TP, MontereyTypedGroup.newAllLocationsInstance(connectionDetails, Dmn1NodeType.TP, locations));
            
            typedFabrics.values().each { addOwnedChild(it) }
        }
        
        typedFabrics.values().each {
            it.refreshLocations(locations);
        }
    }
    
    private void updateClusterTopologies() {
        // Create/destroy clusters
        Collection<Location> newLocations = []
        Collection<Location> removedLocations = []
        
        newLocations.addAll(locations); newLocations.removeAll(clustersByLocationAndType.keySet());
        removedLocations.addAll(clustersByLocationAndType.keySet()); removedLocations.removeAll(locations);

        newLocations.each { Location loc ->
            Map<Dmn1NodeType,MontereyTypedGroup> clustersByType = [:]
            clustersByLocationAndType.put(loc, clustersByType)

            clustersByType.put(Dmn1NodeType.LPP, MontereyTypedGroup.newSingleLocationInstance(connectionDetails, Dmn1NodeType.LPP, loc));
            clustersByType.put(Dmn1NodeType.MR, MontereyTypedGroup.newSingleLocationInstance(connectionDetails, Dmn1NodeType.MR, loc));
            clustersByType.put(Dmn1NodeType.M, MediatorGroup.newSingleLocationInstance(connectionDetails, loc));
            clustersByType.put(Dmn1NodeType.TP, MontereyTypedGroup.newSingleLocationInstance(connectionDetails, Dmn1NodeType.TP, loc));

            clustersByType.values().each { it.setOwner(typedFabrics.get(it.nodeType)) }
        }

        removedLocations.each { Location loc ->
            Map<Dmn1NodeType,MontereyTypedGroup> clustersByType = clustersByLocationAndType.remove(loc)
            clustersByType?.values().each { MontereyTypedGroup cluster ->
                cluster.dispose();
                removeOwnedChild(cluster);
            }
        }
    }
    
    private void updateNodeTopologies() {
        Dmn1NetworkInfo networkInfo = new Dmn1NetworkInfoWebProxy(connectionDetails.getManagementUrl(), gson, connectionDetails.getWebApiAdminCredential());
        Map<NodeId, NodeSummary> nodeSummaries = networkInfo.getNodeSummaries();
        Map<NodeId,Collection<NodeId>> downstreamNodes = networkInfo.getTopology().getAllTargets();
        
        // Create/destroy nodes that have been added/removed
        Collection<NodeSummary> newNodes = []
        Collection<String> removedNodes = []
        nodeSummaries.values().each {
            if (!nodesByCreationId.containsKey(it.creationUid)) {
                newNodes.add(it)
            }
            removedNodes.remove(it.creationUid)
        }

        newNodes.each {
            // Node started externally
            MontereyActiveLocation montereyLocation = it.getMontereyActiveLocation();
            Location location = locationRegistry.getConvertedLocation(montereyLocation);
            MontereyContainerNode containerNode = new MontereyContainerNode(connectionDetails, it.creationUid);
            containerNode.connectToExisting(it, location)
            addOwnedChild(containerNode);
            nodesByCreationId.put(it.creationUid, containerNode);
        }

        removedNodes.each {
            MontereyContainerNode node = nodesByCreationId.remove(it)
            if (node != null) {
                node.dispose();
                removeOwnedChild(node);
            }
        }
        
        // Notify "container nodes" (i.e. BasicNode in monterey classes jargon) of what node-types are running there
        nodeSummaries.values().each {
            nodesByCreationId.get(it.creationUid)?.updateContents(it, downstreamNodes.get(it.getNodeId()));
        }
    }
    
    private void updateSegmentTopologies() {
        Dmn1NetworkInfo networkInfo = new Dmn1NetworkInfoWebProxy(connectionDetails.getManagementUrl(), gson, connectionDetails.getWebApiAdminCredential());
        Map<String, SegmentSummary> segmentSummaries = networkInfo.getSegmentSummaries();
        Map<String, NodeId> segmentAllocations = networkInfo.getSegmentAllocations();
        
        // Create/destroy segments
        Collection<String> newSegments = []
        Collection<String> removedSegments = []
        newSegments.addAll(segmentSummaries.keySet()); newSegments.removeAll(segments.keySet());
        removedSegments.addAll(segments.keySet()); removedSegments.removeAll(segmentSummaries.keySet());

        newSegments.each {
            Segment segment = new Segment(connectionDetails, it);
            addOwnedChild(segment);
            this.@segments.put(it, segment);
        }

        removedSegments.each {
            Segment segment = this.@segments.remove(it);
            if (segment != null) {
                segment.dispose();
                removeOwnedChild(segment);
            }
        }

        // Notify segments of their mediator
        segments.values().each {
            String segmentId = it.segmentId()
            SegmentSummary summary = segmentSummaries.get(segmentId);
            NodeId mediator = segmentAllocations.get(segmentId);
            it.updateTopology(summary, mediator);
        }
    }
    
    private void updateWorkrates() {
        Dmn1NetworkInfo networkInfo = new Dmn1NetworkInfoWebProxy(connectionDetails.getManagementUrl(), gson, connectionDetails.getWebApiAdminCredential());
        Map<NodeId, WorkrateReport> workrates = networkInfo.getActivityModel().getAllWorkrateReports();

        workrates.entrySet().each {
            WorkrateReport report = it.getValue();

            // Update this node's workrate
            findNode(it.getKey())?.updateWorkrate(report);

            // Update each segment's workrate (if a mediator's segment-workrate item is contained here)
            report.getWorkrateItems().each {
                String itemName = it.getName()
                if (MediationWorkrateItemNames.isNameForSegment(itemName)) {
                    String segmentId = MediationWorkrateItemNames.segmentFromName(itemName);
                    segments.get(segmentId)?.updateWorkrate(report);
                }
            }
        }
    }
    
    private Collection<AbstractMontereyNode> findNodesOfType(Dmn1NodeType type) {
        Collection<AbstractMontereyNode> result = []
        montereyNodes.values().each {
            if (type == it.nodeType) result.add(it)
        }
        return result
    }

    private MontereyContainerNode findNode(NodeId nodeId) {
        MontereyContainerNode result
        nodesByCreationId.values().each { if (nodeId.equals(it.nodeId)) result = it }
        return result
    }

    private static <T> Set<T> union(Collection<T> col1, Collection<T> col2) {
        Set<T> result = new LinkedHashSet<T>(col1);
        result.addAll(col2);
        return result;
    }
    
    /**
     * The relative complement of A with respect to a set B, is the set of elements in B but not in A.
     * Therefore, returns the elements that are in col but that are not in other.
     */
    private static <T> Collection<T> relativeComplement(Collection<T> col, Collection<?> other) {
        Set<T> result = new LinkedHashSet<T>(col);
        result.removeAll(other);
        return Collections.unmodifiableSet(result);
    }
    
}
