/*
 * Copyright 2015 Matthias Einwag
 *
 * The jawampa authors license this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package ws.wamp.jawampa.connection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

import ws.wamp.jawampa.ApplicationError;
import ws.wamp.jawampa.WampMessages.WampMessage;
import ws.wamp.jawampa.WampSerialization;

public class QueueingConnectionController implements IConnectionController {
    
    static class QueuedMessage {
        public final WampMessage message;
        public final IWampConnectionPromise<Void> promise;
        
        public QueuedMessage(WampMessage message, IWampConnectionPromise<Void> promise) {
            this.message = message;
            this.promise = promise;
        }
    }
    
    /** Possible states while closing the connection */
    enum CloseStatus {
        /** Close was not issued */
        None,
        /** Connection should be closed at the next possible point of time */
        CloseNow,
        /** Connection should be closed after all already queued messages have been sent */
        CloseAfterRemaining,
        /** Close was issued but not yet acknowledged */
        CloseSent,
        /** Close is acknowledged */
        Closed
    }
    
    final ICompletionCallback<Void> messageSentHandler = new ICompletionCallback<> () {
        @Override
        public void onCompletion(final IWampConnectionFuture<Void> future) {
            tryScheduleAction(new Runnable() {
                @Override
                public void run() {
                    // Dequeue the first element of the queue.
                    // Queue might be empty if closed in between
                    QueuedMessage first = QueueingConnectionController.this.queuedMessages.poll();
                    if (future.isSuccess())
                        first.promise.fulfill(null);
                    else {
                        first.promise.reject(future.error());
                    }
                    
                    /** Whether to close after this call */
                    boolean sendClose =
                        (QueueingConnectionController.this.closeStatus == CloseStatus.CloseNow) ||
                        (QueueingConnectionController.this.closeStatus == CloseStatus.CloseAfterRemaining && QueueingConnectionController.this.queuedMessages.size() == 0);
                    
                    if (sendClose) {
                        // Close the connection now
                        QueueingConnectionController.this.closeStatus = CloseStatus.CloseSent;
                        QueueingConnectionController.this.connection.close(true, QueueingConnectionController.this.connectionClosedPromise);
                    } else if (QueueingConnectionController.this.queuedMessages.size() >= 1) {
                        // There's more to send
                        WampMessage nextMessage = QueueingConnectionController.this.queuedMessages.peek().message;
                        QueueingConnectionController.this.messageSentPromise.reset(QueueingConnectionController.this.messageSentHandler, null);
                        QueueingConnectionController.this.connection.sendMessage(nextMessage, QueueingConnectionController.this.messageSentPromise);
                    }
                }
            });
        }
    };
    
    final ICompletionCallback<Void> connectionClosedHandler = new ICompletionCallback<> () {
        @Override
        public void onCompletion(final IWampConnectionFuture<Void> future) {
            tryScheduleAction(new Runnable() {
                @Override
                public void run() {
                    assert (QueueingConnectionController.this.closeStatus == CloseStatus.CloseSent);
                    // The connection is now finally closed
                    QueueingConnectionController.this.closeStatus = CloseStatus.Closed;
                    
                    // Complete all pending sends
                    while (QueueingConnectionController.this.queuedMessages.size() > 0) {
                        QueuedMessage nextMessage = QueueingConnectionController.this.queuedMessages.remove();
                        nextMessage.promise.reject(
                            new ApplicationError(ApplicationError.TRANSPORT_CLOSED));
                        // This could theoretically cause side effects.
                        // However it is not valid to call anything on the controller after
                        // close() anyway, so it isn't valid.
                    } 
                    
                    // Forward the result 
                    if (future.isSuccess()) QueueingConnectionController.this.queuedClose.fulfill(null);
                    else QueueingConnectionController.this.queuedClose.reject(future.error());
                    QueueingConnectionController.this.queuedClose = null;
                }
            });
        }
    };
    
    /**
     * Promise that will be fulfilled when the underlying connection
     * has sent a single message. The promise will be reused for
     * all messages that will be sent through this controller.
     */
    final WampConnectionPromise<Void> messageSentPromise =
        new WampConnectionPromise<>(this.messageSentHandler, null);
    
    /**
     * Promise that will be fulfilled when the connection was closed
     * and the close was acknowledged by the underlying connection.
     */
    final WampConnectionPromise<Void> connectionClosedPromise =
        new WampConnectionPromise<>(this.connectionClosedHandler, null);
    
    
    /** The scheduler on which all state transitions will run */
    final ScheduledExecutorService scheduler;
    /** The wrapped connection object. Must be injected later due to Router design */
    IWampConnection connection;
    /** The wrapped listener object */
    final IWampConnectionListener connectionListener;
    
    /** Queued messages */
    Deque<QueuedMessage> queuedMessages = new ArrayDeque<>();
    /** Holds the promise that will be fulfilled when the connection was closed */
    IWampConnectionPromise<Void> queuedClose = null;
    
    /** Whether to forward incoming messages or not */
    boolean forwardIncoming = true;
    CloseStatus closeStatus = CloseStatus.None;
    
    public QueueingConnectionController(ScheduledExecutorService scheduler, 
            IWampConnectionListener connectionListener) {
        this.scheduler = scheduler;
        this.connectionListener = connectionListener;
    }
    
    @Override
    public IWampConnectionListener connectionListener() {
        return this.connectionListener;
    }
    
    @Override
    public IWampConnection connection() {
        return this.connection;
    }
    
    @Override
    public void setConnection(IWampConnection connection) {
        this.connection = connection;
    }
    
    /**
     * Tries to schedule a runnable on the underlying executor.<br>
     * Rejected executions will be suppressed.<br>
     * This is useful for cases when the clients EventLoop is shut down before
     * the EventLoop of the underlying connection.
     * 
     * @param action The action to schedule.
     */
    private void tryScheduleAction(Runnable action) {
        try {
            this.scheduler.submit(action);
        } catch (RejectedExecutionException e) {}
    }
    
    // IWampConnection members 
    
    @Override
    public WampSerialization serialization() {
        return this.connection.serialization();
    }

    @Override
    public boolean isSingleWriteOnly() {
        return false;
    }

    @Override
    public void sendMessage(WampMessage message, IWampConnectionPromise<Void> promise) {
        if (this.closeStatus != CloseStatus.None)
            throw new IllegalStateException("close() was already called");
        
        this.queuedMessages.add(new QueuedMessage(message, promise));
        
        // Check if there is already a send in progress
        if (this.queuedMessages.size() == 1) {
            // We are first in queue. Send immediately
            this.messageSentPromise.reset(this.messageSentHandler, null);
            this.connection.sendMessage(message, this.messageSentPromise);
        }
    }

    @Override
    public void close(boolean sendRemaining, IWampConnectionPromise<Void> promise) {
        if (this.closeStatus != CloseStatus.None)
            throw new IllegalStateException("close() was already called");
        // Mark as closed. No other actions allowed after that
        if (sendRemaining) this.closeStatus = CloseStatus.CloseAfterRemaining;
        else this.closeStatus = CloseStatus.CloseNow;
        this.queuedClose = promise;
        
        // Avoid forwarding of new incoming messages
        this.forwardIncoming = false;
        
        if (this.queuedMessages.size() == 0) {
            // Can immediately start to close
            this.closeStatus = CloseStatus.CloseSent;
            this.connection.close(true, this.connectionClosedPromise);
        }
    }
    
    // IWampConnectionListener methods

    @Override
    public void transportClosed() {
        tryScheduleAction(new Runnable() {
            @Override
            public void run() {
                // Avoid forwarding more than once
                if (!QueueingConnectionController.this.forwardIncoming) return;
                QueueingConnectionController.this.forwardIncoming = false;
                
                QueueingConnectionController.this.connectionListener.transportClosed();
            }
        });
    }

    @Override
    public void transportError(final Throwable cause) {
        tryScheduleAction(new Runnable() {
            @Override
            public void run() {
                // Avoid forwarding more than once
                if (!QueueingConnectionController.this.forwardIncoming) return;
                QueueingConnectionController.this.forwardIncoming = false;
                
                QueueingConnectionController.this.connectionListener.transportError(cause);
            }
        });
    }

    @Override
    public void messageReceived(final WampMessage message) {
        tryScheduleAction(new Runnable() {
            @Override
            public void run() {
                // Drop messages that arrive after close
                if (!QueueingConnectionController.this.forwardIncoming) return;
                
                QueueingConnectionController.this.connectionListener.messageReceived(message);
            }
        });
    }
}
