package org.icij.datashare.com.bus.amqp;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AmpInterlocutor has the responsibility for creating connections and publish channels.
 * <p>
 * It keeps tracks of the publish channels and closes them when close() is called.
 * </p>
 * Consumer channels are kept inside AbstractConsumer and closed by the
 */
public class AmqpInterlocutor {
    private static final Logger logger = LoggerFactory.getLogger(AmqpInterlocutor.class);
    private static AmqpInterlocutor instance;
    final Configuration configuration;
    private final ConnectionFactory connectionFactory;
    private final Connection connection;
    private final AtomicInteger nbQueue = new AtomicInteger(0);
    private final ConcurrentHashMap<AmqpQueue, AmqpChannel> publishChannels = new ConcurrentHashMap<>();

    public static AmqpInterlocutor getInstance() throws IOException {
        if (instance == null)
            synchronized(logger) { instance = new AmqpInterlocutor(new Configuration(new PropertiesProvider().getProperties())); }
        return instance;
    }

    static AmqpInterlocutor initWith(Configuration configuration) throws IOException {
        if (instance == null) {
            synchronized (logger) {
                instance = new AmqpInterlocutor(configuration);
            }
        }
        return instance;
    }

    private AmqpInterlocutor(Configuration configuration) throws IOException {
        this.configuration = configuration;
        this.connectionFactory = createConnectionFactory(configuration);
        this.connection = createConnection();
    }

    Connection createConnection() throws IOException {
        try {
            logger.info("Trying to connect AMQP on " + configuration.host + ":" + configuration.port + "...");
            Connection connection = connectionFactory.newConnection();
            logger.info("...connection to AMQP created");
            return connection;
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void close() throws IOException, TimeoutException {
        closeChannelsAndConnection();
    }

    public void publish(AmqpQueue queue, Event event) throws IOException {
        getChannel(queue).publish(event);
    }

    AmqpChannel getChannel(AmqpQueue queue) {
        if (queue == null) {
            throw new UnknownChannelException(queue);
        }
        AmqpChannel channel = publishChannels.get(queue);
        if (channel == null) {
            throw new UnknownChannelException(queue);
        }
        return channel;
    }

    public synchronized AmqpInterlocutor createAmqpChannelForPublish(AmqpQueue queue) throws IOException {
        AmqpChannel channel = new AmqpChannel(connection.createChannel(), queue);
        channel.initForPublish();
        publishChannels.put(queue, channel);
        logger.info("publish channel " + channel + " has been created for exchange {}", queue.exchange);
        return this;
    }

    public synchronized AmqpChannel createAmqpChannelForConsume(AmqpQueue queue) throws IOException {
        AmqpChannel channel = new AmqpChannel(connection.createChannel(), queue, nbQueue.incrementAndGet());
        channel.initForConsume(configuration.deadletter, configuration.nbMaxMessages);
        logger.info("consume channel " + channel + " has been created for queue {}", channel.queueName());
        return channel;
    }

    private ConnectionFactory createConnectionFactory(Configuration configuration) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUsername(configuration.user);
        factory.setPassword(configuration.password);
        factory.setVirtualHost("/");
        factory.setHost(configuration.host);
        factory.setPort(configuration.port);
        factory.setRequestedHeartbeat(60);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(configuration.connectionRecoveryDelay);
        return factory;
    }

    void closeChannelsAndConnection() throws IOException, TimeoutException {
        for (AmqpChannel channel : publishChannels.values()) {
            channel.close();
        }
        if (connection.isOpen()) {
            connection.close();
            logger.info("closing connection to {}:{}", configuration.host, configuration.port);
        }
    }

    private static class UnknownChannelException extends RuntimeException {
        public UnknownChannelException(AmqpQueue queue) {
            super("Unknown channel for queue " + queue);
        }
    }
}
