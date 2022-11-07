package org.testcontainers.containers;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * This container wraps Confluent Kafka and Zookeeper (optionally)
 *
 */
public class KafkaContainer extends GenericContainer<KafkaContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("confluentinc/cp-kafka");

    private static final String DEFAULT_TAG = "5.4.3";

    public static final int KAFKA_PORT = 9093;

    public static final int ZOOKEEPER_PORT = 2181;

    private static final String DEFAULT_INTERNAL_TOPIC_RF = "1";

    private static final String STARTER_SCRIPT = "/testcontainers_start.sh";

    protected String externalZookeeperConnect = null;

    protected boolean kraftEnabled = false;

    /**
     * @deprecated use {@link KafkaContainer(DockerImageName)} instead
     */
    @Deprecated
    public KafkaContainer() {
        this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
    }

    /**
     * @deprecated use {@link KafkaContainer(DockerImageName)} instead
     */
    @Deprecated
    public KafkaContainer(String confluentPlatformVersion) {
        this(DEFAULT_IMAGE_NAME.withTag(confluentPlatformVersion));
    }

    public KafkaContainer(final DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);

        withExposedPorts(KAFKA_PORT);

        withCreateContainerCmdModifier(cmd -> {
            cmd.withEntrypoint("sh");
        });
        withCommand("-c", "while [ ! -f " + STARTER_SCRIPT + " ]; do sleep 0.1; done; " + STARTER_SCRIPT);
    }

    public KafkaContainer withEmbeddedZookeeper() {
        externalZookeeperConnect = null;
        return self();
    }

    public KafkaContainer withExternalZookeeper(String connectString) {
        externalZookeeperConnect = connectString;
        return self();
    }

    public KafkaContainer withKraft() {
        kraftEnabled = true;
        return self();
    }

    public String getBootstrapServers() {
        return String.format("PLAINTEXT://%s:%s", getHost(), getMappedPort(KAFKA_PORT));
    }

    @Override
    protected void configure() {
        if (kraftEnabled) {
            waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1));
        } else {
            waitingFor(Wait.forLogMessage(".*\\[KafkaServer id=\\d+\\] started.*", 1));
        }
        // Use two listeners with different names, it will force Kafka to communicate with itself via internal
        // listener when KAFKA_INTER_BROKER_LISTENER_NAME is set, otherwise Kafka will try to use the advertised listener
        withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:" + KAFKA_PORT + ",BROKER://0.0.0.0:9092");
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT");
        withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER");

        withEnv("KAFKA_BROKER_ID", "1");
        withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", DEFAULT_INTERNAL_TOPIC_RF);
        withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", DEFAULT_INTERNAL_TOPIC_RF);
        withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", DEFAULT_INTERNAL_TOPIC_RF);
        withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", DEFAULT_INTERNAL_TOPIC_RF);
        withEnv("KAFKA_LOG_FLUSH_INTERVAL_MESSAGES", Long.MAX_VALUE + "");
        withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");

        if (kraftEnabled) {
            withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                String.format("%s,CONTROLLER:PLAINTEXT",
                    getEnvMap().get("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP")
                )
            );
            withEnv("KAFKA_LISTENERS",
                String.format("%s,CONTROLLER://0.0.0.0:9094",
                    getEnvMap().get("KAFKA_LISTENERS")
                )
            );

            withEnv("KAFKA_NODE_ID", "1");
            withEnv("KAFKA_PROCESS_ROLES", "broker,controller");
            withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS",
                String.format("1@%s:9094", getNetwork() != null ? getNetworkAliases().get(0) : "localhost")
            );
            withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
        } else if (externalZookeeperConnect != null) {
            withEnv("KAFKA_ZOOKEEPER_CONNECT", externalZookeeperConnect);
        } else {
            addExposedPort(ZOOKEEPER_PORT);
            withEnv("KAFKA_ZOOKEEPER_CONNECT", "localhost:" + ZOOKEEPER_PORT);
        }
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        super.containerIsStarting(containerInfo);

        String command = "#!/bin/bash\n";
        // exporting KAFKA_ADVERTISED_LISTENERS with the container hostname
        command += String.format("export KAFKA_ADVERTISED_LISTENERS=%s,%s\n", getBootstrapServers(), brokerAdvertisedListener(containerInfo));
        logger().info(String.format(">KAFKA_ADVERTISED_LISTENERS=%s,%s\n", getBootstrapServers(), brokerAdvertisedListener(containerInfo)));
        if (kraftEnabled) {
            command += "sed -i '/KAFKA_ZOOKEEPER_CONNECT/d' /etc/confluent/docker/configure\n";
            command += "echo 'kafka-storage format --ignore-formatted -t \"$(kafka-storage random-uuid)\" -c /etc/kafka/kafka.properties' >> /etc/confluent/docker/configure\n";
        } else {
            command += "echo 'clientPort=" + ZOOKEEPER_PORT + "' > zookeeper.properties\n";
            command += "echo 'dataDir=/var/lib/zookeeper/data' >> zookeeper.properties\n";
            command += "echo 'dataLogDir=/var/lib/zookeeper/log' >> zookeeper.properties\n";
            command += "zookeeper-server-start zookeeper.properties &\n";
        }

        getEnv().forEach(it -> logger().info(">" + it));

        // Optimization: skip the checks
        command += "echo '' > /etc/confluent/docker/ensure \n";
        // Run the original command
        command += "/etc/confluent/docker/run \n";
        copyFileToContainer(Transferable.of(command, 0777), STARTER_SCRIPT);
    }

    protected String brokerAdvertisedListener(InspectContainerResponse containerInfo) {
        return String.format("BROKER://%s:%s", containerInfo.getConfig().getHostName(), "9092");
    }
}
