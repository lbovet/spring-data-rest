package org.springframework.data.rest.webmvc.util;
/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Synchronization utility that let only one thread running at any given time and passing hand between them in
 * deterministic turns. Useful for troubleshooting race conditions.
 *
 * @author Laurent Bovet
 */
public class InterleavingThreadScheduler {

    private Map<Thread, Semaphore> mutexes = Collections.synchronizedMap(new LinkedHashMap<>());
    private Map<Semaphore, Set<Integer>> skippedSteps = new HashMap<>();
    private Map<Semaphore, AtomicInteger> currentStep = new HashMap<>();
    private Iterator<Semaphore> it;

    private static final Logger LOG = LoggerFactory.getLogger(InterleavingThreadScheduler.class);

    /**
     * Called by orchestrating thread to initialize threads and start the first one.
     * <p>
     * By default, each time next() is called, the hand is given to the next thread in the list.
     *
     * @param runnables        The code that will be executed in separate threads.
     * @param skippedStepsList For each thread, specify the steps, i.e. calls to next(), that must be ignored.
     *                         This allows for more advanced interleaving.
     */
    public void start(Runnable[] runnables, Integer[][] skippedStepsList) {
        final AtomicInteger i = new AtomicInteger(0);
        Stream.of(runnables).forEach(runnable -> {
            Semaphore mutex = new Semaphore(1);
            try {
                mutex.acquire(); // all threads must be initially blocked
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            Thread thread = new Thread(() -> {
                LOG.debug(Thread.currentThread() + " started");
                waitMyTurn(); // all threads must be initially blocked
                runnable.run();
                mutexes.put(Thread.currentThread(), null);
                next();
            });
            mutexes.put(thread, mutex);
            LOG.debug("Thread " + thread + " uses " + mutex);
            currentStep.put(mutex, new AtomicInteger(0));
            if (i.get() < skippedStepsList.length) {
                skippedSteps.put(mutex, new HashSet<>(Arrays.asList(skippedStepsList[i.getAndIncrement()])));
            }
        });
        mutexes.keySet().forEach(Thread::start); // start all threads
        getNext().release(); // release the first thread
        // wait for all of them to finish
        for (Thread thread : mutexes.keySet()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                // silently ignored
            }
        }
    }

    /**
     * Gives hand to next thread and block until another thread gives hand.
     */
    public void next() {
        long runningThreads = mutexes.values().stream().filter(item -> item != null).count();
        LOG.debug("Running threads: " + runningThreads);
        Semaphore current = mutexes.get(Thread.currentThread());
        if (runningThreads > 0 && (skippedSteps.get(current) == null || !skippedSteps.get(current).contains(currentStep.get(current).get()))) {
            while (true) {
                Semaphore next = getNext();
                if (next != null) {
                    LOG.debug("Releasing " + next);
                    next.release();
                    waitMyTurn();
                    break;
                }
            }
        }
        if (current != null) {
            currentStep.get(current).incrementAndGet();
        }
    }

    private void waitMyTurn() {
        Semaphore mutex = mutexes.get(Thread.currentThread());
        LOG.debug(Thread.currentThread() + " waiting turn on " + mutex);
        if (mutex != null) {
            // Block only if mutex still defined and if not all threads are done.
            try {
                mutex.acquire();
                LOG.debug(Thread.currentThread() + " has been released by " + mutex);
            } catch (InterruptedException e) {
                // silently ignored
            }
        }
    }

    private Semaphore getNext() {
        if (it == null || !it.hasNext()) {
            it = mutexes.values().iterator();
        }
        return it.next();
    }
}
