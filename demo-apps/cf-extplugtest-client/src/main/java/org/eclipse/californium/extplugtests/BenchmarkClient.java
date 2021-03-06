/*******************************************************************************
 * Copyright (c) 2018 Bosch Software Innovations GmbH and others.
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
 *    Bosch Software Innovations GmbH - initial implementation
 *    Achim Kraus (Bosch Software Innovations GmbH) - add transmission error statistic
 ******************************************************************************/

package org.eclipse.californium.extplugtests;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MessageObserver;
import org.eclipse.californium.core.coap.MessageObserverAdapter;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.network.config.NetworkConfig.Keys;
import org.eclipse.californium.core.network.config.NetworkConfigDefaultHandler;
import org.eclipse.californium.elements.UDPConnector;
import org.eclipse.californium.elements.util.DaemonThreadFactory;
import org.eclipse.californium.plugtests.ClientInitializer;
import org.eclipse.californium.plugtests.ClientInitializer.Arguments;
import org.eclipse.californium.plugtests.ClientInitializer.PlugPskStore;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.util.ByteArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple benchmark client.
 * 
 * Starts multiple parallel clients to send CON-POST requests. Print statistic
 * with retransmissions.
 */
public class BenchmarkClient {

	/** The logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkClient.class.getCanonicalName());
	/**
	 * File name for network configuration.
	 */
	private static final File CONFIG_FILE = new File("CaliforniumBenchmark.properties");
	/**
	 * Header for network configuration.
	 */
	private static final String CONFIG_HEADER = "Californium CoAP Properties file for Benchmark Client";
	/**
	 * Default maximum resource size.
	 */
	private static final int DEFAULT_MAX_RESOURCE_SIZE = 8192;
	/**
	 * Default block size.
	 */
	private static final int DEFAULT_BLOCK_SIZE = 1024;
	/**
	 * Default number of clients.
	 */
	private static final int DEFAULT_CLIENTS = 5;
	/**
	 * Default number of requests.
	 */
	private static final int DEFAULT_REQUESTS = 100;

	/**
	 * Special network configuration defaults handler.
	 */
	private static NetworkConfigDefaultHandler DEFAULTS = new NetworkConfigDefaultHandler() {

		@Override
		public void applyDefaults(NetworkConfig config) {
			config.setInt(Keys.MAX_RESOURCE_BODY_SIZE, DEFAULT_MAX_RESOURCE_SIZE);
			config.setInt(Keys.MAX_MESSAGE_SIZE, DEFAULT_BLOCK_SIZE);
			config.setInt(Keys.PREFERRED_BLOCK_SIZE, DEFAULT_BLOCK_SIZE);
			config.setInt(Keys.MAX_ACTIVE_PEERS, 10);
			config.setInt(Keys.MAX_PEER_INACTIVITY_PERIOD, 60 * 60 * 24); // 24h
			config.setInt(Keys.TCP_CONNECTION_IDLE_TIMEOUT, 60 * 60 * 12); // 12h
			config.setInt(Keys.TCP_CONNECT_TIMEOUT, 20);
			config.setInt(Keys.TCP_WORKER_THREADS, 2);
			config.setInt(Keys.NETWORK_STAGE_RECEIVER_THREAD_COUNT, 1);
			config.setInt(Keys.NETWORK_STAGE_SENDER_THREAD_COUNT, 1);
			config.setInt(Keys.PROTOCOL_STAGE_THREAD_COUNT, 1);
		}

	};

	/**
	 * Benchmark timeout. If no messages are exchanged within this timeout, the
	 * benchmark is stopped.
	 */
	private static final long DEFAULT_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(10000);
	/**
	 * Overall request down-counter.
	 */
	private static CountDownLatch overallRequestsDownCounter;
	/**
	 * Client counter.
	 */
	private static final AtomicInteger clientCounter = new AtomicInteger();
	/**
	 * Overall retransmission counter.
	 */
	private static final AtomicLong retransmissionCounter = new AtomicLong();
	/**
	 * Observer to detect retransmission.
	 */
	private static final MessageObserver retransmissionDetector = new MessageObserverAdapter() {

		@Override
		public void onRetransmission() {
			retransmissionCounter.incrementAndGet();
		}
	};

	/**
	 * Overall transmission error counter.
	 */
	private static final AtomicLong transmissionErrorCounter = new AtomicLong();
	/**
	 * Don't stop client on transmission errors.
	 */
	private static boolean noneStop;
	/**
	 * Client to be used for benchmark.
	 */
	private final CoapClient client;
	/**
	 * Endpoint to exchange messages.
	 */
	private final Endpoint endpoint;
	/**
	 * Per client request counter.
	 */
	private final AtomicInteger requestsCounter = new AtomicInteger();
	/**
	 * Indicate that client has stopped.
	 * 
	 * @see #stop()
	 */
	private final AtomicBoolean stop = new AtomicBoolean();

	/**
	 * Create client.
	 * 
	 * @param uri destination URI
	 * @param endpoint local endpoint to exchange messages
	 */
	public BenchmarkClient(URI uri, Endpoint endpoint) {
		client = new CoapClient(uri);
		this.endpoint = endpoint;
	}

	/**
	 * Start client. Start endpoint. Must be called before
	 * {@link #startBenchmark()}.
	 */
	public void start() {
		client.setEndpoint(endpoint);
	}

	/**
	 * Test request.
	 * 
	 * @return {@code true} on success, {@code false} on failure.
	 */
	public boolean test() {
		Request post = Request.newPost();
		post.setURI(client.getURI());
		CoapResponse response = client.advanced(post);
		if (response != null) {
			if (response.isSuccess()) {
				LOGGER.info("Received response: {}", response.advanced());
				return true;
			} else {
				LOGGER.warn("Received error response: {}", response.advanced());
			}
		} else {
			LOGGER.warn("Received no response!");
		}
		return false;
	}

	/**
	 * Start benchmark.
	 * 
	 * Prepare first request and follow-up request on response handler calls.
	 * Must be called after {@link #start()}
	 */
	public void startBenchmark() {
		Request post = Request.newPost();
		post.setURI(client.getURI());
		post.addMessageObserver(retransmissionDetector);
		client.advanced(new CoapHandler() {

			@Override
			public void onLoad(CoapResponse response) {
				if (response.isSuccess()) {
					next();
					long c = overallRequestsDownCounter.getCount();
					LOGGER.info("Received response: {} {}", response.advanced(), c);
				} else {
					LOGGER.warn("Received error response: {}", response.advanced());
					stop();
				}
			}

			@Override
			public void onError() {
				long c = requestsCounter.get();
				if (noneStop) {
					transmissionErrorCounter.incrementAndGet();
					LOGGER.info("Error after {} requests.", c);
					next();
				} else {
					LOGGER.error("failed after {} requests!", c);
					stop();
				}
			}

			public void next() {
				overallRequestsDownCounter.countDown();
				long c = overallRequestsDownCounter.getCount();
				if (0 < c) {
					requestsCounter.incrementAndGet();
					Request post = Request.newPost();
					post.setURI(client.getURI());
					post.addMessageObserver(retransmissionDetector);
					client.advanced(this, post);
				} else {
					stop();
				}
			}

		}, post);
		clientCounter.incrementAndGet();
	}

	/**
	 * Stop client.
	 * 
	 * @return number of requests processed by this client.
	 */
	public int stop() {
		if (stop.compareAndSet(false, true)) {
			clientCounter.decrementAndGet();
			endpoint.stop();
			client.shutdown();
			endpoint.destroy();
		}
		return requestsCounter.get();
	}

	public static void main(String[] args) throws InterruptedException {

		if (args.length == 0) {

			System.out.println("\nCalifornium (Cf) Benchmark Client");
			System.out.println("(c) 2018, Bosch Software Innovations GmbH and others");
			System.out.println();
			System.out.println("Usage: " + BenchmarkClient.class.getSimpleName() + " URI [#clients] [#requests] [nonestop]");
			System.out.println("  URI       : The CoAP URI of the extended Plugtest server to test");
			System.out.println("              (coap://<host>[:<port>]/benchmark  or coaps://<host>[:<port>]/benchmark)");
			System.out.println("  #clients  : number of clients. Default " + DEFAULT_CLIENTS + ".");
			System.out.println("  #requests : number of requests per clients. Default " + DEFAULT_REQUESTS + ".");
			System.out.println("  nonestop  : don't stop client if request fails (timeout)");
			System.out.println();
			System.out.println("Example: " + BenchmarkClient.class.getSimpleName() + " coap://localhost:5783/benchmark");
			System.out.println("Note: californium.eclipse.org doesn't support a benchmark and will response with 5.01, NOT_IMPLEMENTED!");
			System.exit(-1);
		}

		NetworkConfig config = NetworkConfig.createWithFile(CONFIG_FILE, CONFIG_HEADER, DEFAULTS);
		Arguments arguments = ClientInitializer.init(config, args);
		// random part of PSK identity
		SecureRandom random = new SecureRandom();
		byte[] id = new byte[8];

		URI uri = null;
		int clients = 5;
		int requests = 100;

		if (0 < arguments.args.length) {
			clients = Integer.parseInt(arguments.args[0]);
			if (1 < arguments.args.length) {
				requests = Integer.parseInt(arguments.args[1]);
				if (2 < arguments.args.length) {
					noneStop = arguments.args[2].equalsIgnoreCase("nonestop");
				}
			}
		}

		try {
			uri = new URI(arguments.uri);
		} catch (URISyntaxException e) {
			System.err.println("Invalid URI: " + e.getMessage());
			System.exit(-1);
		}

		int overallRequests = (requests * clients);
		overallRequestsDownCounter = new CountDownLatch(overallRequests);
		List<BenchmarkClient> clientList = new ArrayList<>(clients);
		ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),
				new DaemonThreadFactory("Aux#"));

		boolean plain = uri.getScheme().equals(CoAP.COAP_URI_SCHEME);

		System.out.format("Create %d %s%sbenchmark clients, expect to send %d request overall to %s%n", clients,
				noneStop ? "none-stop " : "", plain ? "" : "secure ", overallRequests, uri);

		final CountDownLatch start = new CountDownLatch(clients);

		// Create & start clients
		for (int index = 0; index < clients; ++index) {
			CoapEndpoint.CoapEndpointBuilder endpointBuilder = new CoapEndpoint.CoapEndpointBuilder();
			endpointBuilder.setNetworkConfig(config);
			if (plain) {
				endpointBuilder.setConnectorWithAutoConfiguration(new UDPConnector());
			} else {
				random.nextBytes(id);
				DtlsConnectorConfig.Builder dtlsBuilder = new DtlsConnectorConfig.Builder();
				dtlsBuilder.setPskStore(new PlugPskStore(ByteArrayUtils.toHex(id)));
				dtlsBuilder.setClientOnly();
				dtlsBuilder.setConnectionThreadCount(1);
				dtlsBuilder.setMaxConnections(10);
				endpointBuilder.setConnector(new DTLSConnector(dtlsBuilder.build()));
			}
			final BenchmarkClient client = new BenchmarkClient(uri, endpointBuilder.build());
			clientList.add(client);
			if (index == 0) {
				// first client, so test request
				client.start();
				start.countDown();
				if (!client.test()) {
					System.out.format("Request %s POST failed, exit Benchmark.%n", uri);
					System.exit(-1);
				}
			} else {
				executor.execute(new Runnable() {

					@Override
					public void run() {
						client.start();
						start.countDown();
					}
				});
			}
		}
		start.await();
		System.out.println("Benchmark clients created.");

		// Start Test
		boolean stale = false;
		long nanos = System.nanoTime();
		long lastRequestsCountDown = overallRequestsDownCounter.getCount();
		long lastRetransmissions = retransmissionCounter.get();
		long lastTransmissionErrrors = transmissionErrorCounter.get();

		for (BenchmarkClient client : clientList) {
			client.startBenchmark();
		}

		System.out.println("Benchmark started.");

		// Wait with timeout or all requests send.
		while (!overallRequestsDownCounter.await(DEFAULT_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
			long currentRequestsCountDown = overallRequestsDownCounter.getCount();
			int numberOfClients = clientCounter.get();
			if ((lastRequestsCountDown == currentRequestsCountDown && currentRequestsCountDown < overallRequests)
					|| (numberOfClients == 0)) {
				// no new requests, clients are stale, or no clients left
				// adjust start time with timeout
				nanos += DEFAULT_TIMEOUT_NANOS;
				stale = true;
				break;
			}
			long requestDifference = (lastRequestsCountDown - currentRequestsCountDown);
			long currentOverallSentRequests = overallRequests - currentRequestsCountDown;
			long retransmissions = retransmissionCounter.get();
			long retransmissionsDifference = retransmissions - lastRetransmissions;
			long transmissionErrors = transmissionErrorCounter.get();
			long transmissionErrorsDifference = transmissionErrors - lastTransmissionErrrors;
			lastRequestsCountDown = currentRequestsCountDown;
			lastRetransmissions = retransmissions;
			lastTransmissionErrrors = transmissionErrors;
			System.out.format("%d requests (%d reqs/s, %s, %s, %d clients)%n", currentOverallSentRequests,
					requestDifference / TimeUnit.NANOSECONDS.toSeconds(DEFAULT_TIMEOUT_NANOS),
					formatRetransmissions(retransmissionsDifference, requestDifference),
					formatTransmissionErrors(transmissionErrorsDifference, requestDifference), numberOfClients);
		}
		long overallSentRequests = overallRequests - overallRequestsDownCounter.getCount();
		nanos = System.nanoTime() - nanos;

		System.out.format("Benchmark clients %s.%n", stale ? "stopped" : "finished");

		// stop and collect per client requests
		int statistic[] = new int[clients];
		for (int index = 0; index < clients; ++index) {
			BenchmarkClient client = clientList.get(index);
			statistic[index] = client.stop();
		}

		System.out.format("%d requests sent, %d expected%n", overallSentRequests, overallRequests);
		System.out.format("%d requests in %dms%s%n", overallSentRequests, TimeUnit.NANOSECONDS.toMillis(nanos),
				formatRequestsPerSecond(overallSentRequests, nanos));
		long retransmissions = retransmissionCounter.get();
		if (retransmissions > 0) {
			System.out.println(formatRetransmissions(retransmissions, overallSentRequests));
		}
		long transmissionErrors = transmissionErrorCounter.get();
		if (transmissionErrors > 0) {
			System.out.println(formatTransmissionErrors(transmissionErrors, overallSentRequests));
		}
		if (overallSentRequests < overallRequests) {
			System.out.format("Stale at %d messages (%d%%)%n", overallSentRequests,
					(overallSentRequests * 100L) / overallRequests);
		}
		if (1 < clients) {
			Arrays.sort(statistic);
			int grouped = 10;
			int last = 0;
			if (overallRequests > 500000) {
				grouped = overallRequests / 50000;
			}
			for (int index = 1; index < clients; ++index) {
				if ((statistic[index] / grouped) > (statistic[last] / grouped)) {
					System.out.println(formatClientRequests(statistic, index, last));
					last = index;
				}
			}
			System.out.println(formatClientRequests(statistic, clients, last));
		}
	}

	private static String formatRetransmissions(long retransmissions, long requests) {
		try (Formatter formatter = new Formatter()) {
			if (requests > 0) {
				return formatter
						.format("%d retransmissions (%4.2f%%)", retransmissions, ((retransmissions * 100D) / requests))
						.toString();
			} else {
				return formatter.format("%d retransmissions (no response-messages received!)", retransmissions)
						.toString();
			}
		}
	}

	private static String formatTransmissionErrors(long transmissionErrors, long requests) {
		try (Formatter formatter = new Formatter()) {
			if (requests > 0) {
				return formatter.format("%d transmission errors (%4.2f%%)", transmissionErrors,
						((transmissionErrors * 100D) / requests)).toString();
			} else {
				return formatter.format("%d transmission errors (no response-messages received!)", transmissionErrors)
						.toString();
			}
		}
	}

	private static String formatRequestsPerSecond(long requests, long nanos) {
		long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
		if (millis > 0) {
			try (Formatter formatter = new Formatter()) {
				return formatter.format(", %d reqs/s", (requests * 1000) / millis).toString();
			}
		}
		return "";
	}

	private static String formatClientRequests(int statistic[], int index, int last) {
		try (Formatter formatter = new Formatter()) {
			formatter.format("%3d clients with %d", (index - last), statistic[last]);
			if (statistic[index - 1] != statistic[last]) {
				formatter.format(" to %d", statistic[index - 1]);
			}
			return formatter.format(" requests.").toString();
		}
	}
}
