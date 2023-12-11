package io.quarkiverse.langchain4j.milvus;

import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.runtime.LaunchMode;
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class MilvusDevServicesProcessor {

    private static final Logger log = Logger.getLogger(MilvusDevServicesProcessor.class);

    /**
     * Label to add to shared Dev Service for Chroma running in containers.
     * This allows other applications to discover the running service and use it instead of starting a new instance.
     */
    private static final String DEV_SERVICE_LABEL = "quarkus-dev-service-milvus";

    private static final String ETCD_IMAGE_NAME = "docker.io/coreos/etcd";
    private static final String MINIO_IMAGE_NAME = "docker.io/minio/minio";
    private static final String MILVUS_IMAGE_NAME = "docker.io/milvusdb/milvus";

    private static final int MILVUS_PORT = 19530;

    private static final ContainerLocator containerLocator = new ContainerLocator(DEV_SERVICE_LABEL, MILVUS_PORT);
    static volatile DevServicesResultBuildItem.RunningDevService milvusDevService;
    static volatile DevServicesResultBuildItem.RunningDevService minioDevService;
    static volatile DevServicesResultBuildItem.RunningDevService etcdDevService;
    static volatile MilvusDevServiceCfg cfg;
    static volatile boolean first = true;

    @BuildStep
    public List<DevServicesResultBuildItem> startMilvusDevServices(
            DockerStatusBuildItem dockerStatusBuildItem,
            LaunchModeBuildItem launchMode,
            MilvusBuildConfig chromaBuildConfig,
            Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem,
            LoggingSetupBuildItem loggingSetupBuildItem,
            GlobalDevServicesConfig devServicesConfig) {

        List<DevServicesResultBuildItem> result = new ArrayList<>();
        MilvusDevServiceCfg configuration = getConfiguration(chromaBuildConfig);

        if (milvusDevService != null || etcdDevService != null || minioDevService != null) {
            boolean shouldShutdown = !configuration.equals(cfg);
            if (!shouldShutdown) {
                result.add(milvusDevService.toBuildItem());
                result.add(etcdDevService.toBuildItem());
                result.add(minioDevService.toBuildItem());
                return result;
            }
            shutdownContainers();
            cfg = null;
        }

        StartupLogCompressor compressor = new StartupLogCompressor(
                (launchMode.isTest() ? "(test) " : "") + "Milvus Dev Services Starting:", consoleInstalledBuildItem,
                loggingSetupBuildItem);
        try {
            DevServicesResultBuildItem.RunningDevService newMilvusDevService = startMilvusContainer(
                    dockerStatusBuildItem, configuration, launchMode,
                    !devServicesSharedNetworkBuildItem.isEmpty(), devServicesConfig.timeout);
            if (newMilvusDevService != null) {
                milvusDevService = newMilvusDevService;
                if (milvusDevService.isOwner()) {
                    log.info("Dev Services instance of Milvus started.");
                }
            }
            if (milvusDevService == null) {
                compressor.closeAndDumpCaptured();
            } else {
                compressor.close();
            }
            DevServicesResultBuildItem.RunningDevService newEtcdDevService = startEtcdContainer(
                    dockerStatusBuildItem, configuration, launchMode,
                    !devServicesSharedNetworkBuildItem.isEmpty(), devServicesConfig.timeout);
            if (newEtcdDevService != null) {
                etcdDevService = newEtcdDevService;
                if (etcdDevService.isOwner()) {
                    log.info("Dev Services instance of Etcd started.");
                }
            }
            if (etcdDevService == null) {
                compressor.closeAndDumpCaptured();
            } else {
                compressor.close();
            }
            DevServicesResultBuildItem.RunningDevService newMinioDevService = startMinioContainer(
                    dockerStatusBuildItem, configuration, launchMode,
                    !devServicesSharedNetworkBuildItem.isEmpty(), devServicesConfig.timeout);
            if (newMinioDevService != null) {
                minioDevService = newMinioDevService;
                if (minioDevService.isOwner()) {
                    log.info("Dev Services instance of Minio started.");
                }
            }
            if (minioDevService == null) {
                compressor.closeAndDumpCaptured();
            } else {
                compressor.close();
            }
        } catch (Throwable t) {
            compressor.closeAndDumpCaptured();
            throw new RuntimeException(t);
        }

        if (milvusDevService == null || etcdDevService == null || minioDevService == null) {
            return Collections.emptyList();
        }

        // Configure the watch dog
        if (first) {
            first = false;
            Runnable closeTask = () -> {
                    shutdownContainers();
                    first = true;
                    cfg = null;
            };
            QuarkusClassLoader cl = (QuarkusClassLoader) Thread.currentThread().getContextClassLoader();
            ((QuarkusClassLoader) cl.parent()).addCloseTask(closeTask);
        }
        cfg = configuration;
        result.add(milvusDevService.toBuildItem());
        result.add(etcdDevService.toBuildItem());
        result.add(minioDevService.toBuildItem());
        return result;
    }

    private void shutdownContainers() {
        if (milvusDevService != null) {
            try {
                milvusDevService.close();
            } catch (Throwable e) {
                log.error("Failed to stop the Milvus server", e);
            } finally {
                milvusDevService = null;
            }
        }
        if (etcdDevService != null) {
            try {
                etcdDevService.close();
            } catch (Throwable e) {
                log.error("Failed to stop the Etcd server", e);
            } finally {
                etcdDevService = null;
            }
        }
        if (minioDevService != null) {
            try {
                minioDevService.close();
            } catch (Throwable e) {
                log.error("Failed to stop the Minio server", e);
            } finally {
                minioDevService = null;
            }
        }
    }

    private DevServicesResultBuildItem.RunningDevService startMilvusContainer(DockerStatusBuildItem dockerStatusBuildItem,
            MilvusDevServiceCfg config, LaunchModeBuildItem launchMode,
            boolean useSharedNetwork, Optional<Duration> timeout) {
        if (!config.devServicesEnabled) {
            // explicitly disabled
            log.debug("Not starting Dev Services for Chroma, as it has been disabled in the config.");
            return null;
        }

        if (!dockerStatusBuildItem.isDockerAvailable()) {
            log.warn("Docker isn't working, please configure the Milvus server location.");
            return null;
        }

        ConfiguredMilvusContainer container = new ConfiguredMilvusContainer(
                DockerImageName.parse(config.milvusImageName).asCompatibleSubstituteFor(MILVUS_IMAGE_NAME),
                config.fixedMilvusPort,
                launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT ? config.serviceName : null,
                useSharedNetwork);

        final Supplier<DevServicesResultBuildItem.RunningDevService> defaultMilvusSupplier = () -> {

            // Starting the broker
            timeout.ifPresent(container::withStartupTimeout);
            container.addEnv("ETCD_ENDPOINTS", "etcd:2379");
            container.addEnv("MINIO_ADDRESS", "minio:9000");
            container.start();
            return getRunningMilvusDevService(
                    container.getContainerId(),
                    container::close,
                    container.getHost(),
                    container.getPort());
        };

        return containerLocator
                .locateContainer(
                        config.serviceName,
                        config.shared,
                        launchMode.getLaunchMode())
                .map(containerAddress -> getRunningMilvusDevService(
                        containerAddress.getId(),
                        null,
                        containerAddress.getHost(),
                        containerAddress.getPort()))
                .orElseGet(defaultMilvusSupplier);
    }

    private DevServicesResultBuildItem.RunningDevService getRunningMilvusDevService(
            String containerId,
            Closeable closeable,
            String host,
            int port) {
        Map<String, String> configMap = Map.of("quarkus.langchain4j.chroma.url", "http://" + host + ":" + port);
        return new DevServicesResultBuildItem.RunningDevService(MilvusProcessor.FEATURE,
                containerId, closeable, configMap);
    }

    private MilvusDevServiceCfg getConfiguration(MilvusBuildConfig cfg) {
        return new MilvusDevServiceCfg(cfg.devservices());
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static final class MilvusDevServiceCfg {

        private boolean devServicesEnabled;
        private OptionalInt fixedMilvusPort;
        private String milvusImageName;
        private String etcdImageName;
        private String minioImageName;
        private String serviceName;
        private boolean shared;

        public MilvusDevServiceCfg(MilvusBuildConfig.MilvusDevServicesBuildTimeConfig devservices) {
            this.devServicesEnabled = devservices.enabled();
            this.fixedMilvusPort = devservices.port();
            this.milvusImageName = devservices.milvusImageName();
            this.etcdImageName = devservices.etcdImageName();
            this.minioImageName = devservices.minioImageName();
            this.serviceName = devservices.serviceName();
            this.shared = devservices.shared();
        }

        // TODO equals, hashcode
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static class ConfiguredMilvusContainer extends GenericContainer<ConfiguredMilvusContainer> {
        private final OptionalInt fixedExposedPort;
        private final boolean useSharedNetwork;

        private String hostName = null;

        public ConfiguredMilvusContainer(DockerImageName dockerImageName,
                OptionalInt fixedExposedPort,
                String serviceName,
                boolean useSharedNetwork) {
            super(dockerImageName);
            this.fixedExposedPort = fixedExposedPort;
            this.useSharedNetwork = useSharedNetwork;

            if (serviceName != null) {
                withLabel(DEV_SERVICE_LABEL, serviceName);
            }
        }

        @Override
        protected void configure() {
            super.configure();

            if (useSharedNetwork) {
                hostName = ConfigureUtil.configureSharedNetwork(this, "milvus");
                return;
            }

            if (fixedExposedPort.isPresent()) {
                addFixedExposedPort(fixedExposedPort.getAsInt(), MILVUS_PORT);
            } else {
                addExposedPort(MILVUS_PORT);
            }
        }

        public int getPort() {
            if (useSharedNetwork) {
                return MILVUS_PORT;
            }

            if (fixedExposedPort.isPresent()) {
                return fixedExposedPort.getAsInt();
            }
            return super.getFirstMappedPort();
        }

        @Override
        public String getHost() {
            return useSharedNetwork ? hostName : super.getHost();
        }
    }
}
