package org.cometd.bayeux.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.BayeuxException;
import org.cometd.bayeux.Extension;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.transport.Transport;
import org.cometd.bayeux.client.transport.TransportException;
import org.cometd.bayeux.client.transport.TransportListener;
import org.cometd.bayeux.client.transport.TransportRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardClientBayeux implements ClientBayeux
{
    private static final String BAYEUX_VERSION = "1.0";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final MetaChannelRegistry metaChannels = new MetaChannelRegistry();
    private final ChannelRegistry channels = new ChannelRegistry();
    private final TransportRegistry transports = new TransportRegistry();
    private final TransportListener transportListener = new Listener();
    private final List<Extension> extensions = new CopyOnWriteArrayList<Extension>();
    private final AtomicInteger messageIds = new AtomicInteger();
    private final ScheduledExecutorService scheduler;
    private volatile State state = State.DISCONNECTED;
    private volatile Transport transport;
    private volatile String clientId;
    private volatile Map<String, Object> advice;
    private volatile ScheduledFuture<?> scheduled; // TODO cancel this when appropriate (e.g. on disconnect)

    public StandardClientBayeux(Transport... transports)
    {
        this(Executors.newSingleThreadScheduledExecutor(), transports);
    }

    public StandardClientBayeux(ScheduledExecutorService scheduler, Transport... transports)
    {
        this.scheduler = scheduler;
        for (Transport transport : transports)
            this.transports.add(transport);
    }

    public MetaChannel getMetaChannel(MetaChannelType type)
    {
        return getMutableMetaChannel(type);
    }

    protected MetaChannel.Mutable getMutableMetaChannel(MetaChannelType type)
    {
        return metaChannels.from(type);
    }

    public void handshake()
    {
        if (!isDisconnected())
            throw new IllegalStateException();

        asyncHandshake();
    }

    private void asyncHandshake()
    {
        String[] transports = this.transports.findTransportTypes(BAYEUX_VERSION);
        Transport newTransport = negotiateTransport(transports);
        transport = lifecycleTransport(transport, newTransport);
        logger.debug("Handshaking with transport {}", transport);

        MetaMessage.Mutable request = transport.newMetaMessage(null);
        request.setMetaChannel(getMetaChannel(MetaChannelType.HANDSHAKE));
        request.put(Message.VERSION_FIELD, BAYEUX_VERSION);
        request.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, transports);

        updateState(State.HANDSHAKING);
        send(request);
    }

    private void updateState(State newState)
    {
        logger.debug("State change: {} -> {}", state, newState);
        this.state = newState;
    }

    private Transport lifecycleTransport(Transport oldTransport, Transport newTransport)
    {
        if (oldTransport != null)
        {
            oldTransport.removeListener(transportListener);
            oldTransport.destroy();
        }
        newTransport.addListener(transportListener);
        newTransport.init();
        return newTransport;
    }

    public void addExtension(Extension extension)
    {
        extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        extensions.remove(extension);
    }

    public Channel getChannel(String channelName)
    {
        return channels.from(channelName, true);
    }

    public void batch(Runnable batch)
    {
        // TODO
    }

    public void disconnect()
    {
        if (isDisconnected())
            throw new IllegalStateException();

        MetaMessage.Mutable metaMessage = transport.newMetaMessage(null);
        metaMessage.setMetaChannel(getMetaChannel(MetaChannelType.DISCONNECT));

        updateState(State.DISCONNECTING);
        send(metaMessage);
    }

    public String getClientId()
    {
        return clientId;
    }

    private Transport negotiateTransport(String[] requestedTransports)
    {
        Transport transport = transports.negotiate(BAYEUX_VERSION, requestedTransports);
        if (transport == null)
            throw new TransportException("Could not negotiate transport: requested " +
                    Arrays.toString(requestedTransports) +
                    ", available " +
                    Arrays.toString(transports.findTransportTypes(BAYEUX_VERSION)));
        return transport;
    }

    protected void send(MetaMessage.Mutable... metaMessages)
    {
        MetaMessage.Mutable[] processed = applyOutgoingExtensions(metaMessages);
        if (processed.length > 0)
            transport.send(processed);
    }

    private MetaMessage.Mutable[] applyOutgoingExtensions(MetaMessage.Mutable... metaMessages)
    {
        List<MetaMessage.Mutable> result = new ArrayList<MetaMessage.Mutable>();
        for (MetaMessage.Mutable metaMessage : metaMessages)
        {
            boolean processed = false;
            for (Extension extension : extensions)
            {
                processed = true;
                try
                {
                    MetaMessage.Mutable processedMetaMessage = extension.metaOutgoing(metaMessage);
                    if (processedMetaMessage != null)
                        result.add(processedMetaMessage);
                    else
                        logger.debug("Extension {} signalled to skip metaMessage {}", extension, metaMessage);
                }
                catch (Exception x)
                {
                    logger.debug("Exception while invoking extension " + extension, x);
                    result.add(metaMessage);
                }
            }
            if (!processed)
                result.add(metaMessage);
        }
        return result.toArray(new MetaMessage.Mutable[result.size()]);
    }

    protected void receive(MetaMessage.Mutable... metaMessages)
    {
        MetaMessage.Mutable[] processed = applyIncomingExtensions(metaMessages);

        for (MetaMessage metaMessage : processed)
        {
            advice = metaMessage.getAdvice();

            MetaChannel metaChannel = metaMessage.getMetaChannel();
            if (metaChannel == null)
                // TODO: call a listener method ? Discard the message ?
                throw new BayeuxException();

            switch (metaChannel.getType())
            {
                case HANDSHAKE:
                {
                    if (state != State.HANDSHAKING)
                        // TODO: call a listener method ? Discard the message ?
                        throw new BayeuxException();

                    if (metaMessage.isSuccessful())
                        processHandshake(metaMessage);
                    else
                        processUnsuccessful(metaMessage);

                    break;
                }
                case CONNECT:
                {
                    if (state != State.CONNECTED && state != State.DISCONNECTING)
                        // TODO: call a listener method ? Discard the message ?
                        throw new BayeuxException();

                    if (metaMessage.isSuccessful())
                        processConnect(metaMessage);
                    else
                        processUnsuccessful(metaMessage);

                    break;
                }
                case DISCONNECT:
                {
                    if (state != State.DISCONNECTING)
                        // TODO: call a listener method ? Discard the message ?
                        throw new BayeuxException();

                    if (metaMessage.isSuccessful())
                        processDisconnect(metaMessage);
                    else
                        processUnsuccessful(metaMessage);

                    break;
                }
                default:
                {
                    throw new BayeuxException();
                }
            }
        }
    }

    private MetaMessage.Mutable[] applyIncomingExtensions(MetaMessage.Mutable... metaMessages)
    {
        List<MetaMessage.Mutable> result = new ArrayList<MetaMessage.Mutable>();
        for (MetaMessage.Mutable metaMessage : metaMessages)
        {
            boolean processed = false;
            for (Extension extension : extensions)
            {
                processed = true;
                try
                {
                    MetaMessage.Mutable processedMetaMessage = extension.metaIncoming(metaMessage);
                    if (processedMetaMessage != null)
                        result.add(processedMetaMessage);
                    else
                        logger.debug("Extension {} signalled to skip metaMessage {}", extension, metaMessage);
                }
                catch (Exception x)
                {
                    logger.debug("Exception while invoking extension " + extension, x);
                    result.add(metaMessage);
                }
            }
            if (!processed)
                result.add(metaMessage);
        }
        return result.toArray(new MetaMessage.Mutable[result.size()]);
    }

    protected void processHandshake(MetaMessage handshake)
    {
        // Renegotiate transport
        Transport newTransport = transports.negotiate(BAYEUX_VERSION, (String[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD));
        if (newTransport == null)
        {
            // TODO: notify and stop
            throw new BayeuxException();
        }
        else if (newTransport != transport)
        {
            transport = lifecycleTransport(transport, newTransport);
        }

        updateState(State.CONNECTED);
        clientId = handshake.getClientId();

        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.HANDSHAKE), handshake);

        // TODO: internal batch ?

        followAdvice();
    }

    protected void processConnect(MetaMessage metaMessage)
    {
        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.CONNECT), metaMessage);
        followAdvice();
    }

    protected void processDisconnect(MetaMessage metaMessage)
    {
        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.DISCONNECT), metaMessage);
    }

    protected void processUnsuccessful(MetaMessage metaMessage)
    {
        // TODO
    }

    private void followAdvice()
    {
        if (advice != null)
        {
            String action = (String)advice.get(Message.RECONNECT_FIELD);
            if (Message.RECONNECT_RETRY_VALUE.equals(action))
            {
                // Must connect, follow timings in the advice
                Number intervalNumber = (Number)advice.get(Message.INTERVAL_FIELD);
                if (intervalNumber != null)
                {
                    long interval = intervalNumber.longValue();
                    if (interval < 0L)
                        interval = 0L;
                    scheduled = scheduler.schedule(new Runnable()
                    {
                        public void run()
                        {
                            asyncConnect();
                        }
                    }, interval, TimeUnit.MILLISECONDS);
                }
            }
            else if (Message.RECONNECT_HANDSHAKE_VALUE.equals(action))
            {
                // TODO:
                throw new BayeuxException();
            }
            else if (Message.RECONNECT_NONE_VALUE.equals(action))
            {
                // Do nothing
                // TODO: sure there is nothing more to do ?
            }
            else
            {
                logger.info("Reconnect action {} not supported in advice {}", action, advice);
            }
        }
    }

    private void asyncConnect()
    {
        MetaMessage.Mutable request = transport.newMetaMessage(null);
        request.setMetaChannel(getMetaChannel(MetaChannelType.CONNECT));
        request.put(Message.CONNECTION_TYPE_FIELD, transport.getType());
        request.setClientId(clientId);
        request.setId(newMessageId());
        send(request);
    }

    private String newMessageId()
    {
        return String.valueOf(messageIds.incrementAndGet());
    }

    private class Listener extends TransportListener.Adapter
    {
        @Override
        public void onMetaMessages(MetaMessage.Mutable... metaMessages)
        {
            receive(metaMessages);
        }
    }

    private boolean isDisconnected()
    {
        return state == State.DISCONNECTED;
    }

    private enum State
    {
        HANDSHAKING, CONNECTED, DISCONNECTING, DISCONNECTED
    }
}
