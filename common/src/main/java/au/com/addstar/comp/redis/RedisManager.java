package au.com.addstar.comp.redis;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisException;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.pubsub.RedisPubSubListener;
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection;
import com.lambdaworks.redis.pubsub.api.async.RedisPubSubAsyncCommands;
import com.lambdaworks.redis.pubsub.api.sync.RedisPubSubCommands;

public class RedisManager {
	static final String RedisKey = "cmgr";
	static final String RedisBcastKey = "cmgr.b";
	
	private final ConfigurationSection redisConfig;
	
	private Map<String, RedisQueryHandler> queryHandlers;
	
	private RedisClient client;
	private RedisPubSubAsyncCommands<String, String> subscribeConnection;
	private RedisPubSubCommands<String, String> publishConnection;
	private RedisHandler handler;
	
	private long nextQueryID;
	private final ListMultimap<String, WaitFuture> waitingFutures;
	
	public RedisManager(ConfigurationSection redisConfig) {
		this.redisConfig = redisConfig;
		queryHandlers = Maps.newHashMap();
		nextQueryID = 0;
		waitingFutures = ArrayListMultimap.create();
	}
	
	public void initialize() throws RedisException {
		RedisURI uri = new RedisURI(redisConfig.getString("host", "localhost"), redisConfig.getInt("port", 6379), 30, TimeUnit.SECONDS);
		if (!Strings.isNullOrEmpty(redisConfig.getString("password"))) {
			uri.setPassword(redisConfig.getString("password"));
		}
		
		client = RedisClient.create(uri);
		// Create the connection for subscribing
		StatefulRedisPubSubConnection<String, String> connection = client.connectPubSub();
		subscribeConnection = connection.async();
		
		handler = new RedisHandler();
		subscribeConnection.addListener(handler);
		subscribeConnection.psubscribe(RedisKey + ".*>" + Bukkit.getServerName());
		subscribeConnection.psubscribe(RedisBcastKey + ".*");
		
		// Create the connection for publishing
		connection = client.connectPubSub();
		publishConnection = connection.sync();
	}
	
	/**
	 * Registers a query handler for one or more commands
	 * @param handler The handler to register
	 * @param commands The commands to register against
	 */
	public void registerQueryHandler(RedisQueryHandler handler, String... commands) {
		for (String command : commands) {
			queryHandlers.put(command.toLowerCase(), handler);
		}
	}
	
	/**
	 * Queries a particular server for some information
	 * @param serverId The ID of the server to query
	 * @param command The command to use
	 * @param args Any arguments needed
	 * @return A future to get the result
	 */
	public ListenableFuture<String> query(String serverId, String command, String... args) {
		// Prepare the data string
		long queryId = nextQueryID++;
		String data = String.format("q\01%d\01%s\01%s", queryId, command, StringUtils.join(args, '\01'));
		
		// Send it
		WaitFuture future = new WaitFuture(queryId);
		waitingFutures.put(serverId, future);
		send(serverId, data);
		
		return future;
	}
	
	private void send(String targetId, String data) {
		publishConnection.publish(String.format("%s.%s>%s", RedisKey, Bukkit.getServerName(), targetId), data);
	}
	
	private void handleQuery(String serverId, long queryId, String command, String[] args) {
		RedisQueryHandler handler = queryHandlers.get(command.toLowerCase());
		String data;
		if (handler == null) {
			// Unknown command
			data = String.format("e\01%d\01%s", queryId, "Unknown Command");
		} else {
			try {
				String result = handler.onQuery(command, args);
				data = String.format("r\01%d\01%s", queryId, result);
			} catch (QueryException e) {
				data = String.format("e\01%d\01%s", queryId, e.getMessage());
			}
		}
		
		// Reply
		send(serverId, data);
	}
	
	private void handleReply(String serverId, long queryId, String retVal, String error) {
		synchronized (waitingFutures) {
			List<WaitFuture> futures = waitingFutures.get(serverId);
			
			// Find and handle the correct future
			for (WaitFuture future : futures) {
				if (future.getQueryId() == queryId) {
					// All done
					if (retVal != null) {
						future.set(retVal);
					} else {
						future.setException(new QueryException(error));
					}
					
					futures.remove(future);
					break;
				}
			}
			
			// Remove expired futures
			Iterator<WaitFuture> it = waitingFutures.values().iterator();
			while (it.hasNext()) {
				WaitFuture future = it.next();
				if (future.isOld()) {
					future.setException(new QueryException("Timeout"));
					it.remove();
				}
			}
		}
	}
	
	/**
	 * A Pub-Sub handler
	 */
	private class RedisHandler implements RedisPubSubListener<String, String> {
		@Override
		public void message(String pattern, String channel, String message) {
			// Get the source server
			String sourceId;
			
			if (pattern.startsWith(RedisManager.RedisKey)) {
				// Server to server communication
				int start = channel.lastIndexOf('.') + 1;
				int end = channel.indexOf('>');
				sourceId = channel.substring(start, end);
			} else {
				// Broadcast
				int pos = pattern.indexOf('*');
				sourceId = channel.substring(pos);
			}
			
			// Make sure we arent listening to ourselves
			if (sourceId.equals(Bukkit.getServerName())) {
				return;
			}
			
			// Extract the parameters
			String[] dataParts = message.split("\01");
			String type = dataParts[0];
			long queryId = Long.parseLong(dataParts[1]);
			
			switch (type) {
			case "q": // query
				String command = dataParts[2];
				String[] args = Arrays.copyOfRange(dataParts, 3, dataParts.length);
				handleQuery(sourceId, queryId, command, args);
				break;
			case "r": // reply
				String retVal = dataParts[2];
				handleReply(sourceId, queryId, retVal, null);
				break;
			case "e": // error
				String error = dataParts[2];
				handleReply(sourceId, queryId, null, error);
				break;
			}
		}
		
		@Override
		public void message(String channel, String message) {}

		@Override
		public void subscribed(String channel, long count) {}

		@Override
		public void psubscribed(String pattern, long count) {}

		@Override
		public void unsubscribed(String channel, long count) {}

		@Override
		public void punsubscribed(String pattern, long count) {}
	}
	
	private static class WaitFuture extends AbstractFuture<String> {
		private final long queryId;
		private final long initializeTime;
		
		public WaitFuture(long queryId) {
			this.queryId = queryId;
			initializeTime = System.currentTimeMillis();
		}
		
		public long getQueryId() {
			return queryId;
		}
		
		/**
		 * Is this future too old (> 10 seconds)
		 * @return True if too old
		 */
		public boolean isOld() {
			if (System.currentTimeMillis() > initializeTime + 10000) {
				return true;
			} else {
				return false;
			}
		}
		
		@Override
		public boolean set(String value) {
			return super.set(value);
		}
		
		@Override
		public boolean setException(Throwable throwable) {
			// TODO Auto-generated method stub
			return super.setException(throwable);
		}
	}
}