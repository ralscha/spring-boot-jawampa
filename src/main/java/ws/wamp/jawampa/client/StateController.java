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

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

import rx.Observable;
import rx.Scheduler;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;
import ws.wamp.jawampa.WampClient;
import ws.wamp.jawampa.WampClient.DisconnectedState;
import ws.wamp.jawampa.WampClient.State;
import ws.wamp.jawampa.WampMessages.WampMessage;

public class StateController {
    private boolean isCompleted = false;
    
    private ClientState currentState = new InitialState(this);
    private ClientConfiguration clientConfig;
    
    private final ScheduledExecutorService scheduler;
    private final Scheduler rxScheduler;
    
    /** The current externally visible status */
    private State extState = new DisconnectedState(null);
    /** Holds the final value with which {@link WampClient#status} will be completed */
    private Throwable closeError = null;
    /** Observable that provides the external state */
    private BehaviorSubject<State> statusObservable = BehaviorSubject.create(this.extState);
    
    public StateController(ClientConfiguration clientConfig) {
        this.clientConfig = clientConfig;
        this.scheduler = clientConfig.connectorProvider().createScheduler();
        this.rxScheduler = Schedulers.from(this.scheduler);
    }
    
    public ClientConfiguration clientConfig() {
        return this.clientConfig;
    }
    
    public ScheduledExecutorService scheduler() {
        return this.scheduler;
    }
    
    public Scheduler rxScheduler() {
        return this.rxScheduler;
    }
    
    public Observable<State> statusObservable() {
        return this.statusObservable;
    }
    
    public void setExternalState(State newState) {
        this.extState = newState;
        this.statusObservable.onNext(this.extState);
    }
    
    public void setCloseError(Throwable closeError) {
        this.closeError = closeError;
    }
    
    /**
     * Tries to schedule a runnable on the provided scheduler.<br>
     * Rejected executions will be suppressed.
     * 
     * @param action The action to schedule.
     * @return true if the Runnable could be scheduled, false otherwise
     */
    public boolean tryScheduleAction(Runnable action) {
        try {
            this.scheduler.execute(action);
            // Ignore this exception
            // The scheduling will be performed with best effort
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    public ClientState currentState() {
        return this.currentState;
    }
    
    public void setState(ClientState newState) {
        ClientState lastState = this.currentState;
        if (lastState != null) lastState.onLeave(newState);
        this.currentState = newState;
        newState.onEnter(lastState);
    }
    
    /**
     * Is called when the underlying connection received a message from the remote side.
     * @param message The received message
     */
    void onMessage(WampMessage message) {
        if (this.currentState instanceof SessionEstablishedState)
            ((SessionEstablishedState)this.currentState).onMessage(message);
        else if (this.currentState instanceof HandshakingState)
            ((HandshakingState)this.currentState).onMessage(message);
    }
    
    /**
     * Is called if the underlying connection was closed from the remote side.
     * Won't be called if the user issues the close, since the client will then move
     * to the {@link WaitingForDisconnectState} directly.
     * @param closeReason An optional reason why the connection closed.
     */
    void onConnectionClosed(Throwable closeReason) {
        if (this.currentState instanceof SessionEstablishedState)
            ((SessionEstablishedState)this.currentState).onConnectionClosed(closeReason);
        else if (this.currentState instanceof HandshakingState)
            ((HandshakingState)this.currentState).onConnectionClosed(closeReason);
    }
    
    /**
     * Initiates the open process.<br>
     * If open was initiated before nothing will happen.
     */
    public void open() {
        this.scheduler.execute(new Runnable() {
            @Override
            public void run() {
                if (!(StateController.this.currentState instanceof InitialState)) return;
                // Try to connect afterwards
                // This guarantees that the external state will always
                // switch to connecting, even when the attempt immediately
                // fails
                int nrConnects = StateController.this.clientConfig.totalNrReconnects();
                if (nrConnects == 0) nrConnects = 1;
                ConnectingState newState =
                    new ConnectingState(StateController.this, nrConnects);
                setState(newState);
            }
        });
    }
    
    /**
     * Initiates the close process.<br>
     * Will be called on {@link WampClient#close()} of the client
     */
    public void initClose() {
        tryScheduleAction(new Runnable() {
            @Override
            public void run() {
                if (StateController.this.isCompleted) return;// Check if already closed
                StateController.this.isCompleted = true;
                
                // Initialize the close sequence
                // The state will try to move to the final state
                StateController.this.currentState.initClose();
            }
        });
    }
    
    /**
     * Performs the shutdown once the statemachine is in it's terminal state.
     */
    public void performShutdown() {
        if (this.closeError != null)
            this.statusObservable.onError(this.closeError);
        else
            this.statusObservable.onCompleted();
        this.scheduler.shutdown();
    }
}
