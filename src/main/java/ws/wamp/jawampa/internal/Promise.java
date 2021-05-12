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

package ws.wamp.jawampa.internal;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Promise<V> {
    
    V value;
    boolean done = false;
    ExecutionException error;
    final Object mutex = new Object();

    public void resolve(V value) {
        synchronized (this.mutex) {
            if (this.done)
                throw new RuntimeException("Promise resolved multiple times!");
            this.value = value;
            this.done = true;
            this.mutex.notifyAll();
        }
    }
    
    public void resolveWithError(ExecutionException e) {
        synchronized (this.mutex) {
            if (this.done)
                throw new RuntimeException("Promise resolved multiple times!");
            this.error = e;
            this.done = true;
            this.mutex.notifyAll();
        }
    }
    
    public java.util.concurrent.Future<V> getFuture() {
        return new Future<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                synchronized (Promise.this.mutex) {
                    return Promise.this.done;
                }
            }

            @Override
            public V get() throws InterruptedException, ExecutionException {
                synchronized (Promise.this.mutex) {
                    while (!Promise.this.done) Promise.this.mutex.wait();
                    if (Promise.this.error != null) throw Promise.this.error;
                    return Promise.this.value;
                }
            }

            @Override
            public V get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException,
                    TimeoutException
            {
                synchronized (Promise.this.mutex) {
                    while (!Promise.this.done) unit.timedWait(Promise.this.mutex, timeout);
                    if (Promise.this.error != null) throw Promise.this.error;
                    return Promise.this.value;
                }
            }
        };
    }

}
