/*******************************************************************************
 * Copyright (c) 2015, 2016 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Stefan Jucker - DTLS implementation
 *    Julien Vermillard - Sierra Wireless
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add duplicate record detection
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 462463
 *    Kai Hudalla (Bosch Software Innovations GmbH) - re-factor configuration
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 464383
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add support for stale
 *                                                    session expiration (466554)
 *    Kai Hudalla (Bosch Software Innovations GmbH) - replace SessionStore with ConnectionStore
 *                                                    keeping all information about the connection
 *                                                    to a peer in a single place
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 472196
 *    Achim Kraus, Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 478538
 *    Kai Hudalla (Bosch Software Innovations GmbH) - derive max datagram size for outbound messages
 *                                                    from network MTU
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 483371
 *    Benjamin Cabe - fix typos in logger
 ******************************************************************************/
package org.eclipse.californium.scandium;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.channels.ClosedByInterruptException;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig.Builder;
import org.eclipse.californium.scandium.dtls.AlertMessage;
import org.eclipse.californium.scandium.dtls.AlertMessage.AlertDescription;
import org.eclipse.californium.scandium.dtls.AlertMessage.AlertLevel;
import org.eclipse.californium.scandium.dtls.ApplicationMessage;
import org.eclipse.californium.scandium.dtls.ClientHandshaker;
import org.eclipse.californium.scandium.dtls.ClientHello;
import org.eclipse.californium.scandium.dtls.CompressionMethod;
import org.eclipse.californium.scandium.dtls.Connection;
import org.eclipse.californium.scandium.dtls.ConnectionStore;
import org.eclipse.californium.scandium.dtls.ContentType;
import org.eclipse.californium.scandium.dtls.DTLSFlight;
import org.eclipse.californium.scandium.dtls.DTLSMessage;
import org.eclipse.californium.scandium.dtls.DTLSSession;
import org.eclipse.californium.scandium.dtls.DtlsHandshakeException;
import org.eclipse.californium.scandium.dtls.FragmentedHandshakeMessage;
import org.eclipse.californium.scandium.dtls.HandshakeException;
import org.eclipse.californium.scandium.dtls.HandshakeMessage;
import org.eclipse.californium.scandium.dtls.HandshakeType;
import org.eclipse.californium.scandium.dtls.Handshaker;
import org.eclipse.californium.scandium.dtls.HelloRequest;
import org.eclipse.californium.scandium.dtls.HelloVerifyRequest;
import org.eclipse.californium.scandium.dtls.InMemoryConnectionStore;
import org.eclipse.californium.scandium.dtls.MaxFragmentLengthExtension;
import org.eclipse.californium.scandium.dtls.ProtocolVersion;
import org.eclipse.californium.scandium.dtls.Record;
import org.eclipse.californium.scandium.dtls.ResumingClientHandshaker;
import org.eclipse.californium.scandium.dtls.ResumingServerHandshaker;
import org.eclipse.californium.scandium.dtls.ServerHandshaker;
import org.eclipse.californium.scandium.dtls.SessionAdapter;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite.KeyExchangeAlgorithm;
import org.eclipse.californium.scandium.util.ByteArrayUtils;


/**
 * A {@link Connector} using <em>Datagram TLS</em> (DTLS) as specified in
 * <a href="http://tools.ietf.org/html/rfc6347">RFC 6347</a> for securing data
 * exchanged between networked clients and a server application.
 */
public class DTLSConnector implements Connector {

	private static final Logger LOGGER = Logger.getLogger(DTLSConnector.class.getCanonicalName());
	private static final int MAX_PLAINTEXT_FRAGMENT_LENGTH = 16384; // max. DTLSPlaintext.length (2^14 bytes)
	private static final int MAX_CIPHERTEXT_EXPANSION =
			CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA256.getMaxCiphertextExpansion(); // CBC cipher has largest expansion
	private static final int MAX_DATAGRAM_BUFFER_SIZE = MAX_PLAINTEXT_FRAGMENT_LENGTH
			+ 12 // DTLS message headers
			+ 13 // DTLS record headers
			+ MAX_CIPHERTEXT_EXPANSION;

	private InetSocketAddress lastBindAddress;
	private int maximumTransmissionUnit = 1280; // min. IPv6 MTU
	private int inboundDatagramBufferSize = MAX_DATAGRAM_BUFFER_SIZE;

	// guard access to cookieMacKey
	private Object cookieMacKeyLock = new Object();
	// last time when the master key was generated
	private long lastGenerationDate = System.currentTimeMillis();
	private SecretKey cookieMacKey = new SecretKeySpec(randomBytes(), "MAC");

	/** all the configuration options for the DTLS connector */ 
	private final DtlsConnectorConfig config;

	private DatagramSocket socket;

	/** The timer daemon to schedule retransmissions. */
	private Timer timer;

	/** The thread that receives messages */
	private Worker receiver;

	/** The thread that sends messages */
	private Worker sender;

	private final ConnectionStore connectionStore;

	/** A queue for buffering outgoing messages */
	private final BlockingQueue<RawData> outboundMessages;

	/** Indicates whether the connector has started and not stopped yet */
	private boolean running;

	private RawDataChannel messageHandler;

	private ErrorHandler errorHandler;

	/**
	 * Creates a DTLS connector from a given configuration object
	 * using the standard in-memory <code>ConnectionStore</code>. 
	 * 
	 * @param configuration the configuration options
	 * @throws NullPointerException if the configuration is <code>null</code>
	 */
	public DTLSConnector(DtlsConnectorConfig configuration) {
		this(configuration, null);
	}

	/**
	 * Creates a DTLS connector from a given configuration object.
	 * 
	 * @param configuration the configuration options
	 * @param connectionStore the store to use for keeping track of connection information,
	 *       if <code>null</code> connection information is kept in-memory
	 * @throws NullPointerException if the configuration is <code>null</code>
	 */
	public DTLSConnector(DtlsConnectorConfig configuration, ConnectionStore connectionStore) {
		if (configuration == null) {
			throw new NullPointerException("Configuration must not be null");
		} else {
			this.config = configuration;
		}
		this.outboundMessages = new LinkedBlockingQueue<RawData>(config.getOutboundMessageBufferSize());
		if (connectionStore != null) {
			this.connectionStore = connectionStore;
		} else {
			this.connectionStore = new InMemoryConnectionStore();
		}
	}

	/**
	 * Creates a DTLS connector for PSK based authentication only.
	 * 
	 * @param address the IP address and port to bind to
	 * @deprecated Use {@link #DTLSConnector(DtlsConnectorConfig, ConnectionStore)} instead
	 */
	@Deprecated
	public DTLSConnector(InetSocketAddress address) {
		this(address, null);
	}

	/**
	 * Creates a DTLS connector that can also do certificate based authentication.
	 * 
	 * @param address the address to bind
	 * @param rootCertificates list of trusted root certificates, e.g. from well known
	 * Certificate Authorities or self-signed certificates.
	 * @deprecated Use {@link #DTLSConnector(DtlsConnectorConfig, ConnectionStore)} instead
	 */
	@Deprecated
	public DTLSConnector(InetSocketAddress address, Certificate[] rootCertificates) {
		this(address, rootCertificates, null, null);
	}

	/**
	 * Creates a DTLS connector that can also do certificate based authentication.
	 * 
	 * @param address the address to bind
	 * @param rootCertificates list of trusted root certificates, e.g. from well known
	 * Certificate Authorities or self-signed certificates (may be <code>null</code>)
	 * @param connectionStore the store to use for keeping track of connection information,
	 *       if <code>null</code> connection information is kept in-memory
	 * @param config the configuration options to use
	 * @deprecated Use {@link #DTLSConnector(DtlsConnectorConfig, ConnectionStore)} instead
	 */
	@Deprecated
	public DTLSConnector(InetSocketAddress address, Certificate[] rootCertificates,
			ConnectionStore connectionStore, DTLSConnectorConfig config) {
		Builder builder = new Builder(address);
		if (config != null) {
			builder.setMaxRetransmissions(config.getMaxRetransmit());
			builder.setRetransmissionTimeout(config.getRetransmissionTimeout());
			if (rootCertificates != null) {
				builder.setTrustStore(rootCertificates);
			}
			if (config.pskStore != null) {
				builder.setPskStore(config.pskStore);
			} else if (config.certChain != null) {
				builder.setIdentity(config.privateKey, config.certChain, config.sendRawKey);
			} else {
				builder.setIdentity(config.privateKey, config.publicKey);
			}
		}
		this.config = builder.build();
		this.outboundMessages = new LinkedBlockingQueue<RawData>(this.config.getOutboundMessageBufferSize());
		if (connectionStore != null) {
			this.connectionStore = connectionStore;
		} else {
			this.connectionStore = new InMemoryConnectionStore();
		}
	}

	/**
	 * Closes a connection with a given peer.
	 * 
	 * The connection is gracefully shut down, i.e. a final
	 * <em>CLOSE_NOTIFY</em> alert message is sent to the peer
	 * prior to removing all session state.
	 * 
	 * @param peerAddress the address of the peer to close the connection to
	 */
	public final void close(InetSocketAddress peerAddress) {
		Connection connection = connectionStore.get(peerAddress);
		if (connection != null && connection.getEstablishedSession() != null) {
			terminateConnection(
					connection,
					new AlertMessage(AlertLevel.WARNING, AlertDescription.CLOSE_NOTIFY, peerAddress),
					connection.getEstablishedSession());
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final synchronized void start() throws IOException {
		start(config.getAddress());
	}

	/**
	 * Re-starts the connector binding to the same IP address and port as
	 * on the previous start.
	 * 
	 * @throws IOException if the connector cannot be bound to the previous
	 *            IP address and port
	 */
	final synchronized void restart() throws IOException {
		if (lastBindAddress != null) {
			start(lastBindAddress);
		} else {
			throw new IllegalStateException("Connector has never been started before");
		}
	}

	private void start(InetSocketAddress bindAddress) throws IOException {
		if (running) {
			return;
		}
		timer = new Timer(true); // run as daemon
		socket = new DatagramSocket(null);
		// make it easier to stop/start a server consecutively without delays
		socket.setReuseAddress(true);
		socket.bind(bindAddress);
		NetworkInterface ni = NetworkInterface.getByInetAddress(bindAddress.getAddress());
		if (ni != null && ni.getMTU() > 0) {
			this.maximumTransmissionUnit = ni.getMTU();
		} else {
			LOGGER.config("Cannot determine MTU of network interface, using minimum MTU [1280] of IPv6 instead");
			this.maximumTransmissionUnit = 200;//1280;
		}

		if (config.getMaxFragmentLengthCode() != null) {
			MaxFragmentLengthExtension.Length lengthCode = MaxFragmentLengthExtension.Length.fromCode(
					config.getMaxFragmentLengthCode());
			// reduce inbound buffer size accordingly
			inboundDatagramBufferSize = lengthCode.length()
					+ MAX_CIPHERTEXT_EXPANSION
					+ 25; // 12 bytes DTLS message headers, 13 bytes DTLS record headers
		}

		lastBindAddress = new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
		running = true;

		sender = new Worker("DTLS-Sender-" + lastBindAddress) {
				@Override
				public void doWork() throws Exception {
					sendNextMessageOverNetwork();
				}
			};

		receiver = new Worker("DTLS-Receiver-" + lastBindAddress) {
				@Override
				public void doWork() throws Exception {
					receiveNextDatagramFromNetwork();
				}
			};

		receiver.start();
		sender.start();
		LOGGER.log(
				Level.INFO,
				"DTLS connector listening on [{0}] with MTU [{1}] using (inbound) datagram buffer size [{2} bytes]",
				new Object[]{lastBindAddress, maximumTransmissionUnit, inboundDatagramBufferSize});
	}

	/**
	 * Force connector to an abbreviated handshake. See <a href="https://tools.ietf.org/html/rfc5246#section-7.3">RFC 5246</a>.
	 * 
	 * The abbreviated handshake will be done next time data will be sent with {@link #send(RawData)}.
	 * @param peer the peer for which we will force to do an abbreviated handshake
	 */
	public final synchronized void forceResumeSessionFor(InetSocketAddress peer){
		Connection peerConnection = connectionStore.get(peer);
		if (peerConnection != null && peerConnection.getEstablishedSession() != null)
			peerConnection.setResumptionRequired(true);
	}

	/**
	 * Stops the sender and receiver threads and closes the socket
	 * used for sending and receiving datagrams.
	 */
	final synchronized void releaseSocket() {
		running = false;
		sender.interrupt();
		outboundMessages.clear();
		if (socket != null) {
			socket.close();
			socket = null;
		}
		maximumTransmissionUnit = 0;
	}

	@Override
	public final synchronized void stop() {
		if (!running) {
			return;
		}
		LOGGER.log(Level.INFO, "Stopping DTLS connector on [{0}]", lastBindAddress);
		timer.cancel();
		releaseSocket();
	}

	/**
	 * Destroys the connector.
	 * <p>
	 * This method invokes {@link #stop()} and clears the <code>ConnectionStore</code>
	 * used to manage connections to peers. Thus, contrary to the behavior specified
	 * for {@link Connector#destroy()}, this connector can be re-started using the
	 * {@link #start()} method but subsequent invocations of the {@link #send(RawData)}
	 * method will trigger the establishment of a new connection to the corresponding peer.
	 * </p>
	 */
	@Override
	public final synchronized void destroy() {
		stop();
		connectionStore.clear();
	}

	private void receiveNextDatagramFromNetwork() throws IOException {
		byte[] buffer = new byte[inboundDatagramBufferSize];
		DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
		synchronized (socket) {
			socket.receive(packet);
		}

		if (packet.getLength() == 0) {
			// nothing to do
			return;
		}
		InetSocketAddress peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());

		byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getLength());
		List<Record> records = Record.fromByteArray(data, peerAddress);
		LOGGER.log(Level.FINER, "Received {0} DTLS records using a {1} byte datagram buffer",
				new Object[]{records.size(), inboundDatagramBufferSize});

		for (Record record : records) {
			try {
				LOGGER.log(Level.FINEST, "Received DTLS record of type [{0}]", record.getType());

				switch(record.getType()) {
				case APPLICATION_DATA:
					processApplicationDataRecord(record);
					break;
				case ALERT:
					processAlertRecord(record);
					break;
				case CHANGE_CIPHER_SPEC:
					processChangeCipherSpecRecord(record);
					break;
				case HANDSHAKE:
					processHandshakeRecord(record);
					break;
				default:
					LOGGER.log(
						Level.FINE,
						"Discarding record of unsupported type [{0}] from peer [{1}]",
						new Object[]{record.getType(), record.getPeerAddress()});
				}
			} catch (RuntimeException e) {
				LOGGER.log(
					Level.INFO,
					String.format("Unexpected error occurred while processing record from peer [%s]", peerAddress),
					e);
				terminateConnection(peerAddress, e, AlertLevel.FATAL, AlertDescription.INTERNAL_ERROR);
				break;
			}
		}
	}

	/**
	 * Immediately terminates an ongoing handshake with a peer.
	 * 
	 * Terminating the handshake includes
	 * <ul>
	 * <li>canceling any pending retransmissions to the peer</li>
	 * <li>destroying any state for an ongoing handshake with the peer</li>
	 * </ul>
	 * 
	 * @param peerAddress the peer to terminate the handshake with
	 * @param cause the exception that is the cause for terminating the handshake
	 * @param description the reason to indicate in the message sent to the peer before terminating the handshake
	 */
	private void terminateOngoingHandshake(final InetSocketAddress peerAddress, final Throwable cause, final AlertDescription description) {

		Connection connection = connectionStore.get(peerAddress);
		if (connection != null && connection.hasOngoingHandshake()) {
			if (LOGGER.isLoggable(Level.FINEST)) {
				LOGGER.log(
					Level.FINEST,
					String.format("Aborting handshake with peer [%s]: ", peerAddress),
					cause);
			} else if (LOGGER.isLoggable(Level.INFO)) {
				LOGGER.log(
					Level.INFO,
					"Aborting handshake with peer [{0}]: {1}",
					new Object[]{peerAddress, cause.getMessage()});
			}
			DTLSSession session = connection.getOngoingHandshake().getSession();
			AlertMessage alert = new AlertMessage(AlertLevel.FATAL, description, peerAddress);
			if (!connection.hasEstablishedSession()) {
				terminateConnection(connection, alert, session);
			} else {
				// keep established session intact and only terminate ongoing handshake
				send(alert, session);
				connection.terminateOngoingHandshake();
			}
		}
	}

	private void terminateConnection(InetSocketAddress peerAddress) {
		if (peerAddress != null) {
			terminateConnection(connectionStore.get(peerAddress));
		}
	}

	private void terminateConnection(Connection connection) {
		if (connection != null) {
			connection.cancelPendingFlight();
			// clear session & (pending) handshaker
			connectionClosed(connection.getPeerAddress());
		}
	}

	private void terminateConnection(InetSocketAddress peerAddress, Throwable cause, AlertLevel level, AlertDescription description) {
		Connection connection = connectionStore.get(peerAddress);
		if (connection != null) {
			if (connection.hasEstablishedSession()) {
				terminateConnection(
						connection,
						new AlertMessage(level, description, peerAddress),
						connection.getEstablishedSession());
			} else if (connection.hasOngoingHandshake()) {
				terminateConnection(
						connection,
						new AlertMessage(level, description, peerAddress),
						connection.getOngoingHandshake().getSession());
			}
		}
	}

	/**
	 * Immediately terminates a connection with a peer.
	 * 
	 * Terminating the connection includes
	 * <ul>
	 * <li>canceling any pending retransmissions to the peer</li>
	 * <li>destroying any established session with the peer</li>
	 * <li>destroying any handshakers for the peer</li>
	 * <li>optionally sending a final ALERT to the peer (if a session exists with the peer)</li>
	 * </ul>
	 * 
	 * @param connection the connection to terminate
	 * @param alert the message to send to the peer before terminating the connection (may be <code>null</code>)
	 * @param session the parameters to encrypt the alert message with (may be <code>null</code> if alert is
	 *           <code>null</code>)
	 */
	private void terminateConnection(Connection connection, AlertMessage alert, DTLSSession session) {
		if (alert != null && session == null) {
			throw new IllegalArgumentException("Session must not be NULL if alert message is to be sent");
		}

		connection.cancelPendingFlight();

		if (alert == null) {
			LOGGER.log(Level.FINE, "Terminating connection with peer [{0}]", connection.getPeerAddress());
		} else {
			LOGGER.log(Level.FINE, "Terminating connection with peer [{0}], reason [{1}]",
					new Object[]{connection.getPeerAddress(), alert.getDescription()});
			send(alert, session);
		}
		// clear session & (pending) handshaker
		connectionClosed(connection.getPeerAddress());
	}

	private void processApplicationDataRecord(Record record) {

		Connection connection = connectionStore.get(record.getPeerAddress());
		if (connection != null && connection.hasEstablishedSession()) {
			DTLSSession session = connection.getEstablishedSession();
			synchronized (session) {
				// The DTLS 1.2 spec (section 4.1.2.6) advises to do replay detection
				// before MAC validation based on the record's sequence numbers
				// see http://tools.ietf.org/html/rfc6347#section-4.1.2.6
				if (session.isRecordProcessable(record.getEpoch(), record.getSequenceNumber())) {
					try {
						// APPLICATION_DATA can only be processed within the context of
						// an established, i.e. fully negotiated, session
						record.setSession(session);
						ApplicationMessage message = (ApplicationMessage) record.getFragment();
						// the fragment could be de-crypted
						// thus, the handshake seems to have been completed successfully
						connection.handshakeCompleted(record.getPeerAddress());
						session.markRecordAsRead(record.getEpoch(), record.getSequenceNumber());
						// finally, forward de-crypted message to application layer
						handleApplicationMessage(message, session.getPeerIdentity());
					} catch (HandshakeException | GeneralSecurityException e) {
						// this means that we could not parse or decrypt the message
						discardRecord(record, e);
					}
				} else {
					LOGGER.log(Level.FINER, "Discarding duplicate APPLICATION_DATA record received from peer [{0}]",
							record.getPeerAddress());
				}
			}
		} else {
			LOGGER.log(Level.FINER,
					"Discarding APPLICATION_DATA record received from peer [{0}] without an active session",
					new Object[]{record.getPeerAddress()});
		}
	}

	private void handleApplicationMessage(ApplicationMessage message, Principal peerIdentity) {
		if (messageHandler != null) {
			messageHandler.receiveData(new RawData(message.getData(), message.getPeer(), peerIdentity));
		}
	}

	/**
	 * Processes an <em>ALERT</em> message received from the peer.
	 * <p>
	 * Terminates the connection with the peer if either
	 * <ul>
	 * <li>the ALERT's level is FATAL or</li>
	 * <li>the ALERT is a <em>closure alert</em></li>
	 * </ul>
	 * 
	 * Also notifies a registered {@link #errorHandler} about the alert message.
	 * </p>
	 * @param record the record containing the ALERT message
	 * @see ErrorHandler
	 * @see #terminateConnection(Connection, AlertMessage, DTLSSession)
	 */
	private void processAlertRecord(Record record) {

		Connection connection = connectionStore.get(record.getPeerAddress());
		if (connection == null) {
			LOGGER.log(Level.FINER, "Discarding ALERT record from [{0}] received without existing connection", record.getPeerAddress());
		} else {
			processAlertRecord(record, connection);
		}
	}

	private void processAlertRecord(final Record record, final Connection connection) {

		if (connection.hasEstablishedSession() && connection.getEstablishedSession().getReadEpoch() == record.getEpoch()) {
				processAlertRecord(record, connection, connection.getEstablishedSession());
		} else if (connection.hasOngoingHandshake() && connection.getOngoingHandshake().getSession().getReadEpoch() == record.getEpoch()) {
				processAlertRecord(record, connection, connection.getOngoingHandshake().getSession());
		} else {
			LOGGER.log(
				Level.FINER,
				"Epoch of ALERT record [epoch=%d] from [%s] does not match expected epoch(s), discarding ...",
				new Object[]{record.getEpoch(), record.getPeerAddress()});
		}
	}

	private void processAlertRecord(final Record record, final Connection connection, final DTLSSession session) {
		record.setSession(session);
		try {
			AlertMessage alert = (AlertMessage) record.getFragment();
			LOGGER.log(
					Level.FINEST,
					"Processing {0} ALERT from [{1}]: {2}",
					new Object[]{alert.getLevel(), alert.getPeer(), alert.getDescription()});
			if (AlertDescription.CLOSE_NOTIFY.equals(alert.getDescription())) {
				// according to section 7.2.1 of the TLS 1.2 spec
				// (http://tools.ietf.org/html/rfc5246#section-7.2.1)
				// we need to respond with a CLOSE_NOTIFY alert and
				// then close and remove the connection immediately
				terminateConnection(
						connection,
						new AlertMessage(AlertLevel.WARNING, AlertDescription.CLOSE_NOTIFY, alert.getPeer()),
						session);
			} else if (AlertLevel.FATAL.equals(alert.getLevel())) {
				// according to section 7.2 of the TLS 1.2 spec
				// (http://tools.ietf.org/html/rfc5246#section-7.2)
				// the connection needs to be terminated immediately
				terminateConnection(connection);
			} else {
				// non-fatal alerts do not require any special handling
			}
	
			if (errorHandler != null) {
				errorHandler.onError(alert.getPeer(), alert.getLevel(), alert.getDescription());
			}
		} catch (HandshakeException | GeneralSecurityException e) {
			discardRecord(record, e);
		}
	}

	private void processChangeCipherSpecRecord(Record record) {
		Connection connection = connectionStore.get(record.getPeerAddress());
		if (connection != null && connection.hasOngoingHandshake()) {
			// processing a CCS message does not result in any additional flight to be sent
			try {
				connection.getOngoingHandshake().processMessage(record);
			} catch (HandshakeException e) {
				handleExceptionDuringHandshake(e, e.getAlert().getLevel(), e.getAlert().getDescription(), record);
			}
		} else {
			// change cipher spec can only be processed within the
			// context of an existing handshake -> ignore record
			LOGGER.log(Level.FINE, "Received CHANGE_CIPHER_SPEC record from peer [{0}] with no handshake going on", record.getPeerAddress());
		}
	}

	private void processHandshakeRecord(final Record record) {

		LOGGER.log(Level.FINE, "Received {0} record from peer [{1}]",
				new Object[]{record.getType(), record.getPeerAddress()});
		final Connection con = connectionStore.get(record.getPeerAddress());
		try {
			if (con == null) {
				processHandshakeRecordWithoutConnection(record);
			} else {
				processHandshakeRecordWithConnection(record, con);
			}
		} catch (HandshakeException e) {
			handleExceptionDuringHandshake(e, e.getAlert().getLevel(), e.getAlert().getDescription(), record);
		}
	}

	/**
	 * 
	 * @param record
	 * @throws HandshakeException if the handshake record cannot be parsed or processed successfully
	 */
	private void processHandshakeRecordWithoutConnection(final Record record) throws HandshakeException {
		if (record.getEpoch() > 0) {
			LOGGER.log(
				Level.FINE,
				"Discarding unexpected handshake message [epoch={0}] received from peer [{1}] without existing connection",
				new Object[]{record.getEpoch(), record.getPeerAddress()});
		} else {
			try {
				HandshakeMessage handshakeMessage = (HandshakeMessage) record.getFragment();
				if (handshakeMessage.getFragmentLength() < handshakeMessage.getMessageLength()) {
					HandshakeMessage message = handleFragmentation((FragmentedHandshakeMessage)handshakeMessage, record.getPeerAddress());
					if (null != message && HandshakeType.CLIENT_HELLO.equals(message.getMessageType())) {
						record.setFragment(message);
						processClientHello((ClientHello) message, record);
					}
				}
				
				// in epoch 0 no crypto params have been established yet, thus we can simply call getFragment()
				//HandshakeMessage handshakeMessage = (HandshakeMessage) record.getFragment();
				// if we do not have a connection yet we ignore everything but a CLIENT_HELLO
				else if (HandshakeType.CLIENT_HELLO.equals(handshakeMessage.getMessageType())) {
					processClientHello((ClientHello) handshakeMessage, record);
				} else {
					LOGGER.log(
							Level.FINE,
							"Discarding unexpected {0} message from peer [{1}]",
							new Object[]{handshakeMessage.getMessageType(), handshakeMessage.getPeer()});
				}
			} catch (GeneralSecurityException e) {
				discardRecord(record, e);
			}
		}
	}
	// 新增变量
	/** Store the fragmented messages until we are able to reassemble the handshake message. */
	protected Map<Integer, SortedSet<FragmentedHandshakeMessage>> fragmentedMessages = new HashMap<Integer, SortedSet<FragmentedHandshakeMessage>>();
	// 新增函数
	private final HandshakeMessage handleFragmentation(FragmentedHandshakeMessage fragment, InetSocketAddress sockAddr) throws HandshakeException {

		LOGGER.log(Level.FINER, "Processing {0} message fragment ...", fragment.getMessageType());
		HandshakeMessage reassembledMessage = null;
		int messageSeq = fragment.getMessageSeq();
		SortedSet<FragmentedHandshakeMessage> existingFragments = fragmentedMessages.get(messageSeq);
		if (existingFragments == null) {
			existingFragments = new TreeSet<FragmentedHandshakeMessage>(new Comparator<FragmentedHandshakeMessage>() {

				// @Override
				public int compare(FragmentedHandshakeMessage o1, FragmentedHandshakeMessage o2) {
					if (o1.getFragmentOffset() == o2.getFragmentOffset()) {
						return 0;
					} else if (o1.getFragmentOffset() < o2.getFragmentOffset()) {
						return -1;
					} else {
						return 1;
					}
				}
			});
			fragmentedMessages.put(messageSeq, existingFragments);
		}
		// store fragment together with other fragments of same message_seq
		existingFragments.add(fragment);
		
		reassembledMessage = reassembleFragments(messageSeq, existingFragments,
				fragment.getMessageLength(), fragment.getMessageType(), sockAddr);
		if (reassembledMessage != null) {
			LOGGER.log(Level.FINER, "Successfully re-assembled {0} message", reassembledMessage.getMessageType());
			fragmentedMessages.remove(messageSeq);
		}
		
		return reassembledMessage;
	}
	// 新增函数
	private final HandshakeMessage reassembleFragments(
			int messageSeq,
			SortedSet<FragmentedHandshakeMessage> fragments,
			int totalLength,
			HandshakeType type,
			InetSocketAddress sockAddr) throws HandshakeException {

		HandshakeMessage message = null;

		byte[] reassembly = new byte[] {};
		int offset = 0;
		for (FragmentedHandshakeMessage fragmentedHandshakeMessage : fragments) {
			
			int fragmentOffset = fragmentedHandshakeMessage.getFragmentOffset();
			int fragmentLength = fragmentedHandshakeMessage.getFragmentLength();
			
			if (fragmentOffset == offset) { // eliminate duplicates
				// case: no overlap
				reassembly = ByteArrayUtils.concatenate(reassembly, fragmentedHandshakeMessage.fragmentToByteArray());
				offset = reassembly.length;
			} else if (fragmentOffset < offset && (fragmentOffset + fragmentLength) > offset) {
				// case: overlap fragment
				
				// determine the offset where the fragment adds new information for the reassembly
				int newOffset = offset - fragmentOffset;
				int newLength = fragmentLength - newOffset;
				byte[] newBytes = new byte[newLength];
				// take only the new bytes and add them
				System.arraycopy(fragmentedHandshakeMessage.fragmentToByteArray(), newOffset, newBytes, 0, newLength);	
				reassembly = ByteArrayUtils.concatenate(reassembly, newBytes);
				
				offset = reassembly.length;
			}
		}
		
		if (reassembly.length == totalLength) {
			// the reassembled fragment has the expected length
			FragmentedHandshakeMessage wholeMessage =
					new FragmentedHandshakeMessage(type, totalLength, messageSeq, 0, reassembly, sockAddr);
			reassembly = wholeMessage.toByteArray();
			
			KeyExchangeAlgorithm keyExchangeAlgorithm = KeyExchangeAlgorithm.NULL;
			boolean receiveRawPublicKey = false;
			message = HandshakeMessage.fromByteArray(reassembly, keyExchangeAlgorithm, receiveRawPublicKey, sockAddr);
		}
		
		return message;
	}

	/**
	 * 
	 * @param record
	 * @param connection
	 * @throws HandshakeException if the handshake message cannot be processed
	 */
	private void processHandshakeRecordWithConnection(final Record record, final Connection connection) throws HandshakeException {
		if (connection.hasOngoingHandshake() && connection.getOngoingHandshake().getSession().getReadEpoch() == record.getEpoch()) {
			// evaluate message in context of ongoing handshake
			record.setSession(connection.getOngoingHandshake().getSession());
		} else if (connection.hasEstablishedSession() && connection.getEstablishedSession().getReadEpoch() == record.getEpoch()) {
			// client wants to re-negotiate established connection's crypto params
			// evaluate message in context of established session
			record.setSession(connection.getEstablishedSession());
		} else if (connection.hasEstablishedSession() && record.getEpoch() == 0) {
			// client has lost track of existing connection and wants to negotiate a new connection
			// in epoch 0 no crypto params have been established yet, thus we do not need to set a session
		} else {
			LOGGER.log(
				Level.FINE,
				"Discarding HANDSHAKE message [epoch={0}] from peer [{1}] which does not match expected epoch(s)",
				new Object[]{record.getEpoch(), record.getPeerAddress()});
			return;
		}

		try {
			HandshakeMessage handshakeMessage = (HandshakeMessage) record.getFragment();
			processDecryptedHandshakeMessage(handshakeMessage, record, connection);
		} catch (GeneralSecurityException e) {
			discardRecord(record, e);
		}
	}

	private void processDecryptedHandshakeMessage(final HandshakeMessage handshakeMessage, final Record record,
			final Connection connection) throws HandshakeException {
		switch (handshakeMessage.getMessageType()) {
		case CLIENT_HELLO:
			processClientHello((ClientHello) handshakeMessage, record, connection);
			break;
		case HELLO_REQUEST:
			processHelloRequest((HelloRequest) handshakeMessage, connection);
			break;
		default:
			processOngoingHandshakeMessage(handshakeMessage, record, connection);
		}
	}

	/**
	 * 
	 * @param message
	 * @param record
	 * @param connection
	 * @throws HandshakeException if the handshake message cannot be processed
	 */
	private void processOngoingHandshakeMessage(final HandshakeMessage message, final Record record, final Connection connection) throws HandshakeException {
		if (connection.hasOngoingHandshake()) {
			DTLSFlight flight = connection.getOngoingHandshake().processMessage(record);
			sendHandshakeFlight(flight, connection);
		} else {
			LOGGER.log(
				Level.FINE,
				"Discarding {0} message received from peer [{1}] with no handshake going on",
				new Object[]{message.getMessageType(), message.getPeer()});
		}
	}

	/**
	 * 
	 * @param helloRequest
	 * @param connection
	 * @throws HandshakeException if the message to initiate the handshake with the peer cannot be created
	 */
	private void processHelloRequest(final HelloRequest helloRequest, final Connection connection) throws HandshakeException {
		if (connection.hasOngoingHandshake()) {
			// TLS 1.2, Section 7.4 advises to ignore HELLO_REQUEST messages arriving while
			// in an ongoing handshake (http://tools.ietf.org/html/rfc5246#section-7.4)
			LOGGER.log(
					Level.FINE,
					"Ignoring {0} received from [{1}] while already in an ongoing handshake with peer",
					new Object[]{helloRequest.getMessageType(), helloRequest.getPeer()});
		} else {
			DTLSSession session = connection.getEstablishedSession();
			if (session == null) {
				session = new DTLSSession(helloRequest.getPeer(), true);
			}
			Handshaker handshaker = new ClientHandshaker(null, session, connection, config, maximumTransmissionUnit);
			sendHandshakeFlight(handshaker.getStartHandshakeMessage(), connection);
		}
	}

	/**
	 * 
	 * @param clientHello
	 * @param record
	 * @throws HandshakeException if the parameters provided in the client hello message cannot be used
	 *               to start a new handshake or resume an existing session
	 */
	private void processClientHello(final ClientHello clientHello, final Record record) throws HandshakeException {
		if (LOGGER.isLoggable(Level.FINE)) {
			StringBuilder msg = new StringBuilder("Processing CLIENT_HELLO from peer [").append(record.getPeerAddress()).append("]");
			if (LOGGER.isLoggable(Level.FINEST)) {
				msg.append(":").append(System.lineSeparator()).append(record);
			}
			LOGGER.fine(msg.toString());
		}

		// before starting a new handshake or resuming an established session we need to make sure that the
		// peer is in possession of the IP address indicated in the client hello message
		if (isClientInControlOfSourceIpAddress(clientHello, record)) {
			if (clientHello.hasSessionId()) {
				// client wants to resume a cached session
				resumeExistingSession(clientHello, record);
			} else {
				// At this point the client has demonstrated reachability by completing a cookie exchange
				// so we start a new handshake (see section 4.2.8 of RFC 6347 (DTLS 1.2))
				startNewHandshake(clientHello, record);
			}
		}
	}

	/**
	 * 
	 * @param clientHello
	 * @param record
	 * @param connection
	 * @throws HandshakeException if the parameters provided in the client hello message cannot be used
	 *               to start a new handshake or resume an existing session
	 */
	private void processClientHello(final ClientHello clientHello, final Record record, final Connection connection) throws HandshakeException {
		if (LOGGER.isLoggable(Level.FINE)) {
			StringBuilder msg = new StringBuilder("Processing CLIENT_HELLO from peer [").append(record.getPeerAddress()).append("]");
			if (LOGGER.isLoggable(Level.FINEST)) {
				msg.append(":").append(System.lineSeparator()).append(record);
			}
			LOGGER.fine(msg.toString());
		}

		// before starting a new handshake or resuming an established session we need to make sure that the
		// peer is in possession of the IP address indicated in the client hello message
		if (isClientInControlOfSourceIpAddress(clientHello, record)) {
			if (isHandshakeAlreadyStartedForMessage(clientHello, connection)) {
				// client has sent this message before (maybe our response flight has been lost)
				// but we do not want to start over again, so let the existing handshaker handle
				// the duplicate
				processOngoingHandshakeMessage(clientHello, record, connection);
			} else if (clientHello.hasSessionId()) {
				// client wants to resume a cached session
				resumeExistingSession(clientHello, record);
			} else {
				// At this point the client has demonstrated reachability by completing a cookie exchange
				// so we terminate the previous connection and start a new handshake
				// (see section 4.2.8 of RFC 6347 (DTLS 1.2))
				terminateConnection(connection);
				startNewHandshake(clientHello, record);
			}
		}
	}

	private static boolean isHandshakeAlreadyStartedForMessage(final ClientHello clientHello, final Connection connection) {
		return connection != null && connection.hasOngoingHandshake() && 
			connection.getOngoingHandshake().hasBeenStartedByMessage(clientHello);
	}

	/**
	 * Checks whether the peer is able to receive data on the IP address indicated
	 * in its client hello message.
	 * <p>
	 * The check is done by means of comparing the cookie contained in the client hello
	 * message with the cookie computed for the request using the <code>generateCookie</code>
	 * method.
	 * </p>
	 * <p>This method sends a <em>HELLO_VERIFY_REQUEST</em> to the peer if the cookie contained
	 * in <code>clientHello</code> does not match the expected cookie.
	 * </p>
	 * 
	 * @param clientHello the peer's client hello method including the cookie to verify
	 * @param record the
	 * @return <code>true</code> if the client hello message contains a cookie and the cookie
	 *             is identical to the cookie expected from the peer address
	 */
	private boolean isClientInControlOfSourceIpAddress(ClientHello clientHello, Record record) {
		// verify client's ability to respond on given IP address
		// by exchanging a cookie as described in section 4.2.1 of the DTLS 1.2 spec
		// see http://tools.ietf.org/html/rfc6347#section-4.2.1
		byte[] expectedCookie = generateCookie(clientHello);
		if (Arrays.equals(expectedCookie, clientHello.getCookie())) {
			return true;
		} else {
			sendHelloVerify(clientHello, record, expectedCookie);
			return false;
		}
	}

	/**
	 * 
	 * @param clientHello
	 * @param record
	 * @throws HandshakeException if the parameters provided in the client hello message
	 *           cannot be used to start a handshake with the peer
	 */
	private void startNewHandshake(final ClientHello clientHello, final Record record) throws HandshakeException {
		Connection peerConnection = new Connection(record.getPeerAddress());
		connectionStore.put(peerConnection);

		// use the record sequence number from CLIENT_HELLO as initial sequence number
		// for records sent to the client (see section 4.2.1 of RFC 6347 (DTLS 1.2))
		DTLSSession newSession = new DTLSSession(record.getPeerAddress(), false, record.getSequenceNumber());
		// initialize handshaker based on CLIENT_HELLO (this accounts
		// for the case that multiple cookie exchanges have taken place)
		Handshaker handshaker = new ServerHandshaker(clientHello.getMessageSeq(),
				newSession, peerConnection, config, maximumTransmissionUnit);
		sendHandshakeFlight(handshaker.processMessage(record), peerConnection);
	}

	/**
	 * 
	 * @param clientHello
	 * @param record
	 * @throws HandshakeException if the session cannot be resumed based on the parameters
	 *             provided in the client hello message
	 */
	private void resumeExistingSession(final ClientHello clientHello, final Record record) throws HandshakeException {
		LOGGER.log(Level.FINER, "Client [{0}] wants to resume session with ID [{1}]",
				new Object[]{clientHello.getPeer(), clientHello.getSessionId()});
		final Connection previousConnection = connectionStore.find(clientHello.getSessionId());
		if (previousConnection != null && previousConnection.hasEstablishedSession()) {
			// session has been found in cache, resume it
			DTLSSession resumableSession = new DTLSSession(record.getPeerAddress(),
					previousConnection.getEstablishedSession(), record.getSequenceNumber());
			Connection peerConnection = new Connection(record.getPeerAddress());

			Handshaker handshaker = new ResumingServerHandshaker(clientHello.getMessageSeq(),
					resumableSession, peerConnection, config, maximumTransmissionUnit);

			if (!previousConnection.getPeerAddress().equals(peerConnection.getPeerAddress())) {
				// client has a new IP address, terminate previous connection once new session has been established
				handshaker.addSessionListener(new SessionAdapter() {
					@Override
					public void sessionEstablished(Handshaker handshaker, DTLSSession establishedSession)
							throws HandshakeException {
						LOGGER.log(Level.FINER,
								"Discarding existing connection to [{0}] after successful resumption of session [ID={1}] by peer [{2}]",
								new Object[]{
										previousConnection.getPeerAddress(),
										establishedSession.getSessionIdentifier(),
										establishedSession.getPeer()});
						terminateConnection(previousConnection);
					}
				});
			} else {
				// immediately remove previous connection
				terminateConnection(previousConnection);
			}
			// add the new one to the store
			connectionStore.put(peerConnection);

			// process message
			sendHandshakeFlight(handshaker.processMessage(record), peerConnection);
		} else {
			LOGGER.log(
				Level.FINER,
				"Client [{0}] tries to resume non-existing session [ID={1}], performing full handshake instead ...",
				new Object[]{clientHello.getPeer(), clientHello.getSessionId()});
			terminateConnection(clientHello.getPeer());
			startNewHandshake(clientHello, record);
		}
	}

	private void sendHelloVerify(ClientHello clientHello, Record record, byte[] expectedCookie) {
		// send CLIENT_HELLO_VERIFY with cookie in order to prevent
		// DOS attack as described in DTLS 1.2 spec
		LOGGER.log(Level.FINER, "Verifying client IP address [{0}] using HELLO_VERIFY_REQUEST", record.getPeerAddress());
		HelloVerifyRequest msg = new HelloVerifyRequest(new ProtocolVersion(), expectedCookie, record.getPeerAddress());
		// because we do not have a handshaker in place yet that
		// manages message_seq numbers, we need to set it explicitly
		// use message_seq from CLIENT_HELLO in order to allow for
		// multiple consecutive cookie exchanges with a client
		msg.setMessageSeq(clientHello.getMessageSeq());
		// use epoch 0 and sequence no from CLIENT_HELLO record as
		// mandated by section 4.2.1 of the DTLS 1.2 spec
		// see http://tools.ietf.org/html/rfc6347#section-4.2.1
		Record helloVerify = new Record(ContentType.HANDSHAKE, 0, record.getSequenceNumber(), msg, record.getPeerAddress());
		DTLSFlight nextFlight = new DTLSFlight(record.getPeerAddress());
		nextFlight.addMessage(helloVerify);
		sendFlight(nextFlight);
	}

	private SecretKey getMacKeyForCookies() {
		synchronized (cookieMacKeyLock) {
			// if the last generation was more than 5 minute ago, let's generate
			// a new key
			if (System.currentTimeMillis() - lastGenerationDate > TimeUnit.MINUTES.toMillis(5)) {
				cookieMacKey = new SecretKeySpec(randomBytes(), "MAC");
				lastGenerationDate = System.currentTimeMillis();
			}
			return cookieMacKey;
		}

	}

	/**
	 * Generates a cookie in such a way that they can be verified without
	 * retaining any per-client state on the server.
	 * 
	 * <pre>
	 * Cookie = HMAC(Secret, Client - IP, Client - Parameters)
	 * </pre>
	 * 
	 * as suggested <a
	 * href="http://tools.ietf.org/html/rfc6347#section-4.2.1">here</a>.
	 * 
	 * @return the cookie generated from the client's parameters
	 * @throws DtlsHandshakeException if the cookie cannot be computed
	 */
	private byte[] generateCookie(ClientHello clientHello) {

		try {
			// Cookie = HMAC(Secret, Client-IP, Client-Parameters)
			Mac hmac = Mac.getInstance("HmacSHA256");
			hmac.init(getMacKeyForCookies());
			// Client-IP
			hmac.update(clientHello.getPeer().toString().getBytes());

			// Client-Parameters
			hmac.update((byte) clientHello.getClientVersion().getMajor());
			hmac.update((byte) clientHello.getClientVersion().getMinor());
			hmac.update(clientHello.getRandom().getRandomBytes());
			hmac.update(clientHello.getSessionId().getId());
			hmac.update(CipherSuite.listToByteArray(clientHello.getCipherSuites()));
			hmac.update(CompressionMethod.listToByteArray(clientHello.getCompressionMethods()));
			return hmac.doFinal();
		} catch (GeneralSecurityException e) {
			throw new DtlsHandshakeException(
					"Cannot compute cookie for peer",
					AlertDescription.INTERNAL_ERROR,
					AlertLevel.FATAL,
					clientHello.getPeer(),
					e);
		}
	}

	void send(AlertMessage alert, DTLSSession session) {
		if (alert == null) {
			throw new IllegalArgumentException("Alert must not be NULL");
		} else if (session == null) {
			throw new IllegalArgumentException("Session must not be NULL");
		} else {
			try {
				DTLSFlight flight = new DTLSFlight(session);
				flight.setRetransmissionNeeded(false);
				flight.addMessage(new Record(ContentType.ALERT, session.getWriteEpoch(), session.getSequenceNumber(), alert, session));
				sendFlight(flight);
			} catch (GeneralSecurityException e) {
				LOGGER.log(
					Level.FINE,
					String.format("Cannot create ALERT message for peer [%s]", session.getPeer()),
					e);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void send(RawData msg) {
		if (msg == null) {
			LOGGER.finest("Ignoring NULL msg ...");
		} else if (msg.getBytes().length > MAX_PLAINTEXT_FRAGMENT_LENGTH) {
			throw new IllegalArgumentException("Message data must not exceed "
					+ MAX_PLAINTEXT_FRAGMENT_LENGTH + " bytes");
		} else {
			boolean queueFull = !outboundMessages.offer(msg);
			if (queueFull) {
				LOGGER.log(Level.WARNING, "Outbound message queue is full! Dropping outbound message to peer [{0}]",
						msg.getInetSocketAddress());
			}
		}
	}

	private void sendNextMessageOverNetwork() throws HandshakeException {

		RawData message;
		try {
			message = outboundMessages.take(); // Blocking
		} catch (InterruptedException e) {
			// this means that the worker thread for sending
			// outbound messages has been interrupted, most
			// probably because the connector is shutting down
			Thread.currentThread().interrupt();
			return;
		}
		
		InetSocketAddress peerAddress = message.getInetSocketAddress();
		LOGGER.log(Level.FINER, "Sending application layer message to peer [{0}]", peerAddress);
		Connection connection = connectionStore.get(peerAddress);
		
		/*
		 * When the DTLS layer receives a message from an upper layer, there is
		 * either already a DTLS session established with the peer or a new
		 * handshake must be initiated. If a session is available and active, the
		 * message will be encrypted and sent to the peer, otherwise a short
		 * handshake will be initiated.
		 */
		
		// TODO make sure that only ONE handshake is in progress with a peer
		// at all times
		
		Handshaker handshaker = null;
		DTLSFlight flight = null;

		try {
			if (connection == null) {
				connection = new Connection(peerAddress);
				connectionStore.put(connection);
			}
			
			DTLSSession session = connection.getEstablishedSession();
			if (session == null) {
				// no session with peer available, create new empty session &
				// start fresh handshake
				handshaker = new ClientHandshaker(message, new DTLSSession(peerAddress, true), connection, config, maximumTransmissionUnit);
			}
			// TODO what if there already is an ongoing handshake with the peer
			else if (connection.isResumptionRequired()){
				// create the session to resume from the previous one.
				DTLSSession resumableSession = new DTLSSession(peerAddress, session, 0);

				// terminate the previous connection and add the new one to the store
				Connection newConnection = new Connection(peerAddress);
				terminateConnection(connection, null, null);
				connectionStore.put(newConnection);
				handshaker = new ResumingClientHandshaker(message, resumableSession, newConnection, config, maximumTransmissionUnit);
			} else {
				// session to peer is active, send encrypted message
				flight = new DTLSFlight(session);
				flight.addMessage(new Record(
						ContentType.APPLICATION_DATA,
						session.getWriteEpoch(),
						session.getSequenceNumber(),
						new ApplicationMessage(message.getBytes(), peerAddress),
						session));
			}
			// start DTLS handshake protocol
			if (handshaker != null) {
				// get starting handshake message
				flight = handshaker.getStartHandshakeMessage();
				connection.setPendingFlight(flight);
				scheduleRetransmission(flight);
			}
			sendFlight(flight);
		} catch (GeneralSecurityException e) {
			LOGGER.log(Level.FINE, String.format("Cannot send record to peer [%s]", peerAddress), e);
		}
	}

	/**
	 * Returns the {@link DTLSSession} related to the given peer address.
	 * 
	 * @param address the peer address
	 * @return the {@link DTLSSession} or <code>null</code> if no session found.
	 */
	public final DTLSSession getSessionByAddress(InetSocketAddress address) {
		if (address == null) {
			return null;
		}
		Connection connection = connectionStore.get(address);
		if (connection != null) {
			return connection.getEstablishedSession();
		} else {
			return null;
		}
	}

	private void sendHandshakeFlight(DTLSFlight flight, Connection connection) {
		if (flight != null) {
			connection.cancelPendingFlight();
			if (flight.isRetransmissionNeeded()) {
				connection.setPendingFlight(flight);
				scheduleRetransmission(flight);
			}
			sendFlight(flight);
		}
	}

	private void sendFlight(DTLSFlight flight) {

		byte[] payload = new byte[] {};
		int maxDatagramSize = maximumTransmissionUnit;
		if (flight.getSession() != null) {
			// the max. fragment length reported by the session will be
			// slightly smaller than the (assumed) PMTU to the peer because it doesn't
			// account for payload expansion introduced by cipher and headers
			maxDatagramSize = flight.getSession().getMaxDatagramSize();
		}

		// put as many records into one datagram as allowed by the max. payload size
		List<DatagramPacket> datagrams = new ArrayList<DatagramPacket>();

		try {
			for (Record record : flight.getMessages()) {
				if (flight.getTries() > 0) {
					// adjust the record sequence number
					int epoch = record.getEpoch();
					record.setSequenceNumber(flight.getSession().getSequenceNumber(epoch));
				}

				byte[] recordBytes = record.toByteArray();
				if (recordBytes.length > maxDatagramSize) {
					LOGGER.log(
							Level.INFO,
							"{0} record of {1} bytes for peer [{2}] exceeds max. datagram size [{3}], discarding...",
							new Object[]{record.getType(), recordBytes.length, record.getPeerAddress(), maxDatagramSize});
					// TODO: inform application layer, e.g. using error handler
					continue;
				}
				LOGGER.log(
						Level.FINEST,
						"Sending record of {2} bytes to peer [{0}]:\n{1}",
						new Object[]{flight.getPeerAddress(), record, recordBytes.length});

				if (payload.length + recordBytes.length > maxDatagramSize) {
					// current record does not fit into datagram anymore
					// thus, send out current datagram and put record into new one
					DatagramPacket datagram = new DatagramPacket(payload, payload.length,
							flight.getPeerAddress().getAddress(), flight.getPeerAddress().getPort());
					datagrams.add(datagram);
					payload = new byte[] {};
				}

				payload = ByteArrayUtils.concatenate(payload, recordBytes);
			}

			DatagramPacket datagram = new DatagramPacket(payload, payload.length,
					flight.getPeerAddress().getAddress(), flight.getPeerAddress().getPort());
			datagrams.add(datagram);

			// send it over the UDP socket
			LOGGER.log(Level.FINER, "Sending flight of {0} message(s) to peer [{1}] using {2} datagram(s) of max. {3} bytes",
					new Object[]{flight.getMessages().size(), flight.getPeerAddress(), datagrams.size(), maxDatagramSize});
			for (DatagramPacket datagramPacket : datagrams) {
				if (!socket.isClosed()) {
					socket.send(datagramPacket);
				} else {
					LOGGER.log(Level.FINE, "Socket [{0}] is closed, discarding packet ...", config.getAddress());
				}
			}

		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Could not send datagram", e);
		} catch (GeneralSecurityException e) {
			LOGGER.log(
				Level.INFO,
				String.format("Cannot send flight to peer [%s]", flight.getPeerAddress()),
				e);
		}
	}

	private void handleTimeout(DTLSFlight flight) {

		// set DTLS retransmission maximum
		final int max = config.getMaxRetransmissions();

		// check if limit of retransmissions reached
		if (flight.getTries() < max) {
			LOGGER.log(Level.FINE, "Re-transmitting flight for [{0}], [{1}] retransmissions left",
					new Object[]{flight.getPeerAddress(), max - flight.getTries() - 1});

			flight.incrementTries();
			sendFlight(flight);

			// schedule next retransmission
			scheduleRetransmission(flight);
		} else {
			LOGGER.log(Level.FINE, "Flight for [{0}] has reached maximum no. [{1}] of retransmissions",
					new Object[]{flight.getPeerAddress(), max});
		}
	}

	private void scheduleRetransmission(DTLSFlight flight) {

		// cancel existing schedule (if any)
		if (flight.getRetransmitTask() != null) {
			flight.getRetransmitTask().cancel();
		}

		if (flight.isRetransmissionNeeded()) {
			// create new retransmission task
			flight.setRetransmitTask(new RetransmitTask(flight));
			
			// calculate timeout using exponential back-off
			if (flight.getTimeout() == 0) {
				// use initial timeout
				flight.setTimeout(config.getRetransmissionTimeout());
			} else {
				// double timeout
				flight.incrementTimeout();
			}
	
			// schedule retransmission task
			timer.schedule(flight.getRetransmitTask(), flight.getTimeout());
		}
	}

	/**
	 * Gets the MTU value of the network interface this connector is bound to.
	 * <p>
	 * Applications may use this property to determine the maximum length of application
	 * layer data that can be sent using this connector without requiring IP fragmentation.
	 * <p> 
	 * The value returned will be 0 if this connector is not running or the network interface
	 * this connector is bound to does not provide an MTU value.
	 * 
	 * @return the MTU provided by the network interface
	 */
	public final int getMaximumTransmissionUnit() {
		return maximumTransmissionUnit;
	}

	/**
	 * Gets the maximum amount of unencrypted payload data that can be sent to a given
	 * peer in a single DTLS record.
	 * <p>
	 * The value of this property serves as an upper boundary for the <em>DTLSPlaintext.length</em>
	 * field defined in <a href="http://tools.ietf.org/html/rfc6347#section-4.3.1">DTLS 1.2 spec,
	 * Section 4.3.1</a>. This means that an application can assume that any message containing at
	 * most as many bytes as indicated by this method, will be delivered to the peer in a single
	 * unfragmented datagram.
	 * </p>
	 * <p>
	 * The value returned by this method considers the <em>current write state</em> of the connection
	 * to the peer and any potential ciphertext expansion introduced by this cipher suite used to
	 * secure the connection. However, if no connection exists to the peer, the value returned is
	 * determined as follows:
	 * </p>
	 * <pre>
	 *   maxFragmentLength = network interface's <em>Maximum Transmission Unit</em>
	 *                     - IP header length (20 bytes)
	 *                     - UDP header length (8 bytes)
	 *                     - DTLS record header length (13 bytes)
	 *                     - DTLS message header length (12 bytes)
	 * </pre>
	 * 
	 * @param peer the address of the remote endpoint
	 * 
	 * @return the maximum length in bytes
	 */
	public final int getMaximumFragmentLength(InetSocketAddress peer) {
		Connection con = connectionStore.get(peer);
		if (con != null && con.getEstablishedSession() != null) {
			return con.getEstablishedSession().getMaxFragmentLength();
		} else {
			return maximumTransmissionUnit - DTLSSession.HEADER_LENGTH;
		}
	}

	/**
	 * Gets the address this connector is bound to.
	 * 
	 * @return the IP address and port this connector is bound to or configured to
	 *            bind to
	 */
	@Override
	public final InetSocketAddress getAddress() {
		if (socket == null) {
			return config.getAddress();
		} else {
			return new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
		}
	}

	public final boolean isRunning() {
		return running;
	}

	private class RetransmitTask extends TimerTask {

		private DTLSFlight flight;

		RetransmitTask(DTLSFlight flight) {
			this.flight = flight;
		}

		@Override
		public void run() {
			handleTimeout(flight);
		}
	}
	
	/**
	 * A worker thread for continuously doing repetitive tasks.
	 */
	private abstract class Worker extends Thread {

		/**
		 * Instantiates a new worker.
		 *
		 * @param name the name, e.g., of the transport protocol
		 */
		private Worker(String name) {
			super(name);
		}

		@Override
		public void run() {
			try {
				LOGGER.log(Level.CONFIG, "Starting worker thread [{0}]", getName());
				while (running) {
					try {
						doWork();
					} catch (ClosedByInterruptException e) {
						LOGGER.log(Level.CONFIG, "Worker thread [{0}] has been interrupted", getName());
					} catch (Exception e) {
						if (running) {
							LOGGER.log(Level.FINE, "Exception thrown by worker thread [" + getName() + "]", e);
						}
					}
				}
			} finally {
				LOGGER.log(Level.CONFIG, "Worker thread [{0}] has terminated", getName());
			}
		}

		/**
		 * Does the actual work.
		 * 
		 * Subclasses should do the repetitive work here.
		 * 
		 * @throws Exception if something goes wrong
		 */
		protected abstract void doWork() throws Exception;
	}

	@Override
	public void setRawDataReceiver(RawDataChannel messageHandler) {
		this.messageHandler = messageHandler;
	}

	/**
	 * Sets a handler to call back if an alert message is received from a peer.
	 * 
	 * @param errorHandler the handler to invoke
	 */
	public final void setErrorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
	}

	private void connectionClosed(InetSocketAddress peerAddress) {
		if (peerAddress != null) {
			connectionStore.remove(peerAddress);
		}
	}

	/** generate a random byte[] of length 32 **/
	private static byte[] randomBytes() {
		SecureRandom rng = new SecureRandom();
		byte[] result = new byte[32];
		rng.nextBytes(result);
		return result;
	}

	private void handleExceptionDuringHandshake(Throwable cause, AlertLevel level, AlertDescription description, Record record) {
		if (AlertLevel.FATAL.equals(level)) {
			terminateOngoingHandshake(record.getPeerAddress(), cause, description);
		} else {
			discardRecord(record, cause);
		}
	}

	private static void discardRecord(final Record record, final Throwable cause) {
		if (LOGGER.isLoggable(Level.FINEST)) {
			LOGGER.log(
				Level.FINEST,
				String.format("Discarding %s record from peer [%s]: ", record.getType(), record.getPeerAddress()),
				cause);
		} else if (LOGGER.isLoggable(Level.FINE)) {
			LOGGER.log(
				Level.FINE,
				"Discarding {0} record from peer [{1}]: {2}",
				new Object[]{record.getType(), record.getPeerAddress(), cause.getMessage()});
		}
	}
}
