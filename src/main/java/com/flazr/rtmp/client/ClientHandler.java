/*
 * Flazr <http://flazr.com> Copyright (C) 2009  Peter Thomas.
 *
 * This file is part of Flazr.
 *
 * Flazr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Flazr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Flazr.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.flazr.rtmp.client;

import com.flazr.io.flv.FlvWriter;

import com.flazr.rtmp.LoopedReader;
import com.flazr.rtmp.message.Control;
import com.flazr.rtmp.RtmpMessage;
import com.flazr.rtmp.RtmpReader;
import com.flazr.rtmp.RtmpPublisher;
import com.flazr.rtmp.RtmpPusher;
import com.flazr.rtmp.RtmpWriter;
import com.flazr.rtmp.message.BytesRead;
import com.flazr.rtmp.message.ChunkSize;
import com.flazr.rtmp.message.WindowAckSize;
import com.flazr.rtmp.message.Command;
import com.flazr.rtmp.message.Metadata;
import com.flazr.rtmp.message.SetPeerBw;
import com.flazr.util.ChannelUtils;
import com.flazr.util.Utils;
import java.util.HashMap;
import java.util.Map;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelPipelineCoverage("one")
public class ClientHandler extends SimpleChannelUpstreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private int transactionId = 1;
    private Map<Integer, String> transactionToCommandMap;
    private ClientOptions options;
    private byte[] swfvBytes;

    private RtmpWriter writer;

    private int bytesReadWindow = 2500000;
    private long bytesRead;
    private long bytesReadLastSent;    
    private int bytesWrittenWindow = 2500000;
    
    private RtmpPusher pusher;
    private int streamId;    

    public void setSwfvBytes(byte[] swfvBytes) {
        this.swfvBytes = swfvBytes;        
        logger.info("set swf verification bytes: {}", Utils.toHex(swfvBytes));        
    }

    public ClientHandler(ClientOptions options) {
        this.options = options;
        transactionToCommandMap = new HashMap<Integer, String>();        
    }

    private void writeCommandExpectingResult(Channel channel, Command command) {
        final int id = transactionId++;
        command.setTransactionId(id);
        transactionToCommandMap.put(id, command.getName());
        logger.info("sending command (expecting result): {}", command);
        channel.write(command);
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        logger.info("channel opened: {}", e);
        super.channelOpen(ctx, e);
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        logger.info("handshake complete, sending 'connect'");
        writeCommandExpectingResult(e.getChannel(), Command.connect(options));
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        logger.info("channel closed: {}", e);
        if (writer != null) {
            writer.close();
        }
        if (pusher != null) {
            pusher.close();
        }
        super.channelClosed(ctx, e);
    }
    
    @Override
    public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent me) {
        final Channel channel = me.getChannel();
        final RtmpMessage message = (RtmpMessage) me.getMessage();
        switch(message.getHeader().getMessageType()) {
            case CHUNK_SIZE: // handled by decoder
                break;
            case CONTROL:
                Control control = (Control) message;
                logger.debug("control: {}", control);
                switch(control.getType()) {
                    case PING_REQUEST:
                        final int time = control.getTime();
                        logger.debug("server ping: {}", time);
                        Control pong = Control.pingResponse(time);
                        logger.debug("sending ping response: {}", pong);
                        channel.write(pong);
                        break;
                    case SWFV_REQUEST:
                        if(swfvBytes == null) {
                            logger.warn("swf verification not initialized!" 
                                + " not sending response, server likely to stop responding / disconnect");
                        } else {
                            Control swfv = Control.swfvResponse(swfvBytes);
                            logger.info("sending swf verification response: {}", swfv);
                            channel.write(swfv);
                        }
                        break;
                    case STREAM_BEGIN:
                        if(pusher != null && !pusher.isStarted()) {
                            pusher.start(streamId, options.getStart(), options.getLength(), new ChunkSize(4096));
                            return;
                        }
                        if(streamId !=0) {
                            channel.write(Control.setBuffer(streamId, options.getBuffer()));
                        }
                        break;
                    default:
                        logger.debug("ignoring control message: {}", control);
                }
                break;
            case METADATA_AMF0:
            case METADATA_AMF3:
                Metadata metadata = (Metadata) message;
                if(metadata.getName().equals("onMetaData")) {
                    logger.debug("writing 'onMetaData': {}", metadata);
                    writer.write(message);
                } else {
                    logger.debug("ignoring metadata: {}", metadata);
                }
                break;
            case AUDIO:
            case VIDEO:
            case AGGREGATE:                
                writer.write(message);
                bytesRead += message.getHeader().getSize();
                if((bytesRead - bytesReadLastSent) > bytesReadWindow) {
                    logger.debug("sending bytes read ack {}", bytesRead);
                    bytesReadLastSent = bytesRead;
                    channel.write(new BytesRead(bytesRead));
                }
                break;
            case COMMAND_AMF0:
            case COMMAND_AMF3:
                Command command = (Command) message;                
                String name = command.getName();
                logger.debug("server command: {}", name);
                if (name.equals("_result")) {
                    String resultFor = transactionToCommandMap.get(command.getTransactionId());
                    logger.info("result for method call: {}", resultFor);
                    if(resultFor.equals("connect")) {
                        writeCommandExpectingResult(channel, Command.createStream());
                    } else if (resultFor.equals("createStream")) {
                        streamId = ((Double) command.getArg(0)).intValue();
                        logger.debug("streamId to use: {}", streamId);
                        if (options.getPublishType() != null) { // TODO append, record                            
                            RtmpReader reader;
                            if (options.getFileToPublish() != null) {
                                reader = RtmpPublisher.getReader(options.getFileToPublish());
                            } else {
                                reader = options.getReaderToPublish();
                            }
                            if (options.getLoop() > 1) {
                                reader = new LoopedReader(reader, options.getLoop());
                            }
                            pusher = new RtmpPusher(reader) {
                                @Override
                                public void onMessage(RtmpMessage message) {
                                    Channels.write(channel, message);
                                }
                                @Override
                                public void onStop(long time) {
                                    Channels.write(channel, Command.unpublish(streamId));
                                }
                            };                            
                            Channels.write(channel, Command.publish(streamId, options));
                            return;
                        } else {
                            writer = options.getWriterToSave();
                            if(writer == null) {
                                writer = new FlvWriter(options.getStart(), options.getSaveAs());
                            }
                            Channels.write(channel, Command.play(streamId, options));
                            Channels.write(channel, Control.setBuffer(streamId, 0));
                        }
                    } else {
                        logger.warn("un-handled server result for: {}", resultFor);
                    }
                } else if (name.equals("onStatus")) {
                    final Map<String, Object> temp = (Map) command.getArg(0);
                    final String code = (String) temp.get("code");
                    logger.info("onStatus code: {}", code);
                    if (code.equals("NetStream.Failed") // TODO cleanup
                            || code.equals("NetStream.Play.Failed")
                            || code.equals("NetStream.Play.Stop")
                            || code.equals("NetStream.Play.StreamNotFound")) {
                        logger.info("disconnecting, code: {}, bytes read: {}", code, bytesRead);
                        channel.close();
                        return;
                    }
                    if (code.equals("NetStream.Publish.Start") && pusher != null && !pusher.isStarted()) {
                        pusher.start(streamId, options.getStart(), options.getLength(), new ChunkSize(4096));
                        return;
                    }
                    if (pusher != null && code.equals("NetStream.Unpublish.Success")) {
                        logger.info("unpublish success, closing channel");
                        ChannelFuture future = Channels.write(channel, Command.closeStream(streamId));
                        future.addListener(ChannelFutureListener.CLOSE);
                        return;
                    }
                } else if(name.equals("close")) {
                    logger.info("server called close, closing channel");
                    Channels.close(channel);
                    return;
                } else if(name.equals("_error")) {
                    logger.error("closing channel, server resonded with error: {}", command);
                    Channels.close(channel);
                    return;
                } else {
                    logger.warn("ignoring server command: {}", command);
                }
                break;
            case BYTES_READ:
                logger.info("ack from server: {}", message);
                break;
            case WINDOW_ACK_SIZE:
                WindowAckSize was = (WindowAckSize) message;                
                if(was.getValue() != bytesReadWindow) {
                    Channels.write(channel, SetPeerBw.dynamic(bytesReadWindow));
                }                
                break;
            case SET_PEER_BW:
                SetPeerBw spb = (SetPeerBw) message;                
                if(spb.getValue() != bytesWrittenWindow) {
                    Channels.write(channel, new WindowAckSize(bytesWrittenWindow));
                }
                break;
            default:
            logger.info("ignoring rtmp message: {}", message);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        ChannelUtils.exceptionCaught(e);
    }    

}
