/*
 * Copyright 2014 Matthias Einwag
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

package ws.wamp.jawampa.client;

import ws.wamp.jawampa.WampClient;
import ws.wamp.jawampa.connection.IConnectionController;
import ws.wamp.jawampa.connection.IPendingWampConnection;
import ws.wamp.jawampa.connection.IPendingWampConnectionListener;
import ws.wamp.jawampa.connection.IWampConnection;
import ws.wamp.jawampa.connection.IWampConnectionPromise;
import ws.wamp.jawampa.connection.QueueingConnectionController;

/** The session is trying to connect to the router */
public class ConnectingState implements ClientState, IPendingWampConnectionListener {
    
    private final StateController stateController;
    /** The currently active connection */
    IConnectionController connectionController;
    /** The current connection attempt */
    IPendingWampConnection connectingCon;
    /** Whether the connection attempt is cancelled */
    boolean isCancelled = false;
    /** How often connects should be attempted */
    int nrConnectAttempts;
    
    public ConnectingState(StateController stateController, int nrConnectAttempts) {
        this.stateController = stateController;
        this.nrConnectAttempts = nrConnectAttempts;
    }
    
    @Override
    public void onEnter(ClientState lastState) {
        if (lastState instanceof InitialState) {
            this.stateController.setExternalState(new WampClient.ConnectingState());
        }
        
        // Check for valid number of connects
        assert (this.nrConnectAttempts != 0);
        // Decrease remaining number of reconnects if it's not infinite
        if (this.nrConnectAttempts > 0) this.nrConnectAttempts--;
        
        // Starts an connection attempt to the router
        this.connectionController =
            new QueueingConnectionController(this.stateController.scheduler(), new ClientConnectionListener(this.stateController));
        
        try {
            this.connectingCon =
                this.stateController.clientConfig().connector().connect(this.stateController.scheduler(), this, this.connectionController);
        } catch (Exception e) {
            // Catch exceptions that can happen during creating the channel
            // These are normally signs that something is wrong with our configuration
            // Therefore we don't trigger retries
            this.stateController.setCloseError(e);
            this.stateController.setExternalState(new WampClient.DisconnectedState(e));
            DisconnectedState newState = new DisconnectedState(this.stateController, e);
            // This is a reentrant call to setState. However it works as onEnter is the last call in setState
            this.stateController.setState(newState);
        }
    }

    @Override
    public void onLeave(ClientState newState) {
        
    }
    
    @Override
    public void connectSucceeded(final IWampConnection connection) {
        boolean wasScheduled = this.stateController.tryScheduleAction(new Runnable() {
            @Override
            public void run() {
                if (!ConnectingState.this.isCancelled) {
                    // Our new channel is connected
                    ConnectingState.this.connectionController.setConnection(connection);
                    HandshakingState newState = new HandshakingState(ConnectingState.this.stateController, ConnectingState.this.connectionController, ConnectingState.this.nrConnectAttempts);
                    ConnectingState.this.stateController.setState(newState);
                } else {
                    // We we're connected but aren't interested in the channel anymore
                    // The client should close
                    // Therefore we close the new channel
                    ConnectingState.this.stateController.setExternalState(new WampClient.DisconnectedState(null));
                    WaitingForDisconnectState newState = new WaitingForDisconnectState(ConnectingState.this.stateController, ConnectingState.this.nrConnectAttempts);
                    connection.close(false, newState.closePromise());
                    ConnectingState.this.stateController.setState(newState);
                }
            }
        });

        if (!wasScheduled) {
            // If the client was closed before the connection
            // succeeds, close the connection
            connection.close(false, IWampConnectionPromise.Empty);
        }
    }
    
    @Override
    public void connectFailed(final Throwable cause) {
        this.stateController.tryScheduleAction(new Runnable() {
            @Override
            public void run() {
                if (!ConnectingState.this.isCancelled) {
                    // Try reconnect if possible, otherwise announce close
                    if (ConnectingState.this.nrConnectAttempts != 0) { // Reconnect is allowed
                        WaitingForReconnectState nextState = new WaitingForReconnectState(ConnectingState.this.stateController, ConnectingState.this.nrConnectAttempts);
                        ConnectingState.this.stateController.setState(nextState);
                    } else {
                        ConnectingState.this.stateController.setExternalState(new WampClient.DisconnectedState(cause));
                        DisconnectedState nextState = new DisconnectedState(ConnectingState.this.stateController, cause);
                        ConnectingState.this.stateController.setState(nextState);
                    }
                } else {
                    // Connection cancel attempt was successfully cancelled.
                    // This is the final state
                    ConnectingState.this.stateController.setExternalState(new WampClient.DisconnectedState(null));
                    DisconnectedState nextState = new DisconnectedState(ConnectingState.this.stateController, null);
                    ConnectingState.this.stateController.setState(nextState);
                }
            }
        });
    }

    @Override
    public void initClose() {
        if (this.isCancelled) return;
        this.isCancelled = true;
        this.connectingCon.cancelConnect();
    }
}