package org.infinispan.server.resp.commands.pubsub;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.CharsetUtil;

import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.commons.marshall.WrappedByteArray;
import org.infinispan.encoding.DataConversion;
import org.infinispan.metadata.Metadata;
import org.infinispan.notifications.cachelistener.filter.CacheEventConverter;
import org.infinispan.notifications.cachelistener.filter.EventType;
import org.infinispan.server.resp.commands.PubSubResp3Command;
import org.infinispan.server.resp.commands.Resp3Command;
import org.infinispan.server.resp.Resp3Handler;
import org.infinispan.server.resp.RespCommand;
import org.infinispan.server.resp.RespRequestHandler;
import org.infinispan.server.resp.SubscriberHandler;
import org.infinispan.server.resp.filter.EventListenerKeysFilter;
import org.infinispan.server.resp.logging.Log;
import org.infinispan.util.concurrent.AggregateCompletionStage;
import org.infinispan.util.concurrent.CompletionStages;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * @link https://redis.io/commands/subscribe/
 * @since 14.0
 */
public class SUBSCRIBE extends RespCommand implements Resp3Command, PubSubResp3Command {
   private static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass(), Log.class);

   public SUBSCRIBE() {
      super(-2, 0, 0, 0);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(Resp3Handler handler,
                                                      ChannelHandlerContext ctx,
                                                      List<byte[]> arguments) {
      SubscriberHandler subscriberHandler = new SubscriberHandler(handler.respServer(), handler);
      return subscriberHandler.handleRequest(ctx, this, arguments);
   }

   @Override
   public CompletionStage<RespRequestHandler> perform(SubscriberHandler handler,
                                                      ChannelHandlerContext ctx,
                                                      List<byte[]> arguments) {
      AggregateCompletionStage<Void> aggregateCompletionStage = CompletionStages.aggregateCompletionStage();
      for (byte[] keyChannel : arguments) {
         if (log.isTraceEnabled()) {
            log.tracef("Subscriber for channel: " + CharsetUtil.UTF_8.decode(ByteBuffer.wrap(keyChannel)));
         }
         WrappedByteArray wrappedByteArray = new WrappedByteArray(keyChannel);
         if (handler.specificChannelSubscribers().get(wrappedByteArray) == null) {
            SubscriberHandler.PubSubListener pubSubListener = new SubscriberHandler.PubSubListener(ctx.channel());
            handler.specificChannelSubscribers().put(wrappedByteArray, pubSubListener);
            byte[] channel = KeyChannelUtils.keyToChannel(keyChannel);
            DataConversion dc = handler.cache().getValueDataConversion();
            CompletionStage<Void> stage = handler.cache().addListenerAsync(pubSubListener, new EventListenerKeysFilter(channel), new CacheEventConverter<Object, Object, byte[]>() {
               @Override
               public byte[] convert(Object key, Object oldValue, Metadata oldMetadata, Object newValue, Metadata newMetadata, EventType eventType) {
                  return (byte[]) dc.fromStorage(newValue);
               }

               @Override
               public MediaType format() {
                  return MediaType.APPLICATION_OCTET_STREAM;
               }
            });
            aggregateCompletionStage.dependsOn(handler.handleStageListenerError(stage, keyChannel, true));
         }
      }
      return handler.sendSubscriptions(ctx, aggregateCompletionStage.freeze(), arguments, true);
   }
}
