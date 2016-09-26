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
package org.springframework.data.rest.webmvc.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Laurent Bovet
 */
public class InterleavingThreadSchedulerTest {

    @Test
    public void testInterleaving() {
        final List<Integer> traces = new ArrayList<>();
        final InterleavingThreadScheduler scheduler = new InterleavingThreadScheduler();
        Runnable r1 = () -> {
            traces.add(1);
            scheduler.next();
            traces.add(4);
            scheduler.next();
            traces.add(5);
        };
        Runnable r2 = () -> {
            traces.add(2);
            scheduler.next();
            traces.add(6);
            scheduler.next();
            traces.add(8);
        };
        Runnable r3 = () -> {
            traces.add(3);
            scheduler.next();
            traces.add(7);
            scheduler.next();
            traces.add(9);
        };
        scheduler.start(new Runnable[]{ r1, r2, r3}, new Integer[][] {{1}});
        Assert.assertArrayEquals(new Integer[]{ 1, 2, 3, 4, 5, 6, 7, 8, 9 }, traces.toArray());
    }
}
